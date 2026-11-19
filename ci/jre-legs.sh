#!/usr/bin/env bash
# Parse and check image/extra-jre, the JRE legs CI publishes beside the default.
# The one parser for that file: build.yml's plan step and upstream-check's
# temurin job both call it, so they cannot disagree about what a leg is.
#
#   ci/jre-legs.sh [FILE]      (default image/extra-jre)
#
# Prints one tab-separated row per leg:
#
#   <line number>  <leg as written>  <x64 version>  <arm64 override, or empty>
#
# A leg is <version> or <version>/arm64=<version>, one per line; `#` starts a
# comment. Anything else fails with the line number: a pin file is not something
# to clean up and guess at. That includes a carriage return anywhere — a CRLF
# file would otherwise hand `25.0.5+8\r` to the build as a version.
set -euo pipefail

FILE="${1:-image/extra-jre}"
VERSION_RE='^[0-9]+(\.[0-9]+)*\+[0-9]+$'

die() { echo "$FILE:$1: $2" >&2; exit 1; }

[ -f "$FILE" ] || { echo "$FILE is missing" >&2; exit 1; }
if grep -q $'\r' "$FILE"; then
  echo "$FILE contains a carriage return (CRLF line ends?); save it with LF only" >&2
  exit 1
fi

n=0
while IFS= read -r line || [ -n "$line" ]; do
  n=$((n + 1))
  entry="${line%%#*}"
  read -r -a words <<< "$entry"
  [ "${#words[@]}" -gt 0 ] || continue
  [ "${#words[@]}" -eq 1 ] || die "$n" "one leg per line, got '$line'"
  leg="${words[0]}"
  v="${leg%%/arm64=*}"
  a=""
  if [ "$v" != "$leg" ]; then
    a="${leg#*/arm64=}"
    [ -n "$a" ] || die "$n" "empty /arm64= override in '$leg'; drop the /arm64= part instead"
  fi
  [[ $v =~ $VERSION_RE ]] || die "$n" "not <version> or <version>/arm64=<version>: '$leg'"
  [ -z "$a" ] || [[ $a =~ $VERSION_RE ]] || die "$n" "arm64 override is not a version: '$leg'"
  [ -z "$a" ] || [ "${a%%[.+]*}" = "${v%%[.+]*}" ] || die "$n" "arches disagree on major in '$leg'"
  printf '%s\t%s\t%s\t%s\n' "$n" "$leg" "$v" "$a"
done < "$FILE"
