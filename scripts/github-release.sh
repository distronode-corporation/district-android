#!/usr/bin/env bash
#
# Publish the GitHub Release for a tag whose build has just been submitted for Google Play review,
# for release.yml's `submit` path and submit.yml. One copy of the release's wording, so every
# release reads the same.
#
#   scripts/github-release.sh print <tag> <versionCode> <submit-run-id> [<build-run-id>]
#       Print the release body to stdout. Touches nothing; the tests and a maintainer's dry run.
#   scripts/github-release.sh publish <tag> <versionCode> <submit-run-id> [<build-run-id>]
#       Publish the release for <tag> with that body, marked Latest, unless one already exists.
#   scripts/github-release.sh build-run <tag>
#       Print the id of the successful `push` run of release.yml that built <tag> (the run that
#       uploaded the bundle Play holds), or nothing when there is none.
#
# <build-run-id> may be empty or left out: the body then says which run submitted it and does not
# guess which one built it. The body is the same shape as v1.1 and v1.2 (v1.2 was published by
# hand and is the template): the submission line, the Play link, the runs, the tag's CHANGELOG
# section as written, and the versionCode sentence.
#
# Environment:
#   CHANGELOG          the CHANGELOG.md to read the section from (default: CHANGELOG.md). In
#                      submit.yml that is the TAG's copy, beside this script from main.
#   SUBMITTED_ON       the submission date, YYYY-MM-DD (default: today, UTC).
#   GITHUB_REPOSITORY  owner/name for the run links and the API (default: this repository).
#   GITHUB_SERVER_URL  (default: https://github.com)
#   GH_TOKEN           for `publish` and `build-run`: a token the gh CLI uses.
#
# ⛔ PUBLISHED IN ONE CALL, NEVER A DRAFT, NEVER EDITED AFTER. This repository has immutable
# releases: once published, a release's body and assets are fixed. So the body is built and
# checked before anything is sent, `gh release create` is given no assets (with assets it makes a
# draft first, then publishes), and nothing here ever edits a release.
#
# RE-RUNNABLE BY DESIGN, like play-publish.sh: a release that already exists for the tag is left
# exactly as it is and the command succeeds, so re-running submit.yml after a failure past the
# Play submission is safe. A create that fails is checked again before it counts as a failure, in
# case the release was made anyway (a lost response, or the other workflow publishing it first).
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
    echo "github-release: $1" >&2
    exit 1
}

usage() {
    echo "usage: $0 print|publish <tag> <versionCode> <submit-run-id> [<build-run-id>]" >&2
    echo "       $0 build-run <tag>" >&2
    exit 1
}

repo=${GITHUB_REPOSITORY:-distronode-corporation/district-android}
server=${GITHUB_SERVER_URL:-https://github.com}
changelog=${CHANGELOG:-CHANGELOG.md}
play_url="https://play.google.com/store/apps/details?id=com.distronode.districtai"

check_tag() {
    printf '%s' "$1" | grep -Eq '^v[0-9]+(\.[0-9]+){0,2}$' || fail "'$1' is not a release tag (v1.1, v1.1.2)."
}

check_run_id() { # what, value
    printf '%s' "$2" | grep -Eq '^[0-9]+$' || fail "the $1 run id '$2' is not a number."
}

run_link() {
    printf '[run %s](%s/%s/actions/runs/%s)' "$1" "$server" "$repo" "$1"
}

# The tag's CHANGELOG section as written, Markdown and line wrapping kept: everything under
# `## [<version>]` up to the next `## ` heading, without link reference lines and without blank
# lines at either end. (release-notes.sh reads the same section but flattens it for Play.)
section() {
    awk -v want="## [$1]" '
        index($0, want) == 1 { inside = 1; next }
        inside && /^## / { exit }
        inside && /^\[[^]]*\]: / { next }
        inside {
            if ($0 ~ /^[[:space:]]*$/) { if (n > 0) blank++; next }
            while (blank > 0) { out[n++] = ""; blank-- }
            out[n++] = $0
        }
        END { for (i = 0; i < n; i++) print out[i] }
    ' "$changelog"
}

