#!/usr/bin/env bash
# Full local quality gate. Must end with "VERIFY OK"; any failure exits non-zero.
set -euo pipefail

cd "$(dirname "$0")/.."

section() { printf '\n=== %s ===\n' "$1"; }

# A missing scanner never passes silently: it is listed in the final line, and fails the gate in CI.
skipped=()
skip() {
  echo "WARNING: skipped – $1 not installed"
  skipped+=("$1")
}

section "backend: lint + build + tests"
./gradlew --no-daemon --console=plain ktlintCheck build

if [[ -d web ]]; then
  section "frontend: lint + typecheck + test + build"
  (cd web && npm ci && npm run lint && npm run typecheck && npm test -- --run && npm run build)
else
  section "frontend: skipped (no web/)"
fi

section "secrets: gitleaks"
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks dir . --no-banner --redact --exit-code 1
  if git rev-parse --verify HEAD >/dev/null 2>&1; then
    gitleaks git . --no-banner --redact --exit-code 1
  fi
else
  skip gitleaks
fi

section "dependencies: osv-scanner"
if command -v osv-scanner >/dev/null 2>&1; then
  osv-scanner scan source -r .
else
  skip osv-scanner
fi

if ((${#skipped[@]} > 0)); then
  if [[ -n "${CI:-}" ]]; then
    printf '\nVERIFY FAILED: security scans missing in CI: %s\n' "${skipped[*]}"
    exit 1
  fi
  printf '\nVERIFY OK (WARNING: scans skipped: %s)\n' "${skipped[*]}"
else
  printf '\nVERIFY OK\n'
fi
