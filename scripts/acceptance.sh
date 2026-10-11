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
# AC-1: <Given/When/Then from SPEC>
# check AC-1 200 -X POST "$BASE_URL/api/..." -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{...}'
# assert_body AC-1 '"id":'

# --- hostile inputs (>= 3 per endpoint) ----------------------------------------
# check HOSTILE-1 400 -X POST "$BASE_URL/api/..." -H 'Content-Type: application/json' -d '{"name":"'"$(printf 'a%.0s' {1..10000})"'"}'

printf '\n%d passed, %d failed\n' "$pass" "$fail"
((fail == 0))
