/* TSI Ledger - Ops Console shared client helpers.
 *
 * One credential realm: an email/password login (Auth.login) issues a
 * session cookie good for every console call (Users, Apps/API-key
 * provisioning, Chart of Accounts, org-wide ledger browsing, reversal
 * request/approve, reports, raw export). No console page ever asks for or
 * holds an API key - Apps authenticate to the API directly with their own
 * X-API-Key, server-to-server, never through this browser UI.
 */

function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;');
}

function qs(id) { return document.getElementById(id); }

function fmtDate(iso) {
  if (!iso) return '-';
  try { return new Date(iso).toLocaleString(); } catch (e) { return iso; }
}

function fmtAmount(v) {
  if (v === null || v === undefined || v === '') return '-';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

function shortId(id) {
  if (!id) return '-';
  return id.length > 13 ? id.slice(0, 8) + '…' + id.slice(-4) : id;
}

function genIdempotencyKey() {
  return (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
    : 'ik-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

async function copyText(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    const ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand('copy'); document.body.removeChild(ta);
  }
  if (btn) {
    const orig = btn.textContent;
    btn.textContent = 'Copied!';
    setTimeout(() => { btn.textContent = orig; }, 1200);
  }
}

/* ---------- Console session (cookie), one call helper for every module ---------- */

async function consoleApi(path, func, extra, headers) {
  const h = Object.assign({ 'Content-Type': 'application/json' }, headers || {});
  const body = Object.assign({ _func: func }, extra || {});
  const res = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: h,
    body: JSON.stringify(body)
  });
  if (res.status === 401) {
    const here = encodeURIComponent(location.pathname + location.search);
    location.href = 'login.html?next=' + here;
    throw new Error('Not authenticated');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(data.message || ('Request failed (' + res.status + ')'));
    err.status = res.status;
    err.body = data;
    throw err;
  }
  return data;
}

/** Downloads a CSV (or any file) response as a browser download - export_entries isn't JSON. */
async function consoleDownload(path, func, extra, filenameFallback) {
  const res = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(Object.assign({ _func: func }, extra || {}))
  });
  if (res.status === 401) {
    location.href = 'login.html?next=' + encodeURIComponent(location.pathname + location.search);
    throw new Error('Not authenticated');
  }
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || ('Request failed (' + res.status + ')'));
  }
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') || '';
  const match = disposition.match(/filename="([^"]+)"/);
  const filename = match ? match[1] : filenameFallback;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
}

/* ---------- Signed-in operator (role-aware nav, page-level gating) ---------- */

let currentUser = null;
let resolveMeReady;
const meReady = new Promise((resolve) => { resolveMeReady = resolve; });

async function loadMe() {
  try {
    currentUser = await consoleApi('/api/v1/auth', 'me');
  } catch (e) {
    currentUser = null;
  }
  resolveMeReady(currentUser);
  return currentUser;
}

function isAdmin() { return currentUser && currentUser.role === 'ADMIN'; }

/* ---------- Shared page shell (sidebar nav) ---------- */

// Tour is intentionally not listed here - it lives outside the console
// (linked from the bottom of login.html/setup/init.html instead), reachable
// without signing in.
const NAV_ITEMS = [
  { key: 'dashboard', href: 'dashboard.html', label: 'Dashboard' },
  { key: 'accounts', href: 'accounts.html', label: 'Chart of Accounts' },
  { key: 'entries', href: 'entries.html', label: 'Journal Entries' },
  { key: 'apps', href: 'apps.html', label: 'Apps', adminOnly: true },
  { key: 'users', href: 'users.html', label: 'Users', adminOnly: true },
  { key: 'audit', href: 'audit.html', label: 'Audit' },
  { key: 'reports', href: 'reports.html', label: 'Reports' }
];

function renderShell(activeKey, pageTitle, pageSub) {
  document.body.insertAdjacentHTML('afterbegin', `
    <div class="shell-topbar">
      <button id="shell-menu-btn" aria-label="Menu">&#9776;</button>
      <strong>TSI Ledger</strong>
      <span></span>
    </div>
    <div class="shell-backdrop" id="shell-backdrop"></div>
    <nav class="shell-sidebar" id="shell-sidebar">
      <div class="shell-brand">TSI <span>Ledger</span></div>
      <div class="shell-nav" id="shell-nav">
        ${NAV_ITEMS.map(i => `<a href="${i.href}" class="${i.key === activeKey ? 'active' : ''}" data-admin-only="${!!i.adminOnly}">${i.label}</a>`).join('')}
      </div>
      <div class="shell-foot">
        <div id="shell-user" class="muted" style="font-size:0.78rem;margin-bottom:8px">Loading…</div>
        <button id="shell-logout-btn">Log Out</button>
      </div>
    </nav>
    <main class="shell-main">
      <div id="shell-page-header">
        <h1>${esc(pageTitle)}</h1>
        ${pageSub ? `<p class="page-sub">${pageSub}</p>` : ''}
      </div>
      <div id="shell-content"></div>
    </main>
  `);

  const sidebar = qs('shell-sidebar');
  const backdrop = qs('shell-backdrop');
  qs('shell-menu-btn').addEventListener('click', () => { sidebar.classList.add('open'); backdrop.classList.add('open'); });
  backdrop.addEventListener('click', () => { sidebar.classList.remove('open'); backdrop.classList.remove('open'); });

  qs('shell-logout-btn').addEventListener('click', async () => {
    try { await consoleApi('/api/v1/auth', 'logout'); } catch (e) { /* redirecting or already logged out either way */ }
    location.href = 'login.html';
  });

  loadMe().then((me) => {
    if (!me) return;
    qs('shell-user').innerHTML = `<strong style="color:#e2e8f0">${esc(me.name)}</strong><br>${esc(me.role)}`;
    if (me.role !== 'ADMIN') {
      qs('shell-nav').querySelectorAll('a[data-admin-only="true"]').forEach(a => a.style.display = 'none');
    }
  });

  return qs('shell-content');
}
