#!/usr/bin/env bash
#
# Tests for the release scripts (release-version.sh, release-notes.sh, gsm-secret.sh,
# play-publish.sh, github-release.sh, release-build.sh, french-guard.sh), run by CI on every pull
# request. No network: curl, gh, Gradle, keytool and aapt2 are replaced by stubs that answer from
# fixtures and record every call, so the tests can assert what was sent, in what order, and what
# was NOT sent (no commit after a failure, no second submission, no token in argv, no second
# release, no French flag nobody chose).
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
passes() { # description, then a command that must succeed, its output kept in $work/out
    local what=$1
    shift
    if "$@" > "$work/out" 2>&1; then pass "$what"; else flunk "$what (it failed: $(tail -n 3 "$work/out"))"; fi
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

# Other languages: release-notes/<language>/<version>.txt, never filled in from English.
notes_root="$work/release-notes"
mkdir -p "$notes_root/fr-CA"
printf '• Les appels peuvent être transférés.\n\n\n' > "$notes_root/fr-CA/1.1.txt"
rn() { CHANGELOG="$work/CHANGELOG.md" RELEASE_NOTES_DIR="$notes_root" "$root/scripts/release-notes.sh" "$@"; }
out=$(rn 1.1 fr-CA)
check "a language's own file, trailing blank lines dropped" test "$out" = "• Les appels peuvent être transférés."
out=$(rn 1.1 en-US)
check "en-US by name is the CHANGELOG section" test "$out" = "$expected"
refuses "a language with no file for the version" rn 1.0 fr-CA
check "  (refused, naming the missing file)" grep -q "release-notes/fr-CA/1.0.txt is missing" "$work/out"
refuses "  (and no English is printed in its place)" grep -q "The first release" "$work/out"
refuses "a language code that is not one" rn 1.1 '../x'
rn 1.1 --dir "$work/notes-out" 2> /dev/null
check "--dir writes en-US from the CHANGELOG" test "$(cat "$work/notes-out/en-US.txt")" = "$expected"
check "--dir writes every language under release-notes/" \
    test "$(cat "$work/notes-out/fr-CA.txt")" = "• Les appels peuvent être transférés."
refuses "--dir with a language missing its file for the version" rn 1.0 --dir "$work/notes-out-1.0"
check "  (and it does not write that language from English)" test ! -e "$work/notes-out-1.0/fr-CA.txt"
: > "$notes_root/fr-CA/1.0.txt"
refuses "an empty language file" rn 1.0 fr-CA
printf -- '- %s\n' "$(printf 'é%.0s' $(seq 1 499))" > "$notes_root/fr-CA/1.1.txt"
refuses "a language file over Play's 500 characters" rn 1.1 fr-CA
refuses "  (and --dir refuses it too)" rn 1.1 --dir "$work/notes-out-long"
printf -- '- %s\n' "$(printf 'é%.0s' $(seq 1 498))" > "$notes_root/fr-CA/1.1.txt"
check "a language file of exactly 500 characters" bash -c "CHANGELOG='$work/CHANGELOG.md' RELEASE_NOTES_DIR='$notes_root' '$root/scripts/release-notes.sh' 1.1 fr-CA > /dev/null"
check "the real 2.1 notes fit, in every language" \
    bash -c "cd '$root' && scripts/release-notes.sh 2.1 --dir '$work/notes-2.1' 2> /dev/null && test -s '$work/notes-2.1/en-US.txt' && test -s '$work/notes-2.1/fr-CA.txt'"

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
mkdir -p "$work/notes"
printf 'Line one.\n' > "$work/notes/en-US.txt"
printf 'Ligne un.\n\n' > "$work/notes/fr-CA.txt"
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
play submit 4185 1.0 "$work/notes" > "$work/out"
check "releases to production" called "PUT $P/edits/E1/tracks/production"
check "commits, which sends it for review" called "POST $P/edits/E1:commit"
check "each listing language's own notes, versionCode and status" \
    test "$(jq -c '.releases' "$STUB_DIR/body-PUT-production")" = \
    '[{"name":"4185 (1.0)","versionCodes":["4185"],"status":"completed","releaseNotes":[{"language":"en-US","text":"Line one."},{"language":"fr-CA","text":"Ligne un."}]}]'

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
mkdir -p "$work/notes-en"
cp "$work/notes/en-US.txt" "$work/notes-en/"
refuses "a listing language with no notes file" play submit 4185 1.0 "$work/notes-en"
check "  (refused, naming the language)" grep -q "language(s) fr-CA" "$work/out"
check "  (and production is not touched: no English in its place)" not_called "PUT $P/edits/E1/tracks/production"
check "  (and nothing is committed)" not_called ":commit"

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
mkdir -p "$work/notes-long"
cp "$work/notes/en-US.txt" "$work/notes-long/"
printf '%s\n' "$(printf 'é%.0s' $(seq 1 501))" > "$work/notes-long/fr-CA.txt"
refuses "notes over 500 characters in any language" play submit 4185 1.0 "$work/notes-long"
check "  (and production is not touched)" not_called "PUT $P/edits/E1/tracks/production"
check "  (and nothing is committed)" not_called ":commit"

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
refuses "a notes file instead of a directory" play submit 4185 1.0 "$work/notes/en-US.txt"
check "  (and nothing is committed)" not_called ":commit"

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
echo '{"track":"production","releases":[{"versionCodes":["4185"],"status":"completed"}]}' > "$STUB_DIR/production"
play submit 4185 1.0 "$work/notes" > "$work/out"
check "a build already on production is not submitted again" not_called "PUT $P/edits/E1/tracks/production"
check "and nothing is committed" not_called ":commit"

reset_stub
echo '{"bundles":[{"versionCode":4185}]}' > "$STUB_DIR/bundles"
echo '{"track":"production","releases":[{"versionCodes":["4185"],"status":"draft"}]}' > "$STUB_DIR/production"
play submit 4185 1.0 "$work/notes" > "$work/out"
check "a draft on production is completed and submitted" called "POST $P/edits/E1:commit"

reset_stub
refuses "a versionCode Play does not hold" play submit 4185 1.0 "$work/notes"
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

# ── release-build.sh ──────────────────────────────────────────────────────────
# The French switch and the Sentry release are tested here: DISTRICT_FRENCH must be true or false, and reaches Gradle
# by value as -Pdistrict.l10n.fr=true or =false (French is on by default since 3.0). Secret Manager, keytool and Gradle are stubs; the
# stub Gradle records its argv and writes a signed-looking bundle and a mapping.
echo "release-build.sh"
rb="$work/rb"
rbbin="$work/rbbin"
mkdir -p "$rb/scripts" "$rbbin" "$work/rbtmp"
cp "$root/scripts/release-build.sh" "$rb/scripts/"
cat > "$rb/scripts/gsm-secret.sh" << 'EOF'
#!/usr/bin/env bash
echo "$1" >> "$RB_LOG/gsm-calls"
case "$1" in
ANDROID_UPLOAD_KEYSTORE) printf 'stub-keystore' | base64 ;;
*) printf 'stub-%s' "$1" ;;
esac
EOF
cat > "$rb/gradlew" << 'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$RB_LOG/gradle-args"
mkdir -p app/build/outputs/bundle/release app/build/outputs/mapping/release
python3 -I -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("META-INF/STUB.RSA", "sig"); z.writestr("base/manifest/AndroidManifest.xml", "m"); z.close()' \
    app/build/outputs/bundle/release/app-release.aab
