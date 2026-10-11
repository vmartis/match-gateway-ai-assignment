#!/usr/bin/env bash
# PreToolUse(Edit|Write|MultiEdit): protect sensitive files, tests and secrets. Exit 2 + stderr = block.
set -euo pipefail

input=$(cat)
file=$(jq -r '.tool_input.file_path // empty' <<<"$input")
[[ -z "$file" ]] && exit 0

# New text being written: Write.content, Edit.new_string, MultiEdit.edits[].new_string
content=$(jq -r '[.tool_input.content, .tool_input.new_string, (.tool_input.edits // [] | .[].new_string)]
                 | map(select(. != null)) | join("\n")' <<<"$input")

project="${CLAUDE_PROJECT_DIR:-$PWD}"
rel="${file#"$project"/}"
base=$(basename -- "$file")

block() {
  echo "BLOCKED by guard-files.sh: $1" >&2
  echo "File: $rel" >&2
  exit 2
}

has() { grep -Eq -- "$1" <<<"$content"; }
has_i() { grep -Eiq -- "$1" <<<"$content"; }

# 1. Protected paths
[[ "$base" == .env* ]] && block ".env* files are off-limits. Document variables in docs/ or the README instead."
[[ "/$rel/" == */secrets/* ]] && block "secrets/ is off-limits."
[[ "$rel" == "docs/SPEC.md" ]] && block "docs/SPEC.md is human-owned. Propose the change instead (CLAUDE.md §5)."

# 2. Disabled/skipped tests
is_test=0
if [[ "/$rel" == */src/test/* || "/$rel" == */src/*[Tt]est*/* || "/$rel" == */__tests__/* \
  || "$base" =~ (Test|Tests|IT|Spec)\.kts?$ || "$base" =~ \.(test|spec)\.[cm]?[jt]sx?$ ]]; then
  is_test=1
fi
if ((is_test)) && has "@Disabled|@Ignore([^a-zA-Z]|$)|\.skip\(|(^|[^a-zA-Z_])x(it|describe|test)\("; then
  block "disabling/skipping tests (@Disabled, @Ignore, .skip, xit) is not allowed (CLAUDE.md §5)."
fi

# 3. Hardcoded secrets
has "(AKIA|ASIA)[0-9A-Z]{16}" && block "looks like an AWS access key."
has "-----BEGIN ([A-Z]+ )*PRIVATE KEY-----" && block "looks like a private key. Load keys from env/mounted file."

key='(password|passwd|pwd|secret|client[_-]?secret|api[_-]?key|private[_-]?key)'
if ((!is_test)); then
  # Quoted literal: password = "x", "password": "x", password: 'x' (env refs like "${X}" are allowed)
  has_i "${key}[\"']?[[:space:]]*(:|=|:=)[[:space:]]*[\"'][^\"'\$]{3,}[\"']" \
    && block "looks like a hardcoded secret (password/secret/api key literal). Use env/config."
  # Unquoted value in config files: password: hunter2 / password=hunter2 / ${DB_PASSWORD:hunter2}.
  # An empty env default (`${DB_PASSWORD:}`) is allowed: `}` cannot start a value. `{` is a key boundary,
  # so `${PASSWORD:hunter2}` is caught too.
  if [[ "$base" =~ \.(ya?ml|properties|conf|ini|toml)$ ]]; then
    has_i "(^|[[:space:]_.{-])${key}[[:space:]]*(:|=)[[:space:]]*[^[:space:]\$\"'{}#][^[:space:]]*" \
      && block "looks like a hardcoded secret in config. Use \${ENV_VAR}."
  fi
fi

exit 0
