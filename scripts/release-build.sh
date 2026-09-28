#!/usr/bin/env bash
#
# Build the signed release bundle in release.yml, with the upload key and the Sentry values
# borrowed from Google Secret Manager for this one run.
#
#   GCP_TOKEN=<token> VERSION_CODE=<n> scripts/release-build.sh
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
# Each value is masked in the job log before anything else can print it, and GCP_TOKEN is dropped
# from the environment before Gradle starts, so no build plugin can see the credential that
# reads these.
#
# ⛔ THE KEY IS CHECKED BEFORE THE BUILD, NOT AFTER. A wrong password or alias otherwise fails at
# the signing task, twenty minutes in, after R8. keytool reads the password from the environment
# (`-storepass:env`), never from argv.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
    echo "release-build: $1" >&2
    exit 1
}

: "${GCP_TOKEN:?GCP_TOKEN must hold a Google Cloud access token}"
: "${VERSION_CODE:?VERSION_CODE must be set (scripts/release-version.sh)}"

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
    "-PdistrictVersionCode=$VERSION_CODE"

aab=app/build/outputs/bundle/release/app-release.aab
mapping=app/build/outputs/mapping/release/mapping.txt
[ -s "$aab" ] || fail "no bundle at $aab."
[ -s "$mapping" ] || fail "no R8 mapping at $mapping."
# An unsigned bundle is a supported local state (see the signing block in build-logic), so prove
# this one carries a signature rather than finding out from Play.
unzip -l "$aab" | grep -Eq 'META-INF/[^/]+\.(RSA|EC|DSA)$' || fail "$aab carries no signature."
echo "Built $aab (versionCode $VERSION_CODE), signed, with its mapping."
