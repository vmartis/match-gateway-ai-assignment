#!/usr/bin/env bash
# Mint a dev JWT (D-4, ADR-004) with the private key from the issuer-keys volume. The stack must have started once
# (the keygen service creates the keys).
# Usage: ./scripts/token.sh <tenant_id> <sub> "<scopes: enroll verify admin>" [ttl-seconds, max 900]
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -lt 3 ]]; then
  echo "usage: $0 <tenant_id> <sub> \"<enroll verify admin>\" [ttl-seconds]" >&2
  exit 2
fi
args=(mint --tenant "$1" --sub "$2" --scope "$3")
[[ -n "${4:-}" ]] && args+=(--ttl "$4")
exec docker compose run --rm --no-deps -T devtoken "${args[@]}"
