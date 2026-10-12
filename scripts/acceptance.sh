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

# --- AC-1 / ADR-009: RabbitMQ broker, quorum queues, 3 matcher consumers ---------
QUEUES=$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name type 2>/dev/null)
BODY=$QUEUES
assert_body AC-1-queue-jobs $'^matchgw\\.verifications[[:space:]]+quorum'
assert_body AC-1-queue-dlq '^matchgw\.verifications\.dead[[:space:]]+quorum'
CONSUMERS=$(docker compose exec -T rabbitmq rabbitmqctl -q list_consumers queue_name 2>/dev/null | grep -c '^matchgw\.verifications[[:space:]]*$')
if [[ ${CONSUMERS:-0} -ge 3 ]]; then
  pass=$((pass + 1)); echo "PASS  AC-1-consumers $CONSUMERS consumers on matchgw.verifications"
else
  fail=$((fail + 1)); echo "FAIL  AC-1-consumers expected >= 3 consumers on matchgw.verifications, got ${CONSUMERS:-0}"
fi
matcher_readiness() { # container-id -> HTTP response text (bare JRE image: bash /dev/tcp)
  docker exec -i "$1" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; cat <&3'
}
READY_COUNT=0 MATCHER_COUNT=0
for cid in $(docker compose ps -q matcher); do
  MATCHER_COUNT=$((MATCHER_COUNT + 1))
  matcher_readiness "$cid" 2>/dev/null | grep -q '"status":"UP"' && READY_COUNT=$((READY_COUNT + 1))
done
if [[ $MATCHER_COUNT -ge 3 && $READY_COUNT -eq $MATCHER_COUNT ]]; then
  pass=$((pass + 1)); echo "PASS  AC-1-ready    $READY_COUNT/$MATCHER_COUNT matcher replicas ready"
else
  fail=$((fail + 1)); echo "FAIL  AC-1-ready    $READY_COUNT/$MATCHER_COUNT matcher replicas ready (expected >= 3, all UP)"
fi

# --- steps 7-9: threshold, erasure, idempotency, metrics -----------------------
RUN="$(date +%s)$RANDOM"
JSON='Content-Type: application/json'

# AC-45 / AC-46 / AC-7: tenant threshold. Restored to the default 0.5 on any exit, so an aborted run cannot leave
# bank-alpha at 0.7 (which would skew eval/eval.py).
trap 'curl -sS -o /dev/null --max-time 10 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d "{\"threshold\":0.5}" "$BASE_URL/v1/tenant/threshold" || true' EXIT
check AC-45 200 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":0.7}' "$BASE_URL/v1/tenant/threshold"
assert_body AC-45 '"threshold":0\.7'
check AC-46-low 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":-0.01}' "$BASE_URL/v1/tenant/threshold"
check AC-46-high 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":1.01}' "$BASE_URL/v1/tenant/threshold"
check AC-46-string 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":"abc"}' "$BASE_URL/v1/tenant/threshold"
check AC-46-missing 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{}' "$BASE_URL/v1/tenant/threshold"
check AC-7-threshold 403 -X PUT -H "Authorization: Bearer $TOKEN_ENROLL" -H "$JSON" -d '{"threshold":0.7}' "$BASE_URL/v1/tenant/threshold"
check AC-45-restore 200 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":0.5}' "$BASE_URL/v1/tenant/threshold"
assert_body AC-45-restore '"threshold":0\.5'

# AC-47 / AC-48: erase a throwaway enrolled subject (D-26: malformed id -> 400)
ERASE="acc-erase-$RUN"
check AC-47-enrol 201 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d "{\"template\":\"$TEMPLATE\"}" "$BASE_URL/v1/subjects/$ERASE/enrollment"
check AC-47 204 -X DELETE -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/subjects/$ERASE"
check AC-48 404 -X DELETE -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/subjects/$ERASE"
check AC-48-malformed 400 -X DELETE -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/subjects/a%20b"
check AC-47-scope 403 -X DELETE -H "Authorization: Bearer $TOKEN_VERIFY" "$BASE_URL/v1/subjects/$SUBJ"

