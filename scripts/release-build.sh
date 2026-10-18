#!/usr/bin/env bash
#
# Build the signed release bundle in release.yml, with the upload key and the Sentry values
# borrowed from Google Secret Manager for this one run.
#
#   GCP_TOKEN=<token> VERSION_CODE=<n> VERSION_NAME=<name> DISTRICT_FRENCH=false scripts/release-build.sh
#
# Output: app/build/outputs/bundle/release/app-release.aab and
# app/build/outputs/mapping/release/mapping.txt.
#
# What is read, and where it goes:
#   ANDROID_UPLOAD_KEYSTORE           base64 of the PKCS12 upload keystore; decoded into a 0600
#                                     file under RUNNER_TEMP that is deleted when this exits
#   ANDROID_UPLOAD_KEYSTORE_PASSWORD  environment of this script and Gradle only
#   ANDROID_UPLOAD_KEY_ALIAS          environment of this script and Gradle only
#   ANDROID_SENTRY_DSN                the DSN baked into the app (public client configuration)
#   SENTRY_EU_AUTH_TOKEN              SENTRY_AUTH_TOKEN, so the Sentry plugin uploads the mapping
#                                     and the source bundle, and sentry-cli creates the release
# Each value is masked in the job log before anything else can print it, and GCP_TOKEN is dropped
# from the environment before Gradle starts, so no build plugin can see the credential that
# reads these.
#
# ⛔ THE KEY IS CHECKED BEFORE THE BUILD, NOT AFTER. A wrong password or alias otherwise fails at
# the signing task, twenty minutes in, after R8. keytool reads the password from the environment
# (`-storepass:env`), never from argv.
#
# ⛔ DISTRICT_FRENCH (release.yml) IS REQUIRED, `true` OR `false`, AND IS THE ONE FRENCH SWITCH.
# Its value is passed to Gradle as is: `true` passes -Pdistrict.l10n.fr=true, which builds
# src/l10n-fr/res in; `false` passes -Pdistrict.l10n.fr=false, which leaves it out (French is on
# by default since 3.0, so leaving the property off would build French in).
# Anything else, or nothing, is refused before a secret is read, because a release whose language
# nobody chose is not one to build. release.yml's French guard reads the same value.
#
# ── The Sentry release, with its commits ────────────────────────────────────────────────────────
# After the build, the release this bundle reports as gets HEAD's commit (sentry-cli releases new,
# set-commits, finalize), which is what gives an issue its suspect commits and "fixed in release";
# Sentry fetches the range since the previous release from GitHub. ⛔ THE NAME IS THE SDK'S
# DEFAULT, `<applicationId>@<versionName>+<versionCode>`, from the values this build was given:
# VERSION_CODE (forced into the bundle by -PdistrictVersionCode), VERSION_NAME (release-version.sh,
# which reads app/build.gradle.kts) and the one applicationId line in app/build.gradle.kts. A name
# one character off is a second, empty release that no event lands in. sentry-cli takes its host,
# org and project from the DISTRICT_SENTRY_* values release.yml gives the plugin.
# ⚠️ NON-FATAL. A crash still deobfuscates without the release; it only loses its commits, so a
# failure is a `::warning::` and the bundle goes on to Play.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
    echo "release-build: $1" >&2
    exit 1
}

: "${GCP_TOKEN:?GCP_TOKEN must hold a Google Cloud access token}"
: "${VERSION_CODE:?VERSION_CODE must be set (scripts/release-version.sh)}"
case "${DISTRICT_FRENCH:-}" in
true) french=(-Pdistrict.l10n.fr=true) ;;
false) french=(-Pdistrict.l10n.fr=false) ;;
*) fail "DISTRICT_FRENCH is '${DISTRICT_FRENCH:-}', not true or false." ;;
esac

secret() {
    local value
    value=$(scripts/gsm-secret.sh "$1") || exit 1
    [ -n "$value" ] || fail "$1 is empty."
    printf '%s' "$value"
}

# Called at the top level, never inside $(...), where the mask command would be captured into
# the variable instead of reaching the runner.
mask() {
    if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::add-mask::$1"; fi
}

umask 077
tmp=${RUNNER_TEMP:-$(mktemp -d)}
keystore="$tmp/upload-keystore.p12"
trap 'rm -f "$keystore"' EXIT

