#!/usr/bin/env bash
#
# Tests for the release scripts (release-version.sh, release-notes.sh, gsm-secret.sh,
# play-publish.sh, github-release.sh), run by CI on every pull request. No network: curl and gh
# are replaced by stubs that answer from fixtures and record every call, so the tests can assert
# what was sent, in what order, and what was NOT sent (no commit after a failure, no second
# submission, no token in argv, no second release).
#
#   scripts/release-scripts.test.sh
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

passed=0
failed=0
pass() { passed=$((passed + 1)); printf '  PASS %s\n' "$1"; }
flunk() { failed=$((failed + 1)); printf '  FAIL %s\n' "$1"; }
check() { # description, then a command that must succeed
    local what=$1
    shift
    if "$@"; then pass "$what"; else flunk "$what"; fi
}
refuses() { # description, then a command that must fail
    local what=$1
    shift
    if "$@" > "$work/out" 2>&1; then flunk "$what (it succeeded)"; else pass "$what"; fi
}

# ── release-version.sh ────────────────────────────────────────────────────────
echo "release-version.sh"
repo="$work/repo"
mkdir -p "$repo/app" "$repo/scripts"
cp "$root/scripts/release-version.sh" "$repo/scripts/"
(
    cd "$repo"
    git init -q -b main
    git config user.email test@example.com
    git config user.name test
    printf 'android {\n    defaultConfig {\n        versionName = "1.1"\n    }\n}\n' > app/build.gradle.kts
    git add -A
    git commit -qm one
    git commit -q --allow-empty -m two
    git commit -q --allow-empty -m three
    git tag v1.1
    git tag v1.0
    git update-ref refs/remotes/origin/main HEAD
    git checkout -q -b side
    git commit -q --allow-empty -m side
    git checkout -q main
)
out=$(cd "$repo" && scripts/release-version.sh)
check "main: 4101 plus the commit count" test "$out" = "$(printf 'version_code=4104\nversion_name=1.1')"
out=$(cd "$repo" && BUILD_NUMBER_OFFSET=10 scripts/release-version.sh | head -n 1)
check "BUILD_NUMBER_OFFSET is honoured" test "$out" = "version_code=13"
out=$(cd "$repo" && git checkout -q v1.1 && scripts/release-version.sh --tag v1.1 | head -n 1)
check "a tag that matches versionName and is on main" test "$out" = "version_code=4104"
refuses "a tag that does not match versionName" bash -c "cd '$repo' && git checkout -q v1.0 && scripts/release-version.sh --tag v1.0"
refuses "HEAD is not the tagged commit" bash -c "cd '$repo' && git checkout -q main~1 && scripts/release-version.sh --tag v1.1"
(cd "$repo" && git checkout -q side && git tag -f v1.1 > /dev/null)
refuses "a tag on a commit that is not on main" bash -c "cd '$repo' && scripts/release-version.sh --tag v1.1"
refuses "BUILD_NUMBER_OFFSET that is not a number" bash -c "cd '$repo' && BUILD_NUMBER_OFFSET=x scripts/release-version.sh"
git clone -q --depth 1 --branch main "file://$repo" "$work/shallow" 2> /dev/null
refuses "a shallow clone" bash -c "cd '$work/shallow' && scripts/release-version.sh"
check "  (refused for being shallow)" grep -q "shallow clone" "$work/out"

# ── release-notes.sh ──────────────────────────────────────────────────────────
echo "release-notes.sh"
cat > "$work/CHANGELOG.md" << 'EOF'
# Changelog

## [Unreleased]

- Not this.

## [1.1] - 2026-10-01

### Added

