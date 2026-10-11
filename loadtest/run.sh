#!/usr/bin/env bash
# Run the k6 verification load test (AC-57) against the running compose stack.
# Usage: ./loadtest/run.sh [--smoke]     (full: 300/s for 60 s; smoke: 10/s for 10 s)
# Prerequisites: `docker compose up --build` is running; python3 (only to generate the dataset if missing).
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"

smoke=0
case "${1:-}" in
  --smoke) smoke=1 ;;
  "") ;;
  *) echo "usage: $0 [--smoke]" >&2; exit 2 ;;
esac

# Pinned k6 image (tag + multi-arch index digest, resolved with `docker buildx imagetools inspect grafana/k6:1.8.0`).
K6_IMAGE="grafana/k6:1.8.0@sha256:b992f241070f3f3a7d78096fa6020db1edcda49297ee8ed9eb0ab847ef3dcb32"

# Dataset (gitignored): generate deterministically if missing.
if [[ ! -s dataset/enrollments.jsonl || ! -s dataset/pairs.jsonl ]]; then
  echo "generating dataset ..."
  (cd dataset && python3 gen_dataset.py)
fi

# Compose network of the running gateway (normally match-gateway_default).
gateway_id="$(docker compose ps -q gateway)"
if [[ -z "$gateway_id" ]]; then
  echo "gateway is not running - start the stack with: docker compose up --build" >&2
  exit 1
fi
network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$gateway_id" | head -n1)"

# One token per tenant (15 min TTL, enough for a ~2 min run). Values are never echoed; they reach the container
# through the environment (`-e NAME` without a value), not the command line.
TOKEN_BANK_ALPHA="$(./scripts/token.sh bank-alpha loadtest "enroll verify")"
TOKEN_BANK_BETA="$(./scripts/token.sh bank-beta loadtest "enroll verify")"
TOKEN_GOV_GAMMA="$(./scripts/token.sh gov-gamma loadtest "enroll verify")"
export TOKEN_BANK_ALPHA TOKEN_BANK_BETA TOKEN_GOV_GAMMA

# Server-side p95 (D-13): scrape every matcher replica before and after; the histogram is cumulative per replica.
scrape_matchers() { # -> concatenated Prometheus text of all replicas
  local cid
  for cid in $(docker compose ps -q matcher); do
    docker exec -i "$cid" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/prometheus HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; cat <&3' || true
  done
}
queue_depth() { scrape_matchers | awk '/^matchgw_queue_depth /{ if ($2 > max) max = $2 } END { print max + 0 }'; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
scrape_matchers >"$tmp/before.txt"

mkdir -p loadtest/results
echo "running k6 ($([[ $smoke == 1 ]] && echo smoke || echo full)) on network $network ..."
docker run --rm \
  --network "$network" \
  --user "$(id -u):$(id -g)" \
  -v "$root/loadtest:/scripts:ro" \
  -v "$root/dataset:/dataset:ro" \
  -v "$root/loadtest/results:/results" \
  -e TOKEN_BANK_ALPHA -e TOKEN_BANK_BETA -e TOKEN_GOV_GAMMA \
  -e SMOKE="$smoke" \
  -e BASE_URL="http://gateway:8080" \
  "$K6_IMAGE" run /scripts/verify.js && k6_exit=0 || k6_exit=$?

# Let the backlog drain (gauge refreshes every 5 s; at most 60 s), then take the window's server-side p95.
for _ in $(seq 1 12); do
  [[ "$(queue_depth)" == 0 ]] && break
  sleep 5
done
sleep 6 # one more gauge/decision cycle so the last decisions are in the histogram
scrape_matchers >"$tmp/after.txt"
python3 loadtest/server_p95.py "$tmp/before.txt" "$tmp/after.txt"
exit "$k6_exit"
