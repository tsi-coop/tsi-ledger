# TSI Ledger

**An open-source, API-first double-entry accounting engine.**

Today, our enterprise systems are isolated. Sales Systems, HR, and Vendor Portals maintain their own data models, leaving the financial entries fragmented. Someone exports the financial entries from these systems, or we run a batch job to push the entries into the ERP or Financial Accounting system.

Accounting teams care deeply about having a single source of financial truth across systems. They get rightfully frustrated when integrations break or when a new system launches without a native accounting integration. Now, we are entering an era where AI agents can autonomously approve vendor payments based on contract agreements, adjust payroll based on real-time performance, and execute settlements across sales channels on the fly. 

Organisations will build fluid interfaces and workflows that cut across traditional software boundaries. They will need a single source of financial truth thats accessible real-time by the accounting teams. Anticipating this need,  we are soft launching TSI Ledger: an open-source, API-first double-entry accounting engine as part of our TSI Coop composable data infrastructure stack.

---

## Features

### API-native integration

Sales systems, HR platforms, or AI agents post transactions directly to a simple API, authenticated with their own API key. No CSV exports, no night-shift batch jobs, no fragile file drops.

### Strict accounting rules, enforced

Double-entry is not a convention here, it's a constraint the engine enforces. Every entry must balance (debits equal credits, checked at post time, not by trusting the caller), and every amount is stored as `NUMERIC(18,4)`, never a float, so rounding error can't creep into the ledger.

### Built-in multi-tenancy

Each posting source (an HRMS, a commerce platform, a lending product, an agent) is provisioned as an App with its own `API_KEY`/`API_SECRET` pair - `API_KEY` is a non-secret identifier, always visible in the console; `API_SECRET` is the actual credential, shown once. All Apps post against one shared, organization-wide Chart of Accounts, so the accounting team always has one ledger to look at instead of one per integration. An Admin can also grant a specific App (e.g. a Financials system) permission to manage the Chart of Accounts itself, via its own credentials, instead of every COA change requiring a console Admin session.

### Tamper-evident audit trail

Every journal entry is cryptographically chained to the one before it: each entry's block hash is derived from its own payload plus the previous entry's block hash, per posting source. Rewriting or deleting a past entry breaks the chain, and `verify_audit` walks it end-to-end to prove nothing was altered. Corrections are never done in place: reversals post as new, ordinary entries, so the record stays immutable.

### Maker-checker on corrections

An App reversing its own entry via its API key can do so immediately. A console operator reversing a manually posted entry cannot: it goes through a reversal request that a second operator (or an Admin) must approve or reject, so no single person can unilaterally rewrite the books.

### Self-service password recovery

An Operator locked out of the console doesn't need an Admin to hand them a new password directly. An Admin issues a one-time reset key instead - only its hash is ever stored, and it's shown once - which the Operator redeems themselves, email + key + new password, at a public reset page.

### Real-time financial reports

Trial balance, balance sheet, and income statement are computed live from the ledger over the API, no month-end close required to know where things stand.

---

## Quick start

**Prerequisites:** Docker and Docker Compose.

```bash
git clone https://github.com/tsi-coop/tsi-ledger.git
cd tsi-ledger
export TSI_BOOTSTRAP_TOKEN=$(openssl rand -hex 24)   # needed once, by the setup wizard
docker compose up -d
```

Once the containers are up, head to **http://localhost:8086/console** and complete the first-time setup wizard (enter your `TSI_BOOTSTRAP_TOKEN`) to create the first Admin operator. From there, **http://localhost:8086/tour** walks you through the Ops Console (Chart of Accounts, Apps/API keys, Journal Entries, and Reports) before you start posting real entries.

