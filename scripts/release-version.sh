#!/usr/bin/env bash
#
# Print the versionCode and versionName a release build of this checkout carries, as
# `version_code=<n>` and `version_name=<name>` lines (the shape $GITHUB_OUTPUT takes), and refuse
# a checkout that cannot produce a correct one. release.yml and submit.yml both call it, so the
# number that is built and the number that is later submitted come from the same arithmetic.
#
#   scripts/release-version.sh              # a build of HEAD (the `main` dispatch in release.yml)
#   scripts/release-version.sh --tag v1.1   # a release: HEAD must be that tag, on main
#
# versionCode is BUILD_NUMBER_OFFSET (default 4101) plus `git rev-list --count HEAD`, the same
# calculation as app/build.gradle.kts, which explains the offset. The workflows pass the result to
# Gradle as -PdistrictVersionCode so the two can never disagree.
#
# ⛔ A SHALLOW CLONE IS REFUSED, NOT WARNED ABOUT. It answers the depth, not the count (a
# one-commit checkout gives 1), and that number is either already taken on Google Play or, worse,
# accepted and below every later build. Check out with `fetch-depth: 0`.
#
# ⛔ ON A TAG, THREE CHECKS, EACH OF WHICH HAS A WAY TO SHIP THE WRONG THING:
#   - the tag minus its `v` must equal versionName, or the store shows a version the tag does not;
#   - HEAD must be the commit the tag names, or the build is of something else;
#   - that commit must be on main (MAIN_REF, default origin/main), because the commit count is
#     monotonic only along main's linear history. A tag on a side branch can count lower than a
#     build already uploaded, and Google Play refuses a versionCode it has seen or one that
#     goes down.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
    echo "release-version: $1" >&2
    exit 1
}

tag=""
case "${1:-}" in
    "") ;;
    --tag)
        [ $# -eq 2 ] || fail "usage: $0 [--tag v<versionName>]"
        tag=$2
        ;;
    *) fail "usage: $0 [--tag v<versionName>]" ;;
esac

[ "$(git rev-parse --is-shallow-repository)" = "false" ] ||
    fail "this is a shallow clone, so git rev-list --count answers its depth, not the commit count. Check out the full history (fetch-depth: 0)."

offset=${BUILD_NUMBER_OFFSET:-4101}
case "$offset" in
    '' | *[!0-9]*) fail "BUILD_NUMBER_OFFSET must be a non-negative integer, got '$offset'." ;;
esac

count=$(git rev-list --count HEAD)
version_code=$((offset + count))

# The one versionName line in app/build.gradle.kts. Exactly one match, or the file changed shape
# and this parser has to change with it rather than guess.
names=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"[[:space:]]*$/\1/p' app/build.gradle.kts)
[ -n "$names" ] || fail "no versionName = \"...\" line in app/build.gradle.kts."
[ "$(printf '%s\n' "$names" | wc -l | tr -d ' ')" = "1" ] ||
    fail "more than one versionName line in app/build.gradle.kts."
version_name=$names

if [ -n "$tag" ]; then
    [ "$tag" = "v$version_name" ] ||
        fail "tag $tag does not match versionName $version_name in app/build.gradle.kts (expected v$version_name)."
    tag_commit=$(git rev-parse --verify --quiet "refs/tags/$tag^{commit}") ||
        fail "tag $tag does not exist in this checkout."
    [ "$tag_commit" = "$(git rev-parse HEAD)" ] ||
        fail "HEAD is not the commit tag $tag names."
    main_ref=${MAIN_REF:-origin/main}
    git rev-parse --verify --quiet "$main_ref^{commit}" > /dev/null ||
        fail "$main_ref is not in this checkout, so the tag cannot be checked against main."
    git merge-base --is-ancestor "$tag_commit" "$main_ref" ||
        fail "tag $tag points at a commit that is not on main."
fi

echo "version_code=$version_code"
echo "version_name=$version_name"
