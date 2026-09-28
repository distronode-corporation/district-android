#!/usr/bin/env bash
#
# Tests for the release scripts (release-version.sh, release-notes.sh, gsm-secret.sh,
# play-publish.sh), run by CI on every pull request. No network: curl is replaced by a stub that
# answers from fixtures and records every call, so the tests can assert what was sent, in what
# order, and what was NOT sent (no commit after a failure, no second submission, no token in argv).
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

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
