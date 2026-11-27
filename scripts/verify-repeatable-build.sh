#!/usr/bin/env sh

# SPDX-FileCopyrightText: Distronode Corporation
#
# SPDX-License-Identifier: Apache-2.0

#
# Build the UNSIGNED release APK twice from clean and check that the two are identical.
#
# What this proves: the same commit, on the same machine and toolchain, gives the same bytes.
# It does not prove that a different machine gives the same bytes; that is the next step.
#
# Both builds run with no signing variables, so the APK carries no signature block and the two
# files can be compared whole with `cmp`. No signature has to be stripped first; the script
# fails if one turns up. Each build starts from `clean`, skips the Gradle build cache and
# reruns every task, so the second build cannot reuse the first build's outputs.
#
# On a difference it prints `diffoscope` output when diffoscope is installed (CI installs it),
# and saves both APKs under build/repeatable/ either way.
#
# Expected differences: none. Nothing is allowlisted. If a difference must ever be accepted,
# list the zip entry here with the reason, and compare the APKs entry by entry instead of
# with `cmp`.
#
# POSIX shell, no Python, for the reason given in verify-release-minification.sh.
#
# Usage:  scripts/verify-repeatable-build.sh
set -u

cd "$(dirname "$0")/.." || exit 1

: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-amd64}"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME

OUT=build/repeatable
rm -rf "$OUT"
mkdir -p "$OUT"

build() {
  n=$1
  echo "== build $n: clean, unsigned :app:assembleRelease =="
  if ! ./gradlew --no-daemon --no-build-cache --no-configuration-cache --rerun-tasks \
      clean :app:assembleRelease -q >"$OUT/build-$n.log" 2>&1; then
    echo "build $n FAILED, see $OUT/build-$n.log"
    tail -20 "$OUT/build-$n.log"
    exit 1
  fi
  apk=$(find app/build/outputs/apk/release -name '*-unsigned.apk' 2>/dev/null | head -1)
  if [ -z "$apk" ]; then
    echo "build $n produced no unsigned release APK (is a signing variable set?)"
    ls -R app/build/outputs/apk/release 2>/dev/null
    exit 1
  fi
  cp "$apk" "$OUT/app-release-$n.apk"
  # A v2/v3 signature block sits before the zip central directory and is tagged with this magic.
  if grep -q 'APK Sig Block 42' "$OUT/app-release-$n.apk"; then
    echo "build $n is signed; this check needs an unsigned build"
    exit 1
  fi
  sha256sum "$OUT/app-release-$n.apk"
}

build 1
build 2

if cmp -s "$OUT/app-release-1.apk" "$OUT/app-release-2.apk"; then
  echo "PASS: the two unsigned release APKs are byte for byte identical"
  exit 0
fi

echo "FAIL: the two unsigned release APKs differ"
if command -v diffoscope >/dev/null 2>&1; then
  diffoscope --text "$OUT/diffoscope.txt" "$OUT/app-release-1.apk" "$OUT/app-release-2.apk"
  head -200 "$OUT/diffoscope.txt"
else
  echo "diffoscope is not installed; first differing bytes:"
  cmp "$OUT/app-release-1.apk" "$OUT/app-release-2.apk" | head -5
fi
exit 1