echo "mapping" > app/build/outputs/mapping/release/mapping.txt
EOF
printf '#!/bin/sh\nexit 0\n' > "$rbbin/keytool"
chmod +x "$rb/scripts/gsm-secret.sh" "$rb/gradlew" "$rbbin/keytool"
rbrun() { # DISTRICT_FRENCH value, or "unset"
    rm -rf "$work/rblog"
    mkdir -p "$work/rblog"
    if [ "$1" = unset ]; then
        env -u DISTRICT_FRENCH PATH="$rbbin:$PATH" RB_LOG="$work/rblog" RUNNER_TEMP="$work/rbtmp" \
            GCP_TOKEN=t VERSION_CODE=4242 "$rb/scripts/release-build.sh"
    else
        env PATH="$rbbin:$PATH" RB_LOG="$work/rblog" RUNNER_TEMP="$work/rbtmp" GCP_TOKEN=t \
            VERSION_CODE=4242 DISTRICT_FRENCH="$1" "$rb/scripts/release-build.sh"
    fi
}
passes "DISTRICT_FRENCH=false builds" rbrun false
check "  (Gradle got -Pdistrict.l10n.fr=false)" grep -qx -- '-Pdistrict.l10n.fr=false' "$work/rblog/gradle-args"
check "  (and the version code)" grep -qx -- '-PdistrictVersionCode=4242' "$work/rblog/gradle-args"
passes "DISTRICT_FRENCH=true builds" rbrun true
check "  (Gradle got -Pdistrict.l10n.fr=true)" grep -qx -- '-Pdistrict.l10n.fr=true' "$work/rblog/gradle-args"
refuses "DISTRICT_FRENCH unset is refused" rbrun unset
check "  (the message names the variable)" grep -q "DISTRICT_FRENCH is '', not true or false" "$work/out"
check "  (no secret was read)" test ! -e "$work/rblog/gsm-calls"
check "  (Gradle never ran)" test ! -e "$work/rblog/gradle-args"
refuses "DISTRICT_FRENCH=yes is refused" rbrun yes
check "  (the message quotes the value)" grep -q "DISTRICT_FRENCH is 'yes'" "$work/out"
check "  (Gradle never ran)" test ! -e "$work/rblog/gradle-args"

