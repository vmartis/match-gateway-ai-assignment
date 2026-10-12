#!/usr/bin/env bash
# OWASP ZAP API scan (passive + active) of the running gateway, authenticated with a dev token of a dedicated tenant.
# Usage: ./scripts/owasp-scan.sh            (stack must be running: docker compose up --build -d)
# Output: findings (WARN and above) on stdout; full reports in zap-report/ (git-ignored). Exit 1 if ZAP reports a FAIL.
set -euo pipefail
cd "$(dirname "$0")/.."

# Official ZAP image, tag + multi-arch index digest (docker buildx imagetools inspect ghcr.io/zaproxy/zaproxy:stable).
ZAP_IMAGE="ghcr.io/zaproxy/zaproxy:stable@sha256:7aaa659b0d43078febd82e29bad112285c370727e86ab8340444220e17d9f0d2"

gateway_id="$(docker compose ps -q gateway)"
if [[ -z "$gateway_id" ]]; then
  echo "gateway is not running - start the stack with: docker compose up --build -d" >&2
  exit 1
fi
network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{"\n"}}{{end}}' "$gateway_id" | head -n1)"

# A dedicated tenant, so the active scan never touches other tenants' data. The token reaches ZAP through the environment
# (-e NAME without a value: not on the command line) and is never printed; ZAP adds it to every request it sends.
ZAP_AUTH_HEADER_VALUE="Bearer $(./scripts/token.sh zap-scan zap "enroll verify admin" 2>/dev/null)"
export ZAP_AUTH_HEADER_VALUE

# No host directory is shared with the container: the API description goes in on stdin, the reports come out by docker cp.
name="zap-scan-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
echo "running ZAP API scan against http://gateway:8080 on network $network ..."
set +e
docker run -i --name "$name" --network "$network" \
  -e ZAP_AUTH_HEADER_VALUE -e ZAP_AUTH_HEADER_SITE=gateway \
  --entrypoint sh "$ZAP_IMAGE" -c '
    mkdir -p /zap/wrk && cat > /zap/wrk/openapi.yaml &&
    zap-api-scan.py -t /zap/wrk/openapi.yaml -f openapi -O http://gateway:8080 \
      -r report.html -J report.json -w report.md -l WARN -s' < scripts/zap/openapi.yaml
status=$?
set -e
mkdir -p zap-report
for f in report.html report.json report.md; do
  rm -f "zap-report/$f"
  docker cp "$name:/zap/wrk/$f" zap-report/ >/dev/null 2>&1 || true
done

# zap-api-scan exit codes: 0 pass, 1 at least one FAIL, 2 at least one WARN, 3 error.
case $status in
  0) echo "ZAP: PASS (no warnings)"; exit 0 ;;
  2) echo "ZAP: WARN only - see zap-report/report.html"; exit 0 ;;
  1) echo "ZAP: FAIL - see zap-report/report.html" >&2; exit 1 ;;
  *) echo "ZAP: error (exit $status)" >&2; exit "$status" ;;
esac
