#!/usr/bin/env bash
# Black-box acceptance checks against a running app (docker compose up --build -d first).
# Usage: BASE_URL=http://localhost:8080 ./scripts/acceptance.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
pass=0 fail=0

# check <id> <expected-status> <curl args...>  – prints one result row; body kept in $BODY for extra assertions.
check() {
  local id=$1 expected=$2
  shift 2
  local out status
  out=$(curl -sS --max-time 10 -w $'\n%{http_code}' "$@") || out=$'\n000'
  status=${out##*$'\n'}
  BODY=${out%$'\n'*}
  if [[ "$status" == "$expected" ]]; then
    pass=$((pass + 1)); printf 'PASS  %-10s expected %s got %s\n' "$id" "$expected" "$status"
  else
    fail=$((fail + 1)); printf 'FAIL  %-10s expected %s got %s  body: %.200s\n' "$id" "$expected" "$status" "$BODY"
  fi
}

# assert_body <id> <regex>  – checks the last response body.
assert_body() {
  if grep -Eq -- "$2" <<<"$BODY"; then
    pass=$((pass + 1)); printf 'PASS  %-10s body matches /%s/\n' "$1" "$2"
  else
    fail=$((fail + 1)); printf 'FAIL  %-10s body does not match /%s/: %.200s\n' "$1" "$2" "$BODY"
  fi
}

# --- health -------------------------------------------------------------------
check HEALTH 200 "$BASE_URL/actuator/health"
assert_body HEALTH '"status":"UP"'
check ACTUATOR 401 "$BASE_URL/actuator/env"   # only health is exposed; everything else denied

# --- acceptance criteria (one block per AC from docs/SPEC.md) ----------------
# Tokens come from the dev tool (AC-5); never printed.
TOKEN_ALL=$(./scripts/token.sh bank-alpha acceptance "enroll verify admin" 2>/dev/null)
TOKEN_VERIFY=$(./scripts/token.sh bank-alpha acceptance "verify" 2>/dev/null)
TOKEN_ENROLL=$(./scripts/token.sh bank-alpha acceptance "enroll" 2>/dev/null)

# AC-6: invalid tokens are rejected; a valid token passes authentication (unknown path -> 404)
check AC-6-none 401 "$BASE_URL/v1/anything"
# Tamper: replace the last signature character with a different one (never a no-op).
last=${TOKEN_ALL: -1}; [[ $last == x ]] && swap=y || swap=x
UNKNOWN_ID=00000000-0000-4000-8000-000000000000
check AC-6-tampered 401 -H "Authorization: Bearer ${TOKEN_ALL%?}$swap" "$BASE_URL/v1/verifications/$UNKNOWN_ID"
check AC-6-valid 404 -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/verifications/$UNKNOWN_ID"
check DENY-DEFAULT 403 -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/anything"
check API5-HEAD 403 -I -H "Authorization: Bearer $TOKEN_ENROLL" "$BASE_URL/v1/verifications/$UNKNOWN_ID"
# AC-7 / AC-8: scope rules in the filter chain
check AC-7 403 -X PUT -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -d '{"threshold":0.7}' "$BASE_URL/v1/tenant/threshold"

# AC-1: <Given/When/Then from SPEC>
# check AC-1 200 -X POST "$BASE_URL/api/..." -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{...}'
# assert_body AC-1 '"id":'

# --- hostile inputs (>= 3 per endpoint) ----------------------------------------
# check HOSTILE-1 400 -X POST "$BASE_URL/api/..." -H 'Content-Type: application/json' -d '{"name":"'"$(printf 'a%.0s' {1..10000})"'"}'

printf '\n%d passed, %d failed\n' "$pass" "$fail"
((fail == 0))
