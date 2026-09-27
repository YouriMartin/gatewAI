#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): the project is on Jackson 3 (`tools.jackson.*`).
# Only the annotations stay in `com.fasterxml.jackson.annotation`; anything else under
# `com.fasterxml.jackson` is a Jackson 2 import. Exit 2 = feed stderr back to the agent.
set -euo pipefail

file=$(jq -r '.tool_input.file_path // ""')
[[ $file == *.java && -f $file ]] || exit 0

if hits=$(grep -nP 'com\.fasterxml\.jackson\.(?!annotation\b)' "$file"); then
  echo "Jackson 2 reference in $file — use tools.jackson.* (Jackson 3):" >&2
  echo "$hits" >&2
  exit 2
fi
exit 0
