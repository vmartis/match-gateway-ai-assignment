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
check LIVENESS 200 "$BASE_URL/actuator/health/liveness"
check READINESS 200 "$BASE_URL/actuator/health/readiness"

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
# AC-9 / AC-10 / AC-11 / AC-12 / AC-42 / AC-43: enrolment (template = unit vector e0, generated with python3 stdlib)
TEMPLATE=$(python3 -c 'import base64,struct; print(base64.b64encode(struct.pack("<128f",1.0,*([0.0]*127))).decode())')
SUBJ="acc-$(date +%s)"
check AC-9 201 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: application/json' -d "{\"template\":\"$TEMPLATE\"}" "$BASE_URL/v1/subjects/$SUBJ/enrollment"
assert_body AC-9 '"version":1'
check AC-10 201 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: application/json' -d "{\"template\":\"$TEMPLATE\"}" "$BASE_URL/v1/subjects/$SUBJ/enrollment"
assert_body AC-10 '"version":2'
# hostile inputs on enrolment
check AC-11-short 400 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: application/json' -d '{"template":"AAAA"}' "$BASE_URL/v1/subjects/$SUBJ/enrollment"
check AC-12-id 400 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: application/json' -d "{\"template\":\"$TEMPLATE\"}" "$BASE_URL/v1/subjects/a%20b/enrollment"
check AC-42-size 413 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: application/json' -d "{\"template\":\"$(printf 'A%.0s' $(seq 1 9000))\"}" "$BASE_URL/v1/subjects/$SUBJ/enrollment"
check AC-43-type 415 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H 'Content-Type: text/plain' -d 'x' "$BASE_URL/v1/subjects/$SUBJ/enrollment"
check AC-7-enrol 403 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -d "{\"template\":\"$TEMPLATE\"}" "$BASE_URL/v1/subjects/$SUBJ/enrollment"

# AC-1: <Given/When/Then from SPEC>
# check AC-1 200 -X POST "$BASE_URL/api/..." -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{...}'
# assert_body AC-1 '"id":'

# AC-14 / AC-17 / AC-30..33: submit and read verifications (decisions come from the matcher in step 5)
TOKEN_B=$(./scripts/token.sh bank-beta acceptance "verify" 2>/dev/null)
check AC-14 202 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -H "Idempotency-Key: acc-$SUBJ" -d "{\"subjectId\":\"$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
VID=$(sed -n 's/.*"verificationId":"\([0-9a-f-]*\)".*/\1/p' <<<"$BODY")
check AC-31 200 -H "Authorization: Bearer $TOKEN_VERIFY" "$BASE_URL/v1/verifications/$VID"
assert_body AC-31 '"subjectId":"'"$SUBJ"'"'
check AC-33-other-tenant 404 -H "Authorization: Bearer $TOKEN_B" "$BASE_URL/v1/verifications/$VID"
check AC-33-not-uuid 404 -H "Authorization: Bearer $TOKEN_VERIFY" "$BASE_URL/v1/verifications/not-a-uuid"
check AC-15-key 400 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -d "{\"subjectId\":\"$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
check AC-17-unknown 202 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -H "Idempotency-Key: acc-ghost-$SUBJ" -d "{\"subjectId\":\"ghost-$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"

# AC-24 / AC-17 / AC-26 end to end (checkpoint 1): the 3 matcher replicas decide what the gateway accepted
poll_final() { # id token -> echoes the final status (or TIMEOUT)
  for _ in $(seq 1 50); do
    st=$(curl -sS -H "Authorization: Bearer $2" "$BASE_URL/v1/verifications/$1" | sed -n 's/.*"status":"\([A-Z_]*\)".*/\1/p')
    [[ -n $st && $st != PENDING ]] && { echo "$st"; return; }
    sleep 0.2
  done
  echo TIMEOUT
}
FINAL=$(poll_final "$VID" "$TOKEN_VERIFY")
[[ $FINAL == MATCH ]] && { pass=$((pass + 1)); echo "PASS  AC-24      enrolled subject, same template -> $FINAL"; } || { fail=$((fail + 1)); echo "FAIL  AC-24      expected MATCH got $FINAL"; }
GHOST=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H 'Content-Type: application/json' -H "Idempotency-Key: acc-ghost2-$SUBJ" -d "{\"subjectId\":\"ghost2-$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications" | sed -n 's/.*"verificationId":"\([0-9a-f-]*\)".*/\1/p')
FINAL=$(poll_final "$GHOST" "$TOKEN_VERIFY")
check AC-17-final 200 -H "Authorization: Bearer $TOKEN_VERIFY" "$BASE_URL/v1/verifications/$GHOST"
assert_body AC-17-final '"status":"FAILED".*"reason":"SUBJECT_NOT_ENROLLED"|"reason":"SUBJECT_NOT_ENROLLED".*"status":"FAILED"'

# --- hostile inputs (>= 3 per endpoint) ----------------------------------------
# check HOSTILE-1 400 -X POST "$BASE_URL/api/..." -H 'Content-Type: application/json' -d '{"name":"'"$(printf 'a%.0s' {1..10000})"'"}'

printf '\n%d passed, %d failed\n' "$pass" "$fail"
((fail == 0))