# The Sentry release after the build: the SDK's name, HEAD's commit, the EU host from
# DISTRICT_SENTRY_URL, the token only in the environment, and never a failed build. sentry-cli and
# git are stubs in a directory of their own, so the cases above see no sentry-cli at all.
rbsentry="$work/rbsentry"
mkdir -p "$rbsentry" "$rb/app"
printf '        applicationId = "com.distronode.districtai"\n' > "$rb/app/build.gradle.kts"
cat > "$rbsentry/sentry-cli" << 'EOF2'
#!/usr/bin/env bash
echo "$* | SENTRY_URL=${SENTRY_URL:-unset} token=${SENTRY_AUTH_TOKEN:+set}" >> "$RB_LOG/sentry-calls"
[ "$1 $2" != "releases ${RB_SENTRY_FAIL:-none}" ]
EOF2
cat > "$rbsentry/git" << 'EOF2'
#!/usr/bin/env bash
[ "$*" = "rev-parse HEAD" ] && echo feedfacefeedfacefeedfacefeedfacefeedface
EOF2
chmod +x "$rbsentry/sentry-cli" "$rbsentry/git"
rbsentryrun() { # extra NAME=value pairs for env
    rm -rf "$work/rblog"
    mkdir -p "$work/rblog"
    env PATH="$rbsentry:$rbbin:$PATH" RB_LOG="$work/rblog" RUNNER_TEMP="$work/rbtmp" GCP_TOKEN=t \
        VERSION_CODE=4262 VERSION_NAME=3.0.2 DISTRICT_FRENCH=true \
        DISTRICT_SENTRY_URL=https://de.sentry.io DISTRICT_SENTRY_ORG=stub-org DISTRICT_SENTRY_PROJECT=stub-project \
        "$@" "$rb/scripts/release-build.sh"
}
release=com.distronode.districtai@3.0.2+4262
passes "the Sentry release is created after the build" rbsentryrun
check "  (it prints the release name and the commit)" \
    grep -qx "Sentry release: $release (commit feedfacefeedfacefeedfacefeedfacefeedface)" "$work/out"
