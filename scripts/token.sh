#!/usr/bin/env bash
# Prints an access token for a dev user from the local Keycloak.
#
#   scripts/token.sh            # alice (site admin)
#   scripts/token.sh bob        # bob / carol: password = username
#
# Usage: TOKEN=$(scripts/token.sh bob); curl -H "Authorization: Bearer $TOKEN" ...
set -euo pipefail

user="${1:-alice}"
issuer="${PLM_OIDC_ISSUER:-http://localhost:8180/realms/plm}"

response=$(curl -sf \
  -d grant_type=password \
  -d client_id=plm-web \
  -d "username=${user}" \
  -d "password=${user}" \
  "${issuer}/protocol/openid-connect/token") || {
  echo "Could not get a token for '${user}' from ${issuer}. Is Keycloak up (make up)?" >&2
  exit 1
}

# Extract access_token without needing jq.
printf '%s' "$response" | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