- Calls can be `transferred` to a
  colleague, see [the guide](https://example.com/guide).
- Faster sign-in.

## [1.0] - 2026-09-26

The first release.

[1.1]: https://example.com/v1.1
EOF
expected=$(printf '• Calls can be transferred to a colleague, see the guide.\n• Faster sign-in.')
out=$(CHANGELOG="$work/CHANGELOG.md" "$root/scripts/release-notes.sh" 1.1)
check "a section flattened to plain text" test "$out" = "$expected"
out=$(CHANGELOG="$work/CHANGELOG.md" "$root/scripts/release-notes.sh" 1.0)
check "the last section stops before link definitions" test "$out" = "The first release."
refuses "a version with no section" env CHANGELOG="$work/CHANGELOG.md" "$root/scripts/release-notes.sh" 2.0
{
    printf '## [3.0] - 2026-12-01\n\n'
    for _ in $(seq 1 60); do printf -- '- Ééééééé.\n'; done
} > "$work/LONG.md"
refuses "a section over Play's 500 characters" env CHANGELOG="$work/LONG.md" "$root/scripts/release-notes.sh" 3.0
printf '## [3.1] - 2026-12-01\n\n- %s\n' "$(printf 'é%.0s' $(seq 1 480))" > "$work/UTF.md"
check "characters are counted, not bytes (482 characters, 963 bytes)" \
    bash -c "CHANGELOG='$work/UTF.md' '$root/scripts/release-notes.sh' 3.1 > /dev/null"
check "the real CHANGELOG's 1.0 section fits" bash -c "'$root/scripts/release-notes.sh' 1.0 > /dev/null"

# ── the curl stub ─────────────────────────────────────────────────────────────
stubbin="$work/bin"
mkdir -p "$stubbin"
cat > "$stubbin/curl" << 'EOF'
#!/usr/bin/env bash
# Answers from $STUB_DIR fixtures; appends "METHOD PATH" to $STUB_DIR/calls and the whole argv to
# $STUB_DIR/argv; saves each request body as $STUB_DIR/body-<METHOD>-<last path segment>.
set -euo pipefail
method=GET out=/dev/stdout fmt="" url="" data=""
while [ $# -gt 0 ]; do
    case "$1" in
        -o) out=$2; shift ;;
        -w) fmt=$2; shift ;;
        -X) method=$2; shift ;;
        -H | --max-time) shift ;;
        --data | --data-binary) data=$2; shift ;;
        -sS) ;;
        http*) url=$1 ;;
    esac
    shift
done
printf '%s\n' "$*" >> "$STUB_DIR/argv"
path=${url#*://*/}
path=${path%%\?*}
echo "$method /$path" >> "$STUB_DIR/calls"
seg=${path##*/}
if [ -n "$data" ]; then printf '%s' "$data" > "$STUB_DIR/body-$method-$seg"; fi
code=200 body='{}'
case "$method /$path" in
    "POST /v1/projects/-/serviceAccounts/"*":generateAccessToken") body='{"accessToken":"play-token-XYZ"}' ;;
    "GET /v1/projects/distronode/secrets/"*) body=$(cat "$STUB_DIR/gsm" 2> /dev/null || echo '{}') ; code=$(cat "$STUB_DIR/gsm-code" 2> /dev/null || echo 200) ;;
    "POST /androidpublisher/v3/applications/"*"/edits") body='{"id":"E1"}' ;;
    "POST /upload/"*"/bundles") body="{\"versionCode\": $(cat "$STUB_DIR/uploaded-vc")}" ;;
    *"/edits/E1/bundles") body=$(cat "$STUB_DIR/bundles"); code=$(cat "$STUB_DIR/bundles-code" 2> /dev/null || echo 200) ;;
    "GET "*"/edits/E1/tracks/production") body=$(cat "$STUB_DIR/production") ;;
    "GET "*"/edits/E1/tracks") body='{"tracks":[{"track":"internal","releases":[{"name":"x","versionCodes":["4185"],"status":"completed"}]}]}' ;;
    "GET "*"/edits/E1/listings") body='{"listings":[{"language":"en-US"},{"language":"fr-CA"}]}' ;;
    "DELETE "*) code=204 body='' ;;
esac
printf '%s' "$body" > "$out"
if [ -n "$fmt" ]; then printf '%s' "$code"; fi
EOF
chmod +x "$stubbin/curl"

reset_stub() {
    export STUB_DIR="$work/stub"
    rm -rf "$STUB_DIR"
    mkdir -p "$STUB_DIR"
    echo '{"bundles":[{"versionCode":4102}]}' > "$STUB_DIR/bundles"
    echo 4185 > "$STUB_DIR/uploaded-vc"
    echo '{"track":"production","releases":[{"versionCodes":["4102"],"status":"completed"}]}' > "$STUB_DIR/production"
    : > "$STUB_DIR/calls"
    : > "$STUB_DIR/argv"
    : > "$STUB_DIR/gh-calls"
}
called() { grep -qxF "$1" "$STUB_DIR/calls"; }
not_called() { ! grep -qF "$1" "$STUB_DIR/calls"; }
play() { PATH="$stubbin:$PATH" GCP_TOKEN=gcp-token-ABC "$root/scripts/play-publish.sh" "$@"; }
printf 'x' > "$work/app.aab"
printf 'y' > "$work/mapping.txt"
printf 'Line one.\n' > "$work/notes.txt"
P=/androidpublisher/v3/applications/com.distronode.districtai
U=/upload/androidpublisher/v3/applications/com.distronode.districtai

