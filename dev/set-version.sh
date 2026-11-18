#!/usr/bin/env bash
# SPDX-FileCopyrightText: The pasejo Authors
# SPDX-License-Identifier: 0BSD

# Usage: dev/set-version.sh VERSION [BUILD_FILE]
#
# Writes VERSION, a calendar version as metio/ci/calver computes it
# (YYYY.M.DHHMMSS in UTC), as versionName into BUILD_FILE
# (app/build.gradle.kts) and, as versionCode, the seconds from 2026-01-01 UTC
# to the time VERSION names. Later versions get larger codes, and the code
# reaches Android's maximum of 2100000000 on 2092-07-18. Prints the code.

set -euo pipefail

epoch_2026=1767225600
max_code=2100000000

version=${1:?usage: dev/set-version.sh VERSION [BUILD_FILE]}
file=${2:-app/build.gradle.kts}

fail() { echo "set-version: $*" >&2; exit 1; }

[[ $version =~ ^([0-9]{4})\.([1-9][0-9]?)\.([1-9][0-9]?)([0-9]{2})([0-9]{2})([0-9]{2})$ ]] ||
  fail "$version is not a calendar version YYYY.M.DHHMMSS"
r=("${BASH_REMATCH[@]}")
seconds=$(date -u -d "${r[1]}-${r[2]}-${r[3]} ${r[4]}:${r[5]}:${r[6]}" +%s 2> /dev/null) ||
  fail "$version names no valid time"
code=$((seconds - epoch_2026))
((code > 0 && code <= max_code)) || fail "$version gives the version code $code, outside 1..$max_code"

[ "$(grep -cE '^ *versionCode = [0-9]+$' "$file")" = 1 ] || fail "$file has no single versionCode line"
[ "$(grep -cE '^ *versionName = "[^"]*"$' "$file")" = 1 ] || fail "$file has no single versionName line"
sed -i -E \
  -e "s/^( *versionCode = )[0-9]+$/\1$code/" \
  -e "s/^( *versionName = )\"[^\"]*\"$/\1\"$version\"/" \
  "$file"
echo "$code"