**Video walkthrough:** [https://youtu.be/NNxa64yXLOw](https://youtu.be/NNxa64yXLOw)

### Environment variables

All secrets have safe local defaults for evaluation. Override them for any non-local deployment.

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_DB` | `tsi_ledger` | Database name |
| `POSTGRES_USER` | `tsi_ledger_admin` | Database user |
| `POSTGRES_PASSWD` | `secure_dev_password` | Database password |
| `JWT_SECRET` | *(dev placeholder)* | Signs Ops Console operator session tokens (**change in production**) |
| `TSI_BOOTSTRAP_TOKEN` | *(none)* | Required by the first-run setup wizard. Setup is disabled until you set it (e.g. `openssl rand -hex 24`) |
| `TSI_LEDGER_ENV` | `local` | Deployment environment tag |
| `ALLOWED_ORIGINS` | `http://localhost:8086` | Comma-separated origins allowed to call the API from a browser |
| `APP_PORT_MAP` | `8086:8080` | Host:container port mapping |
| `DB_PORT_MAP` | `5438:5432` | PostgreSQL port mapping |

Ports default to 8086/5438 rather than the usual 8080/5432 so TSI Ledger can run alongside other TSI products (TSI Compass, TSI Nexus, ...) on the same evaluation host without colliding. Only override `APP_PORT_MAP`/`DB_PORT_MAP` if 8086/5438 also collide with something on your machine.

---

## Project structure

```
tsi-ledger/
├── db/
│   └── 01_init.sql               # Full schema: operators, apps, accounts, journal_entries,
│                                  # entry_lines, reversal_requests
├── src/
│   └── org/tsicoop/ledger/
│       ├── framework/            # Servlet filter, routing, JWT, DB pool, hashing, JSON schema validation
│       └── service/v1/           # One class per API module (Auth, App, Accounts, Ledger, Reports, Audit, Users)
├── web/
│   ├── index.html                # Login page
│   ├── tour/                     # Guided Ops Console walkthrough
│   ├── console/                  # Authenticated Ops Console (Chart of Accounts, Apps, Journal Entries, Reports, Audit, Users)
│   └── WEB-INF/
│       ├── validator/            # One JSON Schema per API function, enforced before it runs
│       └── _processor.tsi        # URL to Java class routing table
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

---

## API

All API calls are `POST /api/v1/{module}` with `Content-Type: application/json`. Every request body includes a `_func` field that selects the operation:

```json
{ "_func": "post_entry", "app_id": "...", "reference_id": "INV-1042", "lines": [...] }
```

**Authentication for posting sources** (Apps/AI agents):
```
X-API-Key: <key>
X-API-Secret: <secret>
```
`X-API-Key` is a non-secret identifier (visible any time in the console); `X-API-Secret` is the actual credential, shown only once at creation or rotation.

**Authentication for the Ops Console** (Admins/Operators): session JWT issued by `Auth.login`.

| Module | Functions |
|--------|-----------|
| `auth` | `login`, `logout`, `me`, `reset_with_key` |
| `setup` | `initial_setup`, `setup_status` |
| `app` | `create_app`, `list_apps`, `get_app`, `update_app`, `rotate_api_key`, `revoke_api_key`, `deactivate_app` |
| `accounts` | `create_account`, `list_accounts`, `get_account`, `deactivate_account`, `activate_account` - writes require the console Admin role, or an App with `can_manage_accounts=true` |
| `ledger` | `post_entry`, `list_entries`, `get_entry`, `reverse_entry`, `request_reversal`, `approve_reversal`, `reject_reversal`, `list_reversal_requests` |
| `reports` | `trial_balance`, `balance_sheet`, `income_statement`, `export_entries` |
| `audit` | `verify_audit` |
| `users` | `create_operator`, `list_operators`, `get_operator`, `update_operator`, `deactivate_operator`, `reset_password`, `change_own_password`, `generate_reset_key` |

Every function's input is validated against a JSON Schema in `web/WEB-INF/validator/` before the request reaches application code.

---

## Testing

```bash
mvn test
```

`HashChainTest` (`test/org/tsicoop/ledger/service/v1/`) is a pure unit test of the canonical-payload/hash-chain math - no database needed, always runs.

`LedgerIntegrationTest` exercises `post_entry`/`reverse_entry` against a real Postgres (balance check, idempotency replay, and concurrent-post locking - the FOR UPDATE row lock that keeps two simultaneous posts to the same App from racing past each other). It skips itself with a clear message if no database is configured, so plain `mvn test` stays green. Point it at the docker-compose dev database:

```bash
POSTGRES_HOST=jdbc:postgresql://localhost:5438 POSTGRES_DB=tsi_ledger \
POSTGRES_USER=tsi_ledger_admin POSTGRES_PASSWD=<your .env value> \
TSI_LEDGER_ENV=local mvn test
```

Each test run creates its own App/Accounts fixtures under a random suffix and tears them down afterward, so it's safe to run against a database that already has real data in it.

---

## License & Contributions

This project is fully open-source and distributed under the Apache 2.0 License. You are completely free to fork, modify, and customize the codebase to fit your specific technical or enterprise needs without any restriction.

**Contributing back to the main project**

If you have built an optimization, bug fix, or feature extension that you believe would add value to the core platform, we would love to review it. To ensure the main repository remains highly stable and securely managed, direct commits to the main branch are restricted.

If you wish to give back your changes to the project, please follow this process:

**Email the Repository Owner:** Send a brief summary of your modifications and a link to your code branch directly to admin@tsicoop.org.

Every contribution is manually evaluated for architectural alignment, readability, and long-term maintenance impact before integration. Thank you for respecting this workflow and helping us maintain a clean, resilient core!