keystore_b64=$(secret ANDROID_UPLOAD_KEYSTORE)
ANDROID_UPLOAD_KEYSTORE_PASSWORD=$(secret ANDROID_UPLOAD_KEYSTORE_PASSWORD)
ANDROID_UPLOAD_KEY_ALIAS=$(secret ANDROID_UPLOAD_KEY_ALIAS)
DISTRICT_SENTRY_DSN=$(secret ANDROID_SENTRY_DSN)
SENTRY_AUTH_TOKEN=$(secret SENTRY_EU_AUTH_TOKEN)
unset GCP_TOKEN
for value in "$keystore_b64" "$ANDROID_UPLOAD_KEYSTORE_PASSWORD" "$ANDROID_UPLOAD_KEY_ALIAS" \
    "$DISTRICT_SENTRY_DSN" "$SENTRY_AUTH_TOKEN"; do
    mask "$value"
done
unset value

printf '%s' "$keystore_b64" | base64 -d > "$keystore" || fail "ANDROID_UPLOAD_KEYSTORE is not valid base64."
unset keystore_b64
ANDROID_UPLOAD_KEYSTORE_PATH=$keystore
export ANDROID_UPLOAD_KEYSTORE_PATH ANDROID_UPLOAD_KEYSTORE_PASSWORD ANDROID_UPLOAD_KEY_ALIAS
export DISTRICT_SENTRY_DSN SENTRY_AUTH_TOKEN

keytool -list -keystore "$keystore" -storetype PKCS12 -storepass:env ANDROID_UPLOAD_KEYSTORE_PASSWORD \
    -alias "$ANDROID_UPLOAD_KEY_ALIAS" > /dev/null ||
    fail "the upload keystore does not open with its password, or has no key under its alias."

# --no-configuration-cache: the cache would write this build's configuration, signing values
# included, to disk under .gradle/, and a release gains nothing from it. -PdistrictVersionCode
# pins the number release-version.sh computed, which is also the one submit.yml will look for.
./gradlew :app:bundleRelease --no-daemon --no-configuration-cache \
    "-PdistrictVersionCode=$VERSION_CODE" ${french[@]+"${french[@]}"}

aab=app/build/outputs/bundle/release/app-release.aab
mapping=app/build/outputs/mapping/release/mapping.txt
[ -s "$aab" ] || fail "no bundle at $aab."
[ -s "$mapping" ] || fail "no R8 mapping at $mapping."
# An unsigned bundle is a supported local state (see the signing block in build-logic), so prove
# this one carries a signature rather than finding out from Play.
unzip -l "$aab" | grep -Eq 'META-INF/[^/]+\.(RSA|EC|DSA)$' || fail "$aab carries no signature."
echo "Built $aab (versionCode $VERSION_CODE), signed, with its mapping."

sentry_release() {
    local application_id release sha value
    [ -n "${VERSION_NAME:-}" ] || { echo "release-build: VERSION_NAME is unset." >&2; return 1; }
    for value in "${DISTRICT_SENTRY_URL:-}" "${DISTRICT_SENTRY_ORG:-}" "${DISTRICT_SENTRY_PROJECT:-}"; do
        [ -n "$value" ] || { echo "release-build: a DISTRICT_SENTRY_* value is unset." >&2; return 1; }
    done
    command -v sentry-cli > /dev/null || { echo "release-build: sentry-cli is not on PATH." >&2; return 1; }
    application_id=$(sed -n 's/^[[:space:]]*applicationId = "\([^"]*\)"[[:space:]]*$/\1/p' app/build.gradle.kts 2> /dev/null) ||
        application_id=""
    [ -n "$application_id" ] && [ "$(printf '%s\n' "$application_id" | wc -l | tr -d ' ')" = "1" ] || {
        echo "release-build: not exactly one applicationId = \"...\" line in app/build.gradle.kts." >&2
        return 1
    }
    release="$application_id@$VERSION_NAME+$VERSION_CODE"
    sha=$(git rev-parse HEAD) || return 1
    echo "Sentry release: $release (commit $sha)"
    SENTRY_URL=$DISTRICT_SENTRY_URL sentry-cli releases new --org "$DISTRICT_SENTRY_ORG" \
        --project "$DISTRICT_SENTRY_PROJECT" "$release" &&
        SENTRY_URL=$DISTRICT_SENTRY_URL sentry-cli releases set-commits --org "$DISTRICT_SENTRY_ORG" \
            "$release" --commit "distronode-corporation/district-android@$sha" --ignore-missing &&
        SENTRY_URL=$DISTRICT_SENTRY_URL sentry-cli releases finalize --org "$DISTRICT_SENTRY_ORG" "$release"
}
sentry_release ||
    echo "::warning::the Sentry release for versionCode $VERSION_CODE was not created or has no commits; crashes still deobfuscate, but carry no suspect commits."