# ── gsm-secret.sh ─────────────────────────────────────────────────────────────
echo "gsm-secret.sh"
reset_stub
printf '{"payload":{"data":"%s"}}' "$(printf 'hunter2' | base64)" > "$STUB_DIR/gsm"
out=$(PATH="$stubbin:$PATH" GCP_TOKEN=gcp-token-ABC "$root/scripts/gsm-secret.sh" SOME_SECRET)
check "decodes the payload" test "$out" = "hunter2"
check "reads the latest version of the named secret" \
    called "GET /v1/projects/distronode/secrets/SOME_SECRET/versions/latest:access"
check "the token is never in curl's argv" bash -c "! grep -q gcp-token-ABC '$STUB_DIR/argv'"
echo 403 > "$STUB_DIR/gsm-code"
refuses "a non-200 answer" env PATH="$stubbin:$PATH" GCP_TOKEN=t "$root/scripts/gsm-secret.sh" SOME_SECRET

# ── play-publish.sh ───────────────────────────────────────────────────────────
echo "play-publish.sh upload"
reset_stub
play upload "$work/app.aab" "$work/mapping.txt" 4185 1.0 > "$work/out"
check "uploads the bundle" called "POST $U/edits/E1/bundles"
check "uploads the mapping under the versionCode" called "POST $U/edits/E1/apks/4185/deobfuscationFiles/proguard"
check "sets the internal track" called "PUT $P/edits/E1/tracks/internal"
check "commits with \${eid}:commit" called "POST $P/edits/E1:commit"
check "the internal release names the versionCode and versionName" \
    test "$(jq -c '.releases' "$STUB_DIR/body-PUT-internal")" = '[{"name":"4185 (1.0)","versionCodes":["4185"],"status":"completed"}]'
check "reads the tracks back after the commit" called "GET $P/edits/E1/tracks"
check "neither token is ever in curl's argv" bash -c "! grep -qE 'gcp-token-ABC|play-token-XYZ' '$STUB_DIR/argv'"

reset_stub
echo '{"bundles":[{"versionCode":4102},{"versionCode":4185}]}' > "$STUB_DIR/bundles"
play upload "$work/app.aab" "$work/mapping.txt" 4185 1.0 > "$work/out"
check "a re-run does not upload a bundle Play already holds" not_called "POST $U/edits/E1/bundles"
check "a re-run still sets the internal track and commits" called "POST $P/edits/E1:commit"

reset_stub
echo 4186 > "$STUB_DIR/uploaded-vc"
refuses "Play reading a different versionCode from the bundle" play upload "$work/app.aab" "$work/mapping.txt" 4185 1.0
check "and then nothing is committed" not_called ":commit"
check "and the edit is deleted" called "DELETE $P/edits/E1"

reset_stub
echo 500 > "$STUB_DIR/bundles-code"
refuses "a failed bundle listing" play upload "$work/app.aab" "$work/mapping.txt" 4185 1.0
check "is not read as 'no bundle', so nothing is uploaded" not_called "POST $U/edits/E1/bundles"

echo "play-publish.sh submit"
reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
play submit 4185 1.0 "$work/notes.txt" > "$work/out"
check "releases to production" called "PUT $P/edits/E1/tracks/production"
check "commits, which sends it for review" called "POST $P/edits/E1:commit"
check "notes for every listing language, versionCode and status" \
    test "$(jq -c '.releases' "$STUB_DIR/body-PUT-production")" = \
    '[{"name":"4185 (1.0)","versionCodes":["4185"],"status":"completed","releaseNotes":[{"language":"en-US","text":"Line one."},{"language":"fr-CA","text":"Line one."}]}]'

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
echo '{"track":"production","releases":[{"versionCodes":["4185"],"status":"completed"}]}' > "$STUB_DIR/production"
play submit 4185 1.0 "$work/notes.txt" > "$work/out"
check "a build already on production is not submitted again" not_called "PUT $P/edits/E1/tracks/production"
check "and nothing is committed" not_called ":commit"

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
echo '{"track":"production","releases":[{"versionCodes":["4185"],"status":"draft"}]}' > "$STUB_DIR/production"
play submit 4185 1.0 "$work/notes.txt" > "$work/out"
check "a draft on production is completed and submitted" called "POST $P/edits/E1:commit"

