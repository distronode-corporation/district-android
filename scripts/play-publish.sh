#!/usr/bin/env bash
#
# Google Play Developer API (androidpublisher v3) for release.yml and submit.yml.
#
#   scripts/play-publish.sh upload <aab> <mapping.txt> <versionCode> <versionName>
#       Upload the bundle and its R8 mapping and release it to the INTERNAL track.
#   scripts/play-publish.sh submit <versionCode> <versionName> <notes-file>
#       Release an already uploaded bundle to PRODUCTION with the given release notes. For a
#       published app, committing that edit sends the release for review.
#   scripts/play-publish.sh status <versionCode>
#       Print every track that holds the versionCode, read from a fresh edit.
#
# Credentials: GCP_TOKEN is the `release` environment's own short-lived Google Cloud token
# (google-github-actions/auth). The service account behind it may only mint a token for the
# Play publishing account, so this script exchanges it for one with the androidpublisher scope
# through the IAM Credentials API. Both tokens travel in a 0600 header file, never in argv.
#
# ⛔ `${eid}:commit`, NEVER `$eid:commit`. In zsh `:c` after a bare variable is a history
# modifier, which turns the URL into `.../<id>ommit` and Google answers with a 404 page. This file
# is bash, where the bare form happens to work, but the braces keep it correct wherever the line
# is copied.
#
# RE-RUNNABLE BY DESIGN, because a release job can die between any two calls:
#   - upload skips the bundle and mapping when Play already holds that versionCode (a bundle is
#     consumed once uploaded, and a second upload of the same number is refused), and still sets
#     the internal track;
#   - submit does nothing when production already carries the versionCode in any state other than
#     draft, so a second run can never send the same build for review twice.
# Nothing is committed until the last call of each command. An edit that fails part way is
# deleted, so it leaves nothing half-applied in the Play Console.
set -euo pipefail