# AC-18 / AC-19 / AC-20: idempotent submit
IDEM_KEY="acc-idem-$RUN"
IDEM_BODY="{\"subjectId\":\"$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}"
check AC-18-first 202 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "Idempotency-Key: $IDEM_KEY" -d "$IDEM_BODY" "$BASE_URL/v1/verifications"
IDEM_ID=$(sed -n 's/.*"verificationId":"\([0-9a-f-]*\)".*/\1/p' <<<"$BODY")
out=$(curl -sS --max-time 10 -w $'\n%{http_code}' -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "Idempotency-Key: $IDEM_KEY" -d "$IDEM_BODY" "$BASE_URL/v1/verifications")
BODY=${out%$'\n'*}; replay_status=${out##*$'\n'}
replay_id=$(sed -n 's/.*"verificationId":"\([0-9a-f-]*\)".*/\1/p' <<<"$BODY")
if [[ -n $IDEM_ID && $replay_id == "$IDEM_ID" && ( $replay_status == 200 || $replay_status == 202 ) ]]; then
  pass=$((pass + 1)); echo "PASS  AC-18      replay returned the same verificationId ($replay_status)"
else
  fail=$((fail + 1)); echo "FAIL  AC-18      replay status $replay_status, id '$replay_id' vs '$IDEM_ID'"
fi
check AC-19 422 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "Idempotency-Key: $IDEM_KEY" -d "{\"subjectId\":\"other-$SUBJ\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
check AC-20 202 -X POST -H "Authorization: Bearer $TOKEN_B" -H "$JSON" -H "Idempotency-Key: $IDEM_KEY" -d "$IDEM_BODY" "$BASE_URL/v1/verifications"
other_id=$(sed -n 's/.*"verificationId":"\([0-9a-f-]*\)".*/\1/p' <<<"$BODY")
if [[ -n $other_id && $other_id != "$IDEM_ID" ]]; then
  pass=$((pass + 1)); echo "PASS  AC-20      tenant B got its own verificationId"
else
  fail=$((fail + 1)); echo "FAIL  AC-20      tenant B id '$other_id' vs tenant A '$IDEM_ID'"
fi

# AC-51: metrics live in the matcher on 8081 (unpublished). The image is a bare JRE (no curl/wget), so use bash /dev/tcp.
# Each replica has its own registry, so scrape all of them and look at the union.
scrape_matcher() { # container-id -> HTTP response text
  docker exec -i "$1" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/prometheus HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; cat <&3'
}
sleep 6   # let the 5 s gauge refresh and the replicas decide the verifications above
METRICS=""
for cid in $(docker compose ps -q matcher); do METRICS+="$(scrape_matcher "$cid" 2>/dev/null)"$'\n'; done
BODY=$METRICS
assert_body AC-51-counter '^matchgw_verifications_decided_total\{result="(MATCH|NO_MATCH|FAILED)"\}'
assert_body AC-51-gauge '^matchgw_queue_depth '
assert_body AC-51-timer '^matchgw_matcher_processing_seconds_count'
assert_body AC-51-histogram '^matchgw_verification_latency_seconds_bucket'
if grep -Eq '^matchgw_[^ ]*[{,](tenant|tenant_id|subject|subject_id)=' <<<"$METRICS"; then
  fail=$((fail + 1)); echo "FAIL  AC-51-labels no tenant/subject label allowed on matchgw_ series"
else
  pass=$((pass + 1)); echo "PASS  AC-51-labels no tenant/subject label on matchgw_ series"
fi
# AC-52: nothing but health on the public port
check AC-52 401 "$BASE_URL/actuator/prometheus"

# --- hostile inputs (>= 3 per endpoint; the full sets are in the ITs) -----------
HKEY="Idempotency-Key: acc-hostile-$RUN"
check HOSTILE-verify-short 400 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "$HKEY-1" -d "{\"subjectId\":\"$SUBJ\",\"probeTemplate\":\"AAAA\"}" "$BASE_URL/v1/verifications"
check HOSTILE-verify-id 400 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "$HKEY-2" -d "{\"subjectId\":\"a b\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
check HOSTILE-verify-type 400 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "$HKEY-3" -d "{\"subjectId\":123,\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
check HOSTILE-verify-size 413 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "$HKEY-4" -d "{\"subjectId\":\"$SUBJ\",\"probeTemplate\":\"$(printf 'A%.0s' $(seq 1 9000))\"}" "$BASE_URL/v1/verifications"
check HOSTILE-verify-dupkey 400 -X POST -H "Authorization: Bearer $TOKEN_VERIFY" -H "$JSON" -H "$HKEY-5" -d "{\"subjectId\":\"$SUBJ\",\"subjectId\":\"x\",\"probeTemplate\":\"$TEMPLATE\"}" "$BASE_URL/v1/verifications"
check HOSTILE-enrol-json 400 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"template":' "$BASE_URL/v1/subjects/$SUBJ/enrollment"
check HOSTILE-enrol-null 400 -X POST -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"template":null}' "$BASE_URL/v1/subjects/$SUBJ/enrollment"
check HOSTILE-threshold-nan 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":NaN}' "$BASE_URL/v1/tenant/threshold"
check HOSTILE-threshold-null 400 -X PUT -H "Authorization: Bearer $TOKEN_ALL" -H "$JSON" -d '{"threshold":null}' "$BASE_URL/v1/tenant/threshold"
check HOSTILE-get-traversal 400 -H "Authorization: Bearer $TOKEN_VERIFY" "$BASE_URL/v1/verifications/..%2F..%2Fetc"
check HOSTILE-delete-id 400 -X DELETE -H "Authorization: Bearer $TOKEN_ALL" "$BASE_URL/v1/subjects/$(printf 'a%.0s' $(seq 1 65))"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
((fail == 0))
