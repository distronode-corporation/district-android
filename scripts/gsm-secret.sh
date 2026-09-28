#!/usr/bin/env bash
#
# Print the latest version of one Google Secret Manager secret to stdout, decoded, for release.yml.
#
#   GCP_TOKEN=<access token> scripts/gsm-secret.sh ANDROID_UPLOAD_KEY_ALIAS
#
# GCP_TOKEN is the short-lived token google-github-actions/auth mints for the `release`
# environment. It travels in a header file readable only by this user, never in curl's argv,
# where any process on the machine could read it. Only use this inside command substitution or
# with stdout redirected to a 0600 file: the output is the secret.
set -euo pipefail

[ $# -eq 1 ] || { echo "usage: $0 <SECRET_NAME>" >&2; exit 1; }
: "${GCP_TOKEN:?GCP_TOKEN must hold a Google Cloud access token}"
name=$1
project=${GSM_PROJECT:-distronode}
api=${GSM_API_BASE:-https://secretmanager.googleapis.com}

umask 077
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf 'Authorization: Bearer %s\n' "$GCP_TOKEN" > "$work/auth"

code=$(curl -sS -o "$work/body" -w '%{http_code}' -H "@$work/auth" \
    "$api/v1/projects/$project/secrets/$name/versions/latest:access")
if [ "$code" != "200" ]; then
    # The error body names the secret and the reason; it carries no secret material.
    echo "gsm-secret: reading $name answered HTTP $code" >&2
    cat "$work/body" >&2
    exit 1
fi

data=$(jq -r '.payload.data // empty' "$work/body")
[ -n "$data" ] || { echo "gsm-secret: $name has an empty payload." >&2; exit 1; }
printf '%s' "$data" | base64 -d
