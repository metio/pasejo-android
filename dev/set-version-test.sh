#!/usr/bin/env bash
# SPDX-FileCopyrightText: The pasejo Authors
# SPDX-License-Identifier: 0BSD

# Tests dev/set-version.sh against copies of a build file.

set -uo pipefail

script=$(dirname "$0")/set-version.sh
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
failures=0

build_file() {
  printf '    defaultConfig {\n        versionCode = 1\n        versionName = "dev"\n    }\n' > "$dir/build.gradle.kts"
}

accepts() { # VERSION CODE
  build_file
  out=$("$script" "$1" "$dir/build.gradle.kts") || { echo "FAIL: $1 was refused"; failures=$((failures + 1)); return; }
  if [ "$out" != "$2" ] ||
    ! grep -qx "        versionCode = $2" "$dir/build.gradle.kts" ||
    ! grep -qx "        versionName = \"$1\"" "$dir/build.gradle.kts"; then
    echo "FAIL: $1 printed $out and wrote:"; cat "$dir/build.gradle.kts"; failures=$((failures + 1))
  else
    echo "ok: $1 -> $2"
  fi
}

refuses() { # VERSION [FILE CONTENT]
  if [ $# -gt 1 ]; then printf '%s\n' "$2" > "$dir/build.gradle.kts"; else build_file; fi
  before=$(cat "$dir/build.gradle.kts")
  if "$script" "$1" "$dir/build.gradle.kts" > /dev/null 2>&1; then
    echo "FAIL: $1 was accepted"; failures=$((failures + 1))
  elif [ "$(cat "$dir/build.gradle.kts")" != "$before" ]; then
    echo "FAIL: refusing $1 changed the file"; failures=$((failures + 1))
  else
    echo "ok: refuses $1${2:+ for a file without single version lines}"
  fi
}

accepts 2026.1.1000001 1
accepts 2026.11.18071819 27760699
accepts 2026.11.9235959 27043199
accepts 2092.7.18132000 2100000000
accepts 2027.1.1000000 31536000

refuses 2026.1.1000000          # code 0
refuses 2092.7.18132001         # code above 2100000000
refuses 2026.2.30120000         # no such day
refuses 2026.11.31000000        # no such day
refuses 2026.11.18250000        # no such hour
refuses 2026.11.18076000        # no such second
refuses 2026.13.1120000         # no such month
refuses 2026.011.18071819       # leading zero
refuses v2026.11.18071819       # prefix
refuses 2026.11.18              # no time
refuses 0.1.0
refuses ""
refuses 2026.11.18071819 'versionName = "dev"'
refuses 2026.11.18071819 $'versionCode = 1\nversionCode = 2\nversionName = "dev"'

# Later versions get larger codes.
build_file; a=$("$script" 2026.11.9235959 "$dir/build.gradle.kts")
build_file; b=$("$script" 2026.11.10000000 "$dir/build.gradle.kts")
if [ "$a" -lt "$b" ]; then echo "ok: 2026.11.9235959 < 2026.11.10000000"; else echo "FAIL: $a >= $b"; failures=$((failures + 1)); fi

[ "$failures" = 0 ] || { echo "$failures failures"; exit 1; }