reset_stub
refuses "a versionCode Play does not hold" play submit 4185 1.0 "$work/notes.txt"
check "and nothing is committed" not_called ":commit"

# ── github-release.sh ─────────────────────────────────────────────────────────
# The gh CLI is replaced by a stub too. It appends its argv to $STUB_DIR/gh-calls, keeps the notes
# file it was given, and answers from these switches in $STUB_DIR: `exists` (the release exists),
# `read-error` (reading it fails with a 500), `create-fails` (the create fails) and `create-lands`
# (the create fails but the release was made anyway).
cat > "$stubbin/gh" << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$STUB_DIR/gh-calls"
case "$1 $2" in
    "api repos/"*"/releases/tags/"*)
        if [ -e "$STUB_DIR/read-error" ]; then echo "gh: Server Error (HTTP 500)" >&2; exit 1; fi
        if [ -e "$STUB_DIR/exists" ]; then echo '{"tag_name":"x"}'; exit 0; fi
        echo "gh: Not Found (HTTP 404)" >&2
        exit 1
        ;;
    "api repos/"*"/commits/"*) echo '{"sha":"1a4711f30370d3b607f4dbfe538f5822f159d4a1"}' ;;
    "api repos/"*"/actions/workflows/release.yml/runs"*) cat "$STUB_DIR/runs" ;;
    "release create")
        while [ $# -gt 0 ]; do
            if [ "$1" = --notes-file ]; then cp "$2" "$STUB_DIR/created-notes"; fi
            shift
        done
        if [ -e "$STUB_DIR/create-lands" ]; then touch "$STUB_DIR/exists"; exit 1; fi
        if [ -e "$STUB_DIR/create-fails" ]; then exit 1; fi
        touch "$STUB_DIR/exists"
        ;;
    *) echo "gh stub: unexpected call: $*" >&2; exit 1 ;;
esac
EOF
chmod +x "$stubbin/gh"
ghrel() {
    PATH="$stubbin:$PATH" GITHUB_REPOSITORY=distronode-corporation/district-android \
        GITHUB_SERVER_URL=https://github.com SUBMITTED_ON=2026-10-02 "$root/scripts/github-release.sh" "$@"
}
gh_called() { grep -qF -- "$1" "$STUB_DIR/gh-calls"; }
gh_not_called() { ! grep -qF -- "$1" "$STUB_DIR/gh-calls"; }

echo "github-release.sh print"
# v1.2's release exactly as it was published (by hand, the template for every release since),
# from the real CHANGELOG.md, byte for byte.
cat > "$work/v1.2-body.md" << 'EOF'
District AI for Android 1.2, the source of the Google Play release submitted for review on 2026-10-02 (versionCode 4201).

On Google Play: https://play.google.com/store/apps/details?id=com.distronode.districtai

Built, signed and uploaded by this repository's GitHub Actions workflow ([run 36968058179](https://github.com/distronode-corporation/district-android/actions/runs/36968058179)), and sent to Google Play review by [run 36978578982](https://github.com/distronode-corporation/district-android/actions/runs/36978578982).

### Fixed

- After you miss or decline a call, the next calls still ring. Before, they could stop arriving
  until you opened the app.
- If you hang up just after answering, the call no longer connects anyway.
- Tapping a message notification opens that message, not the newest one.
- A draft's pictures are kept when you reopen the conversation.
- A credit under $1 shows its minus sign.
- After a change to the device's security settings, signing in keeps you signed in again.

The versionCode is 4101 plus the commit count, so this tag is the only public commit that builds 4201. Changes merged after it are under [Unreleased] in CHANGELOG.md.
EOF
reset_stub
ghrel print v1.2 4201 36978578982 36968058179 > "$work/out-body.md"
check "v1.2's published body, byte for byte" cmp -s "$work/out-body.md" "$work/v1.2-body.md"
check "print calls nothing" test ! -s "$STUB_DIR/gh-calls"
out=$(ghrel print v1.2 4201 36978578982 | sed -n 5p)
check "no build run: the submit run alone, no guess at the build" \
    test "$out" = "Sent to Google Play review by this repository's GitHub Actions workflow ([run 36978578982](https://github.com/distronode-corporation/district-android/actions/runs/36978578982))."
out=$(ghrel print v1.2 4201 36978578982 36978578982 | sed -n 5p)
check "one run that built and submitted is named once" \
    test "$out" = "Built, signed, uploaded and sent to Google Play review by this repository's GitHub Actions workflow ([run 36978578982](https://github.com/distronode-corporation/district-android/actions/runs/36978578982))."
