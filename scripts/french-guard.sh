#!/usr/bin/env bash
#
# Is French in this build? Checks an .aab or .apk for the app's OWN strings in fr-rCA.
#
#   scripts/french-guard.sh absent  app/build/outputs/bundle/release/app-release.aab   (release.yml)
#   scripts/french-guard.sh present app/build/outputs/apk/debug/app-debug.apk          (the French CI job)
#
# `absent` fails when ANY of the keys below has an fr-rCA value; `present` fails when NONE does.
# (`present` asks only whether the French resources were built in at all: until W3 most app strings
# have no French yet, while l10n_language and the error-code sentences already do.)
#
# ⛔ "ANY fr-rCA CONFIGURATION" IS NOT THE TEST, AND WOULD FAIL EVERY BUILD THIS APP HAS EVER MADE.
# AndroidX ships its own fr-rCA strings (119 of them when this was written), so the configuration is
# in every bundle whether or not a word of OUR French is. The question is whether the app's own keys
# resolve to French, so that is what is asked. French is built in only with -Pdistrict.l10n.fr (see
# app/build.gradle.kts), which no release build passes until the 3.0 cut.
#
# Reads the resource table with aapt2 from build-tools 37.0.0 (installed by the workflow). A bundle
# keeps its table as protobuf under base/, which aapt2 reads once that module is repacked as a zip
# with the table at its root.
set -euo pipefail

usage() {
    echo "usage: scripts/french-guard.sh absent|present <app.aab|app.apk>" >&2
    exit 2
}

[ $# -eq 2 ] || usage
mode=$1
artifact=$2
case "$mode" in absent | present) ;; *) usage ;; esac
[ -f "$artifact" ] || { echo "french-guard: $artifact does not exist" >&2; exit 1; }

# App-owned keys, one per catalog and the first string every user sees. A key here must exist in
# l10n/*.toml; one that is missing from the table altogether fails too, rather than reading as
# "no French".
KEYS="app_name sign_in_action failure_offline push_call_title l10n_language api_error_unknown"

aapt2="${ANDROID_HOME:?ANDROID_HOME must be set}/build-tools/37.0.0/aapt2"
[ -x "$aapt2" ] || { echo "french-guard: $aapt2 is not installed" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

table_source=$artifact
case "$artifact" in
*.aab)
    unzip -q "$artifact" 'base/*' -d "$work/aab"
    # aapt2 wants the (protobuf) manifest at the root, where an APK keeps it.
    mv "$work/aab/base/manifest/AndroidManifest.xml" "$work/aab/base/AndroidManifest.xml"
    (cd "$work/aab/base" && zip -q -r "$work/base.apk" .)
    table_source=$work/base.apk
    ;;
esac

"$aapt2" dump resources "$table_source" > "$work/table.txt"

status=0
found=""
for key in $KEYS; do
    # The resource's block: its header line, then one line per configuration, until the next resource.
    block=$(awk -v key="string/$key" '
        $1 == "resource" { inside = ($3 == key) ; next }
        inside && /^[[:space:]]*\(/ { print }
    ' "$work/table.txt")
    if [ -z "$block" ]; then
        echo "french-guard: string/$key is not in $artifact" >&2
        status=1
        continue
    fi
    if grep -q '^[[:space:]]*(fr-rCA)' <<<"$block"; then
        found="$found $key"
    fi
done

if [ "$mode" = absent ] && [ -n "$found" ]; then
    echo "french-guard: these app strings have an fr-rCA value in $artifact:$found. French must not ship before the 3.0 cut." >&2
    status=1
elif [ "$mode" = present ] && [ -z "$found" ]; then
    echo "french-guard: none of $KEYS has an fr-rCA value in $artifact; the French resources are not in this build." >&2
    status=1
fi

if [ $status -eq 0 ]; then
    echo "french-guard: French is $mode in $artifact (fr-rCA among the app's own keys:${found:- none})"
fi
exit $status