check "  (releases new: the argv is exact)" grep -qxF \
    "releases new --org stub-org --project stub-project $release | SENTRY_URL=https://de.sentry.io token=set" \
    "$work/rblog/sentry-calls"
check "  (releases set-commits: the argv is exact)" grep -qxF \
    "releases set-commits --org stub-org $release --commit distronode-corporation/district-android@feedfacefeedfacefeedfacefeedfacefeedface --ignore-missing | SENTRY_URL=https://de.sentry.io token=set" \
    "$work/rblog/sentry-calls"
check "  (releases finalize: the argv is exact)" grep -qxF \
    "releases finalize --org stub-org $release | SENTRY_URL=https://de.sentry.io token=set" \
    "$work/rblog/sentry-calls"
check "  (three calls, in that order)" test "$(cut -d' ' -f2 "$work/rblog/sentry-calls" | tr '\n' ' ')" = "new set-commits finalize "
check "  (the token is never an argument)" bash -c "! grep -q stub-SENTRY_EU_AUTH_TOKEN '$work/rblog/sentry-calls'"
check "  (no warning)" bash -c "! grep -q '::warning::' '$work/out'"
passes "a failed set-commits warns and the build still succeeds" rbsentryrun RB_SENTRY_FAIL=set-commits
check "  (the warning names the versionCode)" grep -q "::warning::the Sentry release for versionCode 4262" "$work/out"
check "  (nothing is finalized after it)" bash -c "! grep -q '^releases finalize' '$work/rblog/sentry-calls'"
passes "no VERSION_NAME warns and creates no release under a guessed name" rbsentryrun VERSION_NAME=
check "  (it warns)" grep -q "::warning::the Sentry release" "$work/out"
check "  (sentry-cli never ran)" test ! -e "$work/rblog/sentry-calls"
rm -f "$rb/app/build.gradle.kts"
passes "no applicationId line warns and creates no release" rbsentryrun
check "  (it names the line it looked for)" grep -q "not exactly one applicationId" "$work/out"
check "  (sentry-cli never ran)" test ! -e "$work/rblog/sentry-calls"

# ── french-guard.sh ───────────────────────────────────────────────────────────
# A tree of its own: the guard reads the app's keys from app/src/main/res/values beside it. The
# stub aapt2 answers `dump resources` with $FG_TABLE and `dump xmltree` with $FG_LOCALES, in the
# formats aapt2 37.0.0 printed for a real bundle (2026-10-14), and records its argv.
echo "french-guard.sh"
fg="$work/fg"
mkdir -p "$fg/scripts" "$fg/app/src/main/res/values" "$work/sdk/build-tools/37.0.0"
cp "$root/scripts/french-guard.sh" "$fg/scripts/"
cat > "$fg/app/src/main/res/values/strings.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<!-- @generated by i18n/tools/l10n.py from catalog "android". Do not edit; run l10n.py gen. -->
<resources>
    <string name="app_name">District AI</string>
    <string name="sign_in_action">Sign in</string>
    <string name="date_format_ymd" translatable="false">2026-12-31</string>
    <plurals name="entry_count">
        <item quantity="one">%1$d entry</item>
        <item quantity="other">%1$d entries</item>
    </plurals>
</resources>
EOF
# Hand-written, not generated: its strings are not catalog keys.
cat > "$fg/app/src/main/res/values/colors.xml" << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="not_a_catalog_key">x</string>
</resources>
EOF
cat > "$work/sdk/build-tools/37.0.0/aapt2" << 'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FG_LOG"
case "$1 $2" in
"dump resources") cat "$FG_TABLE" ;;
"dump xmltree") cat "$FG_LOCALES" ;;
*) echo "stub aapt2: unhandled $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$work/sdk/build-tools/37.0.0/aapt2"
echo "not really an apk" > "$work/app.apk"
python3 -I -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("base/manifest/AndroidManifest.xml", "m"); z.writestr("base/resources.pb", "t"); z.close()' "$work/app.aab"

