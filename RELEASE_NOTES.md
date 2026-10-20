# Release Notes

### Unreleased
**Security**
- Added a server-side authentication gate for the Ops Console pages (`dashboard`, `apps`, `entries`, `audit`, `reports`, `accounts`, `users`). Previously these pages were served to any HTTP client and the only login check was client-side JavaScript that ran after the page had been delivered. Requests without a valid session cookie are now redirected to the login page. API calls were already authenticated independently and are unchanged.
- The first-run setup endpoint (`initial_setup`) now requires a `setup_token` matching the new `TSI_BOOTSTRAP_TOKEN` environment variable. If the variable is not set, setup is disabled and the endpoint returns `503`; there is no default value. The setup wizard has a new Setup Token field. **Action required for new deployments:** set `TSI_BOOTSTRAP_TOKEN` before running the setup wizard.
- The first-run setup existence check and insert now run in a single `SERIALIZABLE` transaction, so two concurrent requests can no longer both create an Admin.
- Fixed the API dispatcher validating the request payload against its JSON schema *before* checking authentication, which let unauthenticated callers learn parameter names from `400` responses. Authentication now runs first: a missing or invalid credential returns `401` with no field names, and payload validation only runs for authenticated callers (or for functions that are public by design).
- Disabled directory listing (e.g. `/console/setup/`) by overriding Jetty's default servlet with `dirAllowed=false`.

---
