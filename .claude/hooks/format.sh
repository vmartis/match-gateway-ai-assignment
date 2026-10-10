#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): best-effort ktlint format of the touched Kotlin file. Never blocks.

file=$(jq -r '.tool_input.file_path // empty' 2>/dev/null) || exit 0
[[ -f "$file" ]] || exit 0

case "$file" in
  *.kt | *.kts)
    # ktlint CLI on the single file (fast). The Gradle plugin (./gradlew ktlintFormat) stays the source of
    # truth in verify.sh; both read .editorconfig.
    if command -v ktlint >/dev/null 2>&1; then
      (cd "${CLAUDE_PROJECT_DIR:-.}" && ktlint --format --relative "$file") >/dev/null 2>&1 || true
    fi
    ;;
esac

exit 0
