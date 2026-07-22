/* TSI Ledger — Ops Console shared client helpers.
 *
 * Two separate credential realms, matching the backend exactly (see
 * InterceptingFilter): admin calls (/api/v1/app) ride the JWT session
 * cookie set by admin_login; every other module needs a specific tenant
 * App's API key, which the backend only ever shows once at create/rotate
 * time and never lets an admin session retrieve again. So the console
 * cannot "know" a tenant's key — the operator pastes it in, it's held in
 * sessionStorage (cleared when the tab closes), and it's sent as
 * X-API-Key only from the browser directly. There is no server-side
 * tenant session; this is not a workaround, it's the same model the API
 * itself uses.
 */

const TENANT_KEY_STORAGE = 'tsi_ledger_tenant_api_key';

function esc(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;');
}

function qs(id) { return document.getElementById(id); }

function fmtDate(iso) {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch (e) { return iso; }
}

function fmtAmount(v) {
  if (v === null || v === undefined || v === '') return '—';
  const n = Number(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

function shortId(id) {
  if (!id) return '—';
  return id.length > 13 ? id.slice(0, 8) + '…' + id.slice(-4) : id;
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

/* ---------- Admin realm (cookie session, /api/v1/app) ---------- */

async function adminApi(func, extra) {
  const body = Object.assign({ _func: func }, extra || {});
  const res = await fetch('/api/v1/app', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (res.status === 401) {
    const here = encodeURIComponent(location.pathname + location.search);
    location.href = 'login.html?next=' + here;
    throw new Error('Not authenticated');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || ('Request failed (' + res.status + ')'));
  return data;
}

/* ---------- Tenant realm (X-API-Key, per selected App) ---------- */

function getTenantKey() { return sessionStorage.getItem(TENANT_KEY_STORAGE) || ''; }
function setTenantKey(key) { sessionStorage.setItem(TENANT_KEY_STORAGE, key); }
function clearTenantKey() { sessionStorage.removeItem(TENANT_KEY_STORAGE); }

class TenantAuthError extends Error {}

async function tenantApi(path, func, extra, headers) {
  const key = getTenantKey();
  if (!key) throw new TenantAuthError('No tenant API key set');

  const h = Object.assign({ 'Content-Type': 'application/json', 'X-API-Key': key }, headers || {});
  const body = Object.assign({ _func: func }, extra || {});
  const res = await fetch(path, { method: 'POST', headers: h, body: JSON.stringify(body) });

  if (res.status === 401) {
    clearTenantKey();
    throw new TenantAuthError('Tenant API key rejected');
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

/**
 * Renders the "connected as" bar for tenant-scoped pages into containerId.
 * Calls onReady() once a key is present (already stored, or just entered),
 * and re-renders (without re-running onReady) after "Change".
 */
function renderTenantKeyBar(containerId, onReady) {
  const el = qs(containerId);

  function draw() {
    const key = getTenantKey();
    if (!key) {
      el.innerHTML = `
        <div class="card">
          <h2>Connect to a Tenant App</h2>
          <p class="page-sub">This page reads/writes one App's ledger data, so it needs that App's live or test
             API key (shown once when the key was created or rotated on the <a href="apps.html">Apps</a> page).</p>
          <div class="field" style="max-width:480px">
            <label for="tenant-key-input">App API Key</label>
            <input id="tenant-key-input" type="password" placeholder="sk_live_… or sk_test_…" autocomplete="off">
          </div>
          <button class="primary" id="tenant-key-connect">Connect</button>
          <p id="tenant-key-error" class="alert error" style="display:none;margin-top:12px"></p>
        </div>`;
      qs('tenant-key-connect').addEventListener('click', () => {
        const v = qs('tenant-key-input').value.trim();
        if (!v) return;
        setTenantKey(v);
        draw();
        onReady();
      });
      qs('tenant-key-input').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') qs('tenant-key-connect').click();
      });
    } else {
      el.innerHTML = `
        <div class="flex-between" style="margin-bottom:20px">
          <span class="badge blue">Connected: <code>${esc(key.slice(0, 11))}…${esc(key.slice(-4))}</code></span>
          <button class="small" id="tenant-key-change">Change Key</button>
        </div>`;
      qs('tenant-key-change').addEventListener('click', () => { clearTenantKey(); draw(); });
    }
  }

  draw();
  if (getTenantKey()) onReady();
}

/** Shows a standard "key rejected" recovery prompt inside a target element. */
function tenantAuthErrorHtml() {
  return `<div class="alert error">That API key was rejected (revoked, wrong environment, or the App was
    deactivated). <a href="#" onclick="clearTenantKey();location.reload();return false;">Enter a different key</a>.</div>`;
}

/* ---------- Shared page shell (sidebar nav) ---------- */

const NAV_ITEMS = [
  { key: 'dashboard', href: 'dashboard.html', label: 'Dashboard' },
  { key: 'apps', href: 'apps.html', label: 'Apps' },
  { key: 'accounts', href: 'accounts.html', label: 'Chart of Accounts' },
  { key: 'entries', href: 'entries.html', label: 'Journal Entries' },
  { key: 'audit', href: 'audit.html', label: 'Audit Verification' },
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
      <div class="shell-brand">TSI <span>Ledger</span> Ops</div>
      <div class="shell-nav">
        ${NAV_ITEMS.map(i => `<a href="${i.href}" class="${i.key === activeKey ? 'active' : ''}">${i.label}</a>`).join('')}
      </div>
      <div class="shell-foot">
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
    try { await adminApi('admin_logout'); } catch (e) { /* redirecting or already logged out either way */ }
    location.href = 'login.html';
  });

  return qs('shell-content');
}
