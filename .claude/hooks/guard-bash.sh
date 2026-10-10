#!/usr/bin/env bash
# PreToolUse(Bash): block dangerous shell commands. Exit 2 + stderr = block.
set -euo pipefail

cmd=$(jq -r '.tool_input.command // empty')
[[ -z "$cmd" ]] && exit 0

block() {
  echo "BLOCKED by guard-bash.sh: $1" >&2
  echo "Command: $cmd" >&2
  exit 2
}

matches() { grep -Eq -- "$1" <<<"$cmd"; }

# Start of a command: line start or after ; & | ( or whitespace.
S='(^|[;&|(`[:space:]])'

# 1. Destructive rm: recursive+force in any spelling, or recursive on /, ~, $HOME, ., .., *
while IFS= read -r seg; do
  [[ -z "$seg" ]] && continue
  recursive=0 force=0
  grep -Eq -- "[[:space:]](-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)([[:space:]]|$)" <<<"$seg" && recursive=1
  grep -Eq -- "[[:space:]](-[a-zA-Z]*f[a-zA-Z]*|--force)([[:space:]]|$)" <<<"$seg" && force=1
  if ((recursive && force)); then
    block "destructive rm (recursive + force). Delete specific paths without -f instead."
  fi
  if ((recursive)) && grep -Eq -- "[[:space:]](/|~|~/|\\\$HOME/?|\.|\./|\.\.|\.\./|\*|/\*)([[:space:]]|$)" <<<"$seg"; then
    block "recursive rm on /, ~, \$HOME, ., .. or *."
  fi
done < <(grep -oE -- "${S}rm[[:space:]][^;&|]*" <<<"$cmd" || true)

# 2. Piping into a shell (curl ... | sh), or process-substitution of downloads
if matches "\|[[:space:]]*(sudo[[:space:]]+)?(env[[:space:]]+)?(/usr)?(/bin/)?(ba|z|da|k)?sh([[:space:]]|$)" \
  || matches "(ba|z)?sh[[:space:]]+<\([[:space:]]*(curl|wget)"; then
  block "piping into a shell. Download, inspect, then run explicitly."
fi

# 3. Force push (--force, --force-with-lease, -f, +refspec)
if matches "${S}git[[:space:]]+(.*[[:space:]])?push([[:space:]]|$)"; then
  if matches "push.*[[:space:]](--force(-with-lease)?(=[^[:space:]]*)?|-[a-zA-Z]*f[a-zA-Z]*)([[:space:]]|$)" \
    || matches "push.*[[:space:]]\+[^[:space:]]+"; then
    block "force push."
  fi
fi

# 4. Skipping git hooks
if matches "--no-verify"; then
  block "--no-verify skips git hooks."
fi

# 5. Skipping tests (Gradle -x/--exclude-task *test*, Maven skipTests / maven.test.skip)
if matches "(^|[[:space:]])(-x|--exclude-task)([[:space:]]+|=)[^[:space:]]*[tT]est" \
  || matches "skipTests|maven\.test\.skip"; then
  block "skipping tests is not allowed (CLAUDE.md §5)."
fi

# 6. World-writable permissions
if matches "${S}chmod[[:space:]]+(-[a-zA-Z]+[[:space:]]+)*0?777([[:space:]]|$)" \
  || matches "${S}chmod[[:space:]]+(-[a-zA-Z]+[[:space:]]+)*(a|ugo)\+rwx"; then
  block "chmod 777 / a+rwx."
fi

# Non-blocking: package install reminder
if matches "${S}(npm[[:space:]]+(install|i|add)|pnpm[[:space:]]+add|yarn[[:space:]]+add)([[:space:]]|$)"; then
  jq -n --arg msg "Reminder: before installing, verify the package exists and the name is exact (npm view <pkg>); never guess package names (CLAUDE.md §4)." \
    '{systemMessage: $msg, hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $msg}}'
fi

exit 0
