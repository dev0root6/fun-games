#!/usr/bin/env bash
# Build release APKs into releases/ and tag them.
#
#   ./release.sh neon-maze 1.1        build one game as v1.1
#   ./release.sh all 1.1              build both
#
# The version code is derived from the version name (1.1 -> 101, 2.3 -> 203).
# Commit the new APKs afterwards; the script creates <game>-v<version> tags for you.
set -euo pipefail
cd "$(dirname "$0")"

game=${1:?usage: ./release.sh <neon-maze|neon-imposter|all> <version>}
version=${2:?usage: ./release.sh <neon-maze|neon-imposter|all> <version>}
major=${version%%.*}
minor=${version#*.}
minor=${minor%%.*}
code=$((major * 100 + minor))

games=("$game")
[[ "$game" == "all" ]] && games=(neon-maze neon-imposter)

mkdir -p releases
for g in "${games[@]}"; do
    [[ -x "$g/android/build.sh" ]] || { echo "unknown game: $g" >&2; exit 1; }
    out="releases/$g-v$version.apk"
    [[ -e "$out" ]] && { echo "$out already exists — bump the version" >&2; exit 1; }
    echo "── $g v$version (code $code)"
    VERSION_NAME="$version" VERSION_CODE="$code" "$g/android/build.sh" >/dev/null 2>&1
    apk=$(ls "$g"/android/build/Neon*.apk | grep -v -e aligned -e base)
    cp "$apk" "$out"
    echo "   $out"
done

echo
echo "next: git add releases && git commit -m \"Release ${games[*]} v$version\""
echo "      then tag: $(for g in "${games[@]}"; do printf 'git tag %s-v%s; ' "$g" "$version"; done)"
