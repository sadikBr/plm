#!/usr/bin/env bash
# Prints an access token for a dev user from the local Keycloak.
#
#   scripts/token.sh            # plmadmin, the built-in site administrator (its password: PLM_ADMIN_PASSWORD)
#   scripts/token.sh bob        # alice / bob / carol: password = username
#
# Usage: TOKEN=$(scripts/token.sh bob); curl -H "Authorization: Bearer $TOKEN" ...
set -euo pipefail

user="${1:-plmadmin}"
issuer="${PLM_OIDC_ISSUER:-http://localhost:8180/realms/plm}"
password="$user"
if [[ "$user" == "plmadmin" ]]; then password="${PLM_ADMIN_PASSWORD:-plmadmin}"; fi

response=$(curl -s \
  -d grant_type=password \
  -d client_id=plm-web \
  -d "username=${user}" \
  --data-urlencode "password=${password}" \
  "${issuer}/protocol/openid-connect/token") || {
  echo "Could not reach Keycloak at ${issuer}. Is it up (./plm start, or make up)?" >&2
  exit 1
}
case "$response" in
  *access_token*) ;;
  *"not fully set up"*)
    echo "${user} must choose a password first: sign in once at http://localhost:5173, then set PLM_ADMIN_PASSWORD to it." >&2
    exit 1
    ;;
  *)
    echo "Could not get a token for '${user}': ${response}" >&2
    [[ "$user" == "plmadmin" ]] && echo "Set PLM_ADMIN_PASSWORD to the password you chose for plmadmin." >&2
    exit 1
    ;;
esac

# Extract access_token without needing jq.
printf '%s' "$response" | sed -E 's/.*"access_token":"([^"]+)".*/\1/'