out=$(CHANGELOG="$work/CHANGELOG.md" ghrel print v1.0 4102 2 1 | sed -n '7,$p')
check "the last section stops before link definitions" \
    test "$out" = "$(printf 'The first release.\n\nThe versionCode is 4101 plus the commit count, so this tag is the only public commit that builds 4102. Changes merged after it are under [Unreleased] in CHANGELOG.md.')"
out=$(CHANGELOG="$work/CHANGELOG.md" ghrel print v1.1 4188 2 1 | sed -n '7,11p')
check "a section is copied as written, Markdown and wrapping kept" \
    test "$out" = "$(sed -n '9,13p' "$work/CHANGELOG.md")"
refuses "a version with no section" env CHANGELOG="$work/CHANGELOG.md" PATH="$stubbin:$PATH" \
    "$root/scripts/github-release.sh" print v2.0 4300 2 1
refuses "a tag that is not a release tag" ghrel print 1.2 4201 2 1
refuses "a run id that is not a number" ghrel print v1.2 4201 2 'x;y'
refuses "a versionCode that is not a number" ghrel print v1.2 42a 2 1

echo "github-release.sh publish"
reset_stub
ghrel publish v1.2 4201 36978578982 36968058179 > "$work/out"
check "publishes the tag's release, Latest, under the release title" \
    gh_called "release create v1.2 --repo distronode-corporation/district-android --verify-tag --latest --title District AI for Android 1.2 --notes-file"
check "with exactly the printed body" cmp -s "$STUB_DIR/created-notes" "$work/v1.2-body.md"
check "never as a draft, and never edited" bash -c "! grep -qE -- '--draft|release edit|release upload' '$STUB_DIR/gh-calls'"

reset_stub
touch "$STUB_DIR/exists"
ghrel publish v1.2 4201 36978578982 36968058179 > "$work/out"
check "a release that already exists is left as it is" gh_not_called "release create"
check "  (and the run says so)" grep -q "already exists" "$work/out"

reset_stub
touch "$STUB_DIR/read-error"
refuses "a failed read is not taken as 'no release'" ghrel publish v1.2 4201 36978578982 36968058179
check "  (so nothing is created)" gh_not_called "release create"

reset_stub
touch "$STUB_DIR/create-lands"
ghrel publish v1.2 4201 36978578982 36968058179 > "$work/out"
check "a failed create whose release exists after all succeeds" grep -q "now exists" "$work/out"

reset_stub
touch "$STUB_DIR/create-fails"
refuses "a failed create with no release fails" ghrel publish v1.2 4201 36978578982 36968058179

reset_stub
refuses "a missing section fails before anything is sent" env CHANGELOG="$work/CHANGELOG.md" \
    PATH="$stubbin:$PATH" "$root/scripts/github-release.sh" publish v2.0 4300 2 1
check "  (nothing was called)" test ! -s "$STUB_DIR/gh-calls"

echo "github-release.sh build-run"
reset_stub
sha=1a4711f30370d3b607f4dbfe538f5822f159d4a1
jq -n --arg sha "$sha" '{workflow_runs: [
    {id: 5, event: "push", head_branch: "v1.2", head_sha: $sha, conclusion: "success", created_at: "2026-10-02T09:00:00Z"},
    {id: 3, event: "push", head_branch: "v1.2", head_sha: $sha, conclusion: "success", created_at: "2026-10-02T05:14:36Z"},
    {id: 2, event: "workflow_dispatch", head_branch: "v1.2", head_sha: $sha, conclusion: "success", created_at: "2026-10-01T00:00:00Z"},
    {id: 1, event: "push", head_branch: "v1.2", head_sha: "0000000000000000000000000000000000000000", conclusion: "success", created_at: "2026-09-30T00:00:00Z"},
    {id: 4, event: "push", head_branch: "v1.1", head_sha: $sha, conclusion: "success", created_at: "2026-09-29T00:00:00Z"}]}' \
    > "$STUB_DIR/runs"
out=$(ghrel build-run v1.2)
check "the earliest successful push run of the tag's own commit" test "$out" = 3
check "asks for successful push runs of release.yml on the tag" \
    gh_called "api repos/distronode-corporation/district-android/actions/workflows/release.yml/runs?event=push&branch=v1.2&status=success"
echo '{"workflow_runs":[]}' > "$STUB_DIR/runs"
out=$(ghrel build-run v1.2)
check "no such run: prints nothing" test -z "$out"

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