# fg_table <app_name configs> <sign_in_action configs> <entry_count configs> <date_format configs>
#          <not_a_catalog_key configs> [nolocale]
# Each configs argument is a space-separated list of extra configurations beside the default
# (`-` for none); app_name `missing` leaves it out of the table. AndroidX's own French is always
# there, as in every real bundle.
fg_table() {
    local config
    {
        echo "Package name=com.distronode.districtai id=7f"
        echo "  type plurals id=0c entryCount=1"
        echo "    resource 0x7f0c0000 plurals/entry_count"
        echo '      () (featureFlag=) (plurals) size=2 src=values.xml:1'
        echo "        one=\"%1\$d entry\""
        echo "        other=\"%1\$d entries\""
        for config in $3; do
            [ "$config" = - ] && continue
            echo "      ($config) (featureFlag=) (plurals) size=2 src=values.xml:2"
            echo "        one=\"%1\$d entree\""
        done
        echo "  type string id=0e entryCount=6"
        echo "    resource 0x7f0e0001 string/abc_action_bar_home_description"
        echo '      () "Navigate home" src=values.xml:3'
        echo '      (fr-rCA) "Revenir a l accueil" src=values-fr-rCA.xml:3'
        echo '      (fr) "Revenir a l accueil" src=values-fr.xml:3'
        if [ "$1" != missing ]; then
            echo "    resource 0x7f0e00c2 string/app_name"
            echo '      () "District AI" src=values.xml:447'
            for config in $1; do [ "$config" = - ] || echo "      ($config) \"District IA\" src=values.xml:9"; done
        fi
        echo "    resource 0x7f0e00c3 string/sign_in_action"
        echo '      () "Sign in" src=values.xml:448'
        for config in $2; do [ "$config" = - ] || echo "      ($config) \"Se connecter\" src=values.xml:10"; done
        echo "    resource 0x7f0e00c4 string/date_format_ymd"
        echo '      () "2026-12-31" src=values.xml:449'
        for config in $4; do [ "$config" = - ] || echo "      ($config) \"2026-12-31\" src=values.xml:11"; done
        echo "    resource 0x7f0e00c5 string/not_a_catalog_key"
        echo '      () "x" src=values.xml:450'
        for config in $5; do [ "$config" = - ] || echo "      ($config) \"x\" src=values.xml:12"; done
        if [ "${6:-}" != nolocale ]; then
            echo "  type xml id=11 entryCount=1"
            echo "    resource 0x7f110000 xml/_generated_res_locale_config"
            echo "      () (file) res/xml/_generated_res_locale_config.xml type=protoXML src=localeConfig:0"
            echo "      (v35) (file) res/xml-v35/_generated_res_locale_config.xml type=protoXML src=localeConfig:0"
        fi
    } > "$work/fg-table"
}
# fg_locales <language>...: the per-app language list as `aapt2 dump xmltree` prints it.
fg_locales() {
    local language
    {
        echo "N: android=http://schemas.android.com/apk/res/android (line=1)"
        echo "  E: locale-config (line=1)"
        echo '    A: http://schemas.android.com/apk/res/android:defaultLocale(0x01010688)="en-US" (Raw: "en-US")'
        for language in "$@"; do
            echo "      E: locale (line=2)"
            echo "        A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"$language\" (Raw: \"$language\")"
        done
    } > "$work/fg-locales"
}
guard() { # mode, artifact
    : > "$work/fg-log"
    env ANDROID_HOME="$work/sdk" FG_TABLE="$work/fg-table" FG_LOCALES="$work/fg-locales" \
        FG_LOG="$work/fg-log" "$fg/scripts/french-guard.sh" "$@"
}
said() { grep -q -- "$1" "$work/out"; }

