#!/usr/bin/env bash
# Creates the org's one-and-only Admin account (initial_setup 409s if this
# has already run) and leaves a reusable console session cookie jar behind
# for every other script in this folder.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source lib/common.sh

ADMIN_NAME="${ADMIN_NAME:-Demo Admin}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@tsicoop.org}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Arundhati123!}"
ADMIN_JAR="$STATE_DIR/admin_cookies.txt"

status_resp="$(curl -sS -X POST "$BASE_URL/api/v1/setup" -H 'Content-Type: application/json' -d '{"_func":"setup_status"}')"
configured="$(echo "$status_resp" | jq -r '.configured')"

if [[ "$configured" != "true" ]]; then
  log "No operators exist yet - running initial_setup as $ADMIN_EMAIL"
  api_console setup "$(jq -n --arg n "$ADMIN_NAME" --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" \
    '{_func:"initial_setup", name:$n, email:$e, password:$p}')" "$ADMIN_JAR" | jq '{id, name, email, role}'
else
  log "Ledger is already configured - reusing \$ADMIN_EMAIL/\$ADMIN_PASSWORD (override via env if this isn't the right Admin)"
fi

log "Logging in as $ADMIN_EMAIL"
api_console auth "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" \
  '{_func:"login", email:$e, password:$p}')" "$ADMIN_JAR" | jq '{authenticated, name, email, role}'

cat > "$STATE_DIR/admin.env" <<EOF
ADMIN_EMAIL=$ADMIN_EMAIL
ADMIN_PASSWORD=$ADMIN_PASSWORD
EOF

log "Admin session ready - cookie jar: $ADMIN_JAR"
