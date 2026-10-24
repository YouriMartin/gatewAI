#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version, for the release notes (v4 C.1).
#
#   scripts/changelog-excerpt.sh 0.1.0         # the "## [0.1.0] - <date>" section
#   scripts/changelog-excerpt.sh 0.1.0-rc.1    # its own section, else [Unreleased]
#
# A final version must have its own section: the release workflow fails here,
# before anything is built, rather than publish a release that says nothing. A
# pre-release (a version with a `-` suffix) may go out on the [Unreleased]
# section, since that is what it is a candidate of. An empty section fails too.
set -euo pipefail

VERSION="${1:?usage: $0 <version>}"
CHANGELOG="${CHANGELOG:-$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md}"

section() { # $1 = the bracketed name; prints the body up to the next "## ["
  awk -v name="$1" '
    index($0, "## [" name "]") == 1 { found = 1; next }
    found && /^## \[/ { exit }
    found && /^\[[^]]+\]: / { exit }
    found { print }
  ' "$CHANGELOG" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}'
}

body=$(section "$VERSION")
if [ -z "$body" ] && [[ "$VERSION" == *-* ]]; then
  body=$(section "Unreleased")
fi
if [ -z "$body" ]; then
  echo "CHANGELOG.md has no non-empty section for $VERSION" >&2
  [[ "$VERSION" == *-* ]] || echo "Rename [Unreleased] to [$VERSION] - <date> before tagging" >&2
  exit 1
fi
printf '%s\n' "$body"