body() { # tag, versionCode, submit run, build run (may be empty)
    local tag=$1 vc=$2 submit_run=$3 build_run=$4 version notes date
    version=${tag#v}
    date=${SUBMITTED_ON:-$(date -u +%F)}
    printf '%s' "$date" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' || fail "SUBMITTED_ON '$date' is not YYYY-MM-DD."
    notes=$(section "$version")
    [ -n "$notes" ] || fail "$changelog has no section for $version, or it is empty."

    printf 'District AI for Android %s, the source of the Google Play release submitted for review on %s (versionCode %s).\n\n' \
        "$version" "$date" "$vc"
    printf 'On Google Play: %s\n\n' "$play_url"
    if [ -z "$build_run" ]; then
        printf 'Sent to Google Play review by this repository'"'"'s GitHub Actions workflow (%s).\n\n' \
            "$(run_link "$submit_run")"
    elif [ "$build_run" = "$submit_run" ]; then
        printf 'Built, signed, uploaded and sent to Google Play review by this repository'"'"'s GitHub Actions workflow (%s).\n\n' \
            "$(run_link "$submit_run")"
    else
        printf 'Built, signed and uploaded by this repository'"'"'s GitHub Actions workflow (%s), and sent to Google Play review by %s.\n\n' \
            "$(run_link "$build_run")" "$(run_link "$submit_run")"
    fi
    printf '%s\n\n' "$notes"
    # 4101 is release-version.sh's BUILD_NUMBER_OFFSET, which no release overrides.
    printf 'The versionCode is 4101 plus the commit count, so this tag is the only public commit that builds %s. Changes merged after it are under [Unreleased] in CHANGELOG.md.\n' \
        "$vc"
}

# Exit 0 when a published release exists for the tag, 1 when GitHub answers 404, and fail on
# anything else: an outage or a token problem must not read as "no release".
release_exists() {
    local err
    if err=$(gh api "repos/$repo/releases/tags/$1" 2>&1 > /dev/null); then
        return 0
    fi
    case "$err" in
        *"HTTP 404"*) return 1 ;;
        *) fail "could not read the release for $1: $err" ;;
    esac
}

build_run() { # tag
    local tag=$1 sha runs
    sha=$(gh api "repos/$repo/commits/$tag" | jq -r '.sha') || fail "could not resolve $tag to a commit."
    printf '%s' "$sha" | grep -Eq '^[0-9a-f]{40}$' || fail "$tag resolved to '$sha', not a commit."
    runs=$(gh api "repos/$repo/actions/workflows/release.yml/runs?event=push&branch=$tag&status=success&per_page=100") ||
        fail "could not list release.yml's runs for $tag."
    # The earliest one, if several: that is the run whose upload Play kept.
    printf '%s' "$runs" | jq -r --arg tag "$tag" --arg sha "$sha" '
        [.workflow_runs[]
         | select(.event == "push" and .head_branch == $tag and .head_sha == $sha and .conclusion == "success")]
        | sort_by(.created_at) | first | .id // empty'
}

[ $# -ge 1 ] || usage
cmd=$1
shift
case "$cmd" in
    print | publish)
        [ $# -eq 3 ] || [ $# -eq 4 ] || usage
        tag=$1 vc=$2 submit_run=$3 build_run=${4:-}
        check_tag "$tag"
        printf '%s' "$vc" | grep -Eq '^[0-9]+$' || fail "versionCode '$vc' is not a number."
        check_run_id submit "$submit_run"
        [ -z "$build_run" ] || check_run_id build "$build_run"
        ;;
    build-run)
        [ $# -eq 1 ] || usage
        check_tag "$1"
        build_run "$1"
        exit 0
        ;;
    *) usage ;;
esac

# Built in full before anything is sent: a missing CHANGELOG section stops here.
notes_file=$(mktemp)
trap 'rm -f "$notes_file"' EXIT
body "$tag" "$vc" "$submit_run" "$build_run" > "$notes_file"

if [ "$cmd" = print ]; then
    cat "$notes_file"
    exit 0
fi

if release_exists "$tag"; then
    echo "github-release: a release for $tag already exists; left as it is."
    exit 0
fi

echo "github-release: publishing the release for $tag:"
cat "$notes_file"
if gh release create "$tag" --repo "$repo" --verify-tag --latest \
    --title "District AI for Android ${tag#v}" --notes-file "$notes_file"; then
    echo "github-release: published $server/$repo/releases/tag/$tag"
elif release_exists "$tag"; then
    echo "github-release: the create failed, but a release for $tag now exists; left as it is."
else
    fail "could not publish the release for $tag."
fi