echo "french-guard.sh absent"
fg_table - - - - -
fg_locales en-US
passes "English app keys pass, beside AndroidX's own French" guard absent "$work/app.apk"
check "  (it counts the app's keys, not AndroidX's or a hand-written file's)" said "4 of 4 app keys in the table, 0 with French"
check "  (both locale-config files were read)" grep -q "dump xmltree --file res/xml-v35/_generated_res_locale_config.xml" "$work/fg-log"
passes "a bundle passes the same way" guard absent "$work/app.aab"
check "  (aapt2 read the repacked base module)" grep -q "dump resources .*/base.apk" "$work/fg-log"
fg_table fr-rCA - - - -
refuses "an app string in fr-rCA fails" guard absent "$work/app.apk"
check "  (it names the key)" said "1 app keys have a French value: string/app_name"
fg_table - - fr - -
refuses "an app plural in plain fr fails" guard absent "$work/app.apk"
check "  (it names the plural)" said "plurals/entry_count"
fg_table - fr-rFR - - -
refuses "an app string in fr-rFR fails" guard absent "$work/app.apk"
check "  (it names the key)" said "string/sign_in_action"
fg_table - b+fr+CA - - -
refuses "an app string in b+fr+CA fails" guard absent "$work/app.apk"
fg_table - - - fr-rCA-v21 -
refuses "a non-translatable key with French (fr-rCA-v21) fails too" guard absent "$work/app.apk"
check "  (it names the key)" said "string/date_format_ymd"
fg_table - - - - fr-rCA
passes "a hand-written resource with French is not a catalog key" guard absent "$work/app.apk"
fg_table - - - - -
fg_locales en-US fr-CA
refuses "the per-app language list naming fr-CA fails" guard absent "$work/app.apk"
check "  (it names the list)" said "the per-app language list names fr-CA"
fg_locales en-US fr
refuses "the per-app language list naming plain fr fails" guard absent "$work/app.apk"
fg_locales en-US
fg_table - - - - - nolocale
refuses "no locale config at all fails rather than passing" guard absent "$work/app.apk"
check "  (it says what is missing)" said "has no xml/_generated_res_locale_config"
fg_table - - - - -
fg_locales
refuses "a locale config with no language read fails" guard absent "$work/app.apk"
fg_locales en-US
fg_table missing - - - -
refuses "a table without string/app_name fails" guard absent "$work/app.apk"
check "  (it says the table was not read)" said "string/app_name is not in"

echo "french-guard.sh present"
fg_table fr-rCA fr-rCA fr-rCA - -
fg_locales en-US fr-CA
passes "every translatable key in fr-rCA and fr-CA listed passes" guard present "$work/app.apk"
check "  (the non-translatable key needs no French)" said "French is present"
fg_table fr-rCA - fr-rCA - -
refuses "a translatable key without fr-rCA fails" guard present "$work/app.apk"
check "  (it names the key)" said "1 translatable app keys have no fr-rCA value: string/sign_in_action"
fg_table "fr-rCA fr" fr-rCA fr-rCA - -
refuses "a plain fr value beside fr-rCA fails" guard present "$work/app.apk"
check "  (it names the key)" said "app keys have a French value other than fr-rCA: string/app_name"
fg_table fr-rCA fr-rCA fr-rCA - -
fg_locales en-US
refuses "fr-CA missing from the per-app language list fails" guard present "$work/app.apk"
check "  (it names what the list holds)" said "does not name fr-CA (it names: en-US)"
fg_table - - - - -
refuses "an English bundle fails present" guard present "$work/app.apk"

refuses "an unknown mode is refused" guard maybe "$work/app.apk"
refuses "a missing artifact is refused" guard absent "$work/nothing.aab"

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