PACKAGE=${PLAY_PACKAGE:-com.distronode.districtai}
PLAY_ACCOUNT=${PLAY_SERVICE_ACCOUNT:-play-publisher@distronode.iam.gserviceaccount.com}
API=${PLAY_API_BASE:-https://androidpublisher.googleapis.com}
IAM_API=${IAM_API_BASE:-https://iamcredentials.googleapis.com}
BASE="$API/androidpublisher/v3/applications/$PACKAGE"
UPLOAD_BASE="$API/upload/androidpublisher/v3/applications/$PACKAGE"

fail() {
    echo "play-publish: $1" >&2
    exit 1
}

: "${GCP_TOKEN:?GCP_TOKEN must hold a Google Cloud access token}"

umask 077
work=$(mktemp -d)
eid=""
cleanup() {
    # An edit that was never committed is deleted, so a failure leaves nothing behind.
    if [ -n "$eid" ]; then
        curl -sS -o /dev/null -X DELETE -H "@$work/auth" "$BASE/edits/${eid}" || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

# call METHOD URL [curl args...]: print the body, fail on anything but 2xx with the body on stderr.
call() {
    local method=$1 url=$2 code
    shift 2
    code=$(curl -sS -o "$work/body" -w '%{http_code}' -X "$method" -H "@$work/auth" "$@" "$url") ||
        fail "$method $url did not complete."
    case "$code" in
        2??) cat "$work/body" ;;
        *)
            echo "play-publish: $method ${url#"$API"} answered HTTP $code" >&2
            cat "$work/body" >&2
            echo >&2
            exit 1
            ;;
    esac
}

printf 'Authorization: Bearer %s\n' "$GCP_TOKEN" > "$work/auth"
play_token=$(call POST "$IAM_API/v1/projects/-/serviceAccounts/$PLAY_ACCOUNT:generateAccessToken" \
    -H 'Content-Type: application/json' \
    --data '{"scope":["https://www.googleapis.com/auth/androidpublisher"],"lifetime":"3600s"}' |
    jq -r '.accessToken // empty')
[ -n "$play_token" ] || fail "IAM Credentials returned no access token for $PLAY_ACCOUNT."
if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::$play_token"; fi
printf 'Authorization: Bearer %s\n' "$play_token" > "$work/auth"

open_edit() {
    eid=$(call POST "$BASE/edits" -H 'Content-Type: application/json' --data '{}' | jq -r '.id // empty')
    [ -n "$eid" ] || fail "Play returned no edit id."
}

commit_edit() {
    call POST "$BASE/edits/${eid}:commit" > /dev/null
    eid=""
}

# ⚠️ Called as an `if` condition, where `set -e` is off, so a failed read has to exit explicitly:
# otherwise an API error would read as "no such bundle" and the upload would go ahead.
has_bundle() { # versionCode
    local list
    list=$(call GET "$BASE/edits/${eid}/bundles") || exit 1
    jq -e --argjson vc "$1" '(.bundles // []) | any(.versionCode == $vc)' <<< "$list" > /dev/null
}

# A fresh, read-only edit: the tracks as Play holds them after the commit, as proof.
report() { # versionCode
    open_edit
    call GET "$BASE/edits/${eid}/tracks" | jq -r --arg vc "$1" '
        (.tracks // [])[] as $t | ($t.releases // [])[]
        | select((.versionCodes // []) | index($vc))
        | "\($t.track): \(.name // "(unnamed)") status=\(.status)"'
    call DELETE "$BASE/edits/${eid}" > /dev/null
    eid=""
}

is_number() { case "$1" in '' | *[!0-9]*) return 1 ;; *) return 0 ;; esac; }

cmd=${1:-}
case "$cmd" in
    upload)
        [ $# -eq 5 ] || fail "usage: $0 upload <aab> <mapping.txt> <versionCode> <versionName>"
        aab=$2 mapping=$3 vc=$4 name=$5
        is_number "$vc" || fail "versionCode must be a number, got '$vc'."
        [ -s "$aab" ] || fail "$aab is missing or empty."
        [ -s "$mapping" ] || fail "$mapping is missing or empty."
        open_edit
        if has_bundle "$vc"; then
            echo "Play already holds versionCode $vc; not uploading it again."
        else
            uploaded=$(call POST "$UPLOAD_BASE/edits/${eid}/bundles?uploadType=media" \
                --max-time 1800 -H 'Content-Type: application/octet-stream' --data-binary "@$aab" |
                jq -r '.versionCode // empty')
            # The number Play read out of the bundle, which is the one that counts.
            [ "$uploaded" = "$vc" ] ||
                fail "Play read versionCode '$uploaded' from the bundle, expected $vc. Nothing was committed."
            call POST "$UPLOAD_BASE/edits/${eid}/apks/$vc/deobfuscationFiles/proguard?uploadType=media" \
                --max-time 600 -H 'Content-Type: application/octet-stream' --data-binary "@$mapping" > /dev/null
            echo "Uploaded versionCode $vc and its mapping."
        fi
        body=$(jq -n --arg vc "$vc" --arg name "$vc ($name)" \
            '{track: "internal", releases: [{name: $name, versionCodes: [$vc], status: "completed"}]}')
        call PUT "$BASE/edits/${eid}/tracks/internal" -H 'Content-Type: application/json' --data "$body" > /dev/null
        commit_edit
        echo "Committed: versionCode $vc on the internal track."
        report "$vc"
        ;;
    submit)
        [ $# -eq 4 ] || fail "usage: $0 submit <versionCode> <versionName> <notes-file>"
        vc=$2 name=$3 notes_file=$4
        is_number "$vc" || fail "versionCode must be a number, got '$vc'."
        [ -s "$notes_file" ] || fail "$notes_file is missing or empty."
        open_edit
        has_bundle "$vc" ||
            fail "Play holds no bundle with versionCode $vc. Build and upload it first (release.yml on the tag)."
        state=$(call GET "$BASE/edits/${eid}/tracks/production" | jq -r --arg vc "$vc" '
            [(.releases // [])[] | select((.versionCodes // []) | index($vc)) | .status] | first // empty')
        if [ -n "$state" ] && [ "$state" != "draft" ]; then
            echo "Production already carries versionCode $vc (status $state). Nothing was submitted again."
            call DELETE "$BASE/edits/${eid}" > /dev/null
            eid=""
            report "$vc"
            exit 0
        fi
        # The same notes for every language the store listing has, so no listing is left without.
        languages=$(call GET "$BASE/edits/${eid}/listings" | jq -c '[(.listings // [])[].language]')
        [ "$languages" != "[]" ] || fail "the store listing has no languages."
        body=$(jq -n --arg vc "$vc" --arg name "$vc ($name)" --rawfile notes "$notes_file" \
            --argjson langs "$languages" '
            {track: "production", releases: [{
                name: $name, versionCodes: [$vc], status: "completed",
                releaseNotes: [$langs[] | {language: ., text: ($notes | sub("\n+$"; ""))}]
            }]}')
        call PUT "$BASE/edits/${eid}/tracks/production" -H 'Content-Type: application/json' --data "$body" > /dev/null
        commit_edit
        echo "Committed: versionCode $vc released to production, which sends it for review."
        report "$vc"
        ;;
    status)
        [ $# -eq 2 ] || fail "usage: $0 status <versionCode>"
        is_number "$2" || fail "versionCode must be a number, got '$2'."
        report "$2"
        ;;
    *) fail "usage: $0 upload|submit|status ..." ;;
esac
