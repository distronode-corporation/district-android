# shellcheck shell=bash
# Shared by the verify-*-against-db.sh scripts: result helpers, route warm-up, and a real bearer
# token minted through the native PKCE flow. Sourced, never executed.
#
# ⚠️ THE CALLER SETS `BASE` (the server URL) BEFORE CALLING ANYTHING HERE. Every function reads it
# rather than taking it as an argument, the same way the scripts themselves already did.
#
# ⛔ ONE COPY OF THE AUTH FLOW. The redirect URI, the token body's fields and the dev session cookie
# are what a server-side change to native sign-in would break; three inline copies meant three
# edits, and one copy had already drifted into hiding the server's reason for a failed exchange.

DISTRICT_REDIRECT="districtai://auth"

FAILURES=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '  \033[33mNOTE\033[0m %s\n' "$1"; }

# warm_routes PATH...
#
# ⚠️ Next dev compiles a route on FIRST hit (measured: 6.4s for /auth/native). The PKCE code has a
# 120s TTL, so an unwarmed run can spend the budget on compilation and fail with an opaque
# "refused (unknown)" that looks like a PKCE bug. So the authorize page, every route the caller
# names and the token endpoint are each hit once, and their answers ignored, before any code is
# minted.
warm_routes() {
  local p
  for p in "/auth/native?code_challenge=x&state=y&redirect_uri=z" "$@"; do
    curl -sS -o /dev/null --max-time 120 -H 'Cookie: distronode_session=local-dev-mock' "$BASE$p" 2>/dev/null
  done
  curl -sS -o /dev/null --max-time 120 -X POST "$BASE/api/auth/native/token" \
    -H 'Content-Type: application/json' -d '{}' 2>/dev/null
  echo "  done"
}

# mint_access_token DEVICE_ID DEVICE_NAME
#
# Sets ACCESS to a bearer token for the dev session's user, or records a failure and EXITS: every
# check after this needs the token, so continuing would only print a page of misleading failures.
#
# ⛔ THE SERVER'S ANSWER IS PRINTED ON FAILURE. The exchange is captured before it is parsed, so a
# refusal shows the server's own reason (expired code, verifier mismatch) rather than a bare "no
# access token".
mint_access_token() {
  local device_id="$1" device_name="$2" verifier challenge state auth_html code exchange
  verifier=$(head -c 32 /dev/urandom | basenc --base64url | tr -d '=')
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | basenc --base64url | tr -d '=')
  state=$(head -c 16 /dev/urandom | basenc --base64url | tr -d '=')
  auth_html=$(curl -sS --max-time 30 -H 'Cookie: distronode_session=local-dev-mock' \
    "$BASE/auth/native?code_challenge=$challenge&state=$state&redirect_uri=$(printf '%s' "$DISTRICT_REDIRECT" | sed 's|:|%3A|g; s|/|%2F|g')")
  code=$(printf '%s' "$auth_html" | grep -oE 'districtai://auth\?code=[A-Za-z0-9_-]+' | head -1 | sed 's/.*code=//')
  [ -n "$code" ] || { fail "no authorization code issued"; exit 1; }
  exchange=$(curl -sS --max-time 30 -X POST "$BASE/api/auth/native/token" \
    -H 'Content-Type: application/json' \
    -d "{\"code\":\"$code\",\"codeVerifier\":\"$verifier\",\"redirectUri\":\"$DISTRICT_REDIRECT\",\"deviceId\":\"$device_id\",\"deviceName\":\"$device_name\",\"platform\":\"android\"}")
  ACCESS=$(printf '%s' "$exchange" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
  if [ -z "$ACCESS" ]; then
    fail "token exchange produced no access token"
    echo "       server said: $exchange"
    exit 1
  fi
  pass "minted a real bearer token"
}
