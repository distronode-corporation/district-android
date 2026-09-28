#!/usr/bin/env sh
#
# Verify that the MINIFIED release artifact is actually usable.
#
# ⛔ WHY THIS EXISTS. `assembleDebug` does not minify, so every R8 problem is invisible locally and
# surfaces as a crash in a Play track. Worse, `assembleRelease` merely SUCCEEDING proves very little:
# R8 happily strips a serializer it cannot see being used, and the build stays green right up until a
# response fails to decode on a user's phone.
#
# This repo ran for ten tasks with R8 enabled, kotlinx.serialization in use, and a proguard-rules.pro
# whose comment still said "Empty for now on purpose: nothing uses serialization". `assembleRelease`
# had never been run.
#
# ⛔ WHAT THIS CHECKS, AND WHAT IT CANNOT. It asserts that every REFERENCED @Serializable model has a
# generated serializer retained by R8. That is a static check and it is the strongest one available
# without a signed-in device: a full end-to-end decode under R8 needs a real session, and the login leg
# needs a browser the emulator does not have. Treat a green run as "the serializers survived", NOT as
# "the release build is fully exercised".
#
# ⛔ POSIX SHELL, NO PYTHON, DELIBERATELY. The script has to run anywhere a JDK and the Android SDK
# do, including a minimal build container with nothing else installed, so a python3 heredoc here
# could pass on a workstation and fail on the next machine. Keep this file free of it.
#
# Usage:  scripts/verify-release-minification.sh
set -u

cd "$(dirname "$0")/.." || exit 1

: "${JAVA_HOME:=/usr/lib/jvm/java-21-openjdk-amd64}"
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export JAVA_HOME ANDROID_HOME

FAILURES=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '  \033[33mNOTE\033[0m %s\n' "$1"; }

echo "== building the release artifact (R8 on) =="
# ⚠️ Not --no-configuration-cache here: CI runs this after a full build in the same invocation chain,
# and the flag is only needed locally alongside --write-locks.
if ./gradlew --no-daemon :app:assembleRelease -q >/tmp/verify-release.log 2>&1; then
  pass "assembleRelease succeeded"
else
  fail "assembleRelease FAILED — see /tmp/verify-release.log"
  tail -20 /tmp/verify-release.log
  exit 1
fi

APK=$(find app/build/outputs/apk/release -name '*.apk' 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  fail "no release APK produced"
  exit 1
fi
printf '  artifact: %s (%s)\n' "$APK" "$(du -h "$APK" | cut -f1)"

# ── The Geist licence text ships beside the fonts ────────────────────────────
#
# ⛔ The OFL requires its text to accompany every copy of the font files, and the APK carries them.
# Nothing reads res/raw/license_geist.txt, so without its tools:keep (res/raw/keep_geist_license.xml
# in core-designsystem) the resource shrinker empties it and the build stays green. Searched by
# content across every res/ entry rather than by path, because resource shrinking may rename paths.
GEIST_OFL=$(unzip -p "$APK" 'res/*' 2>/dev/null | grep -a -c 'SIL OPEN FONT LICENSE Version 1.1')
if [ "${GEIST_OFL:-0}" -gt 0 ]; then
  pass "the Geist licence text is in the release APK"
else
  fail "the Geist licence text is NOT in the release APK (was res/raw/license_geist.txt shrunk away?)"
fi

# ── Collect the classes R8 RETAINED, from the mapping file ───────────────────
#
# ⛔ READ THE MAPPING, NOT THE DEX. R8 RENAMES everything, so the original fully-qualified names are
# simply absent from the dex — searching it for `com.…$$serializer` reports zero matches even when all
# the serializers are present, which is exactly the false alarm the first version of this script
# produced. mapping.txt lists `original -> obfuscated` for every class R8 KEPT, so the left-hand side
# is the authoritative "did this survive" answer.
MAPPING=app/build/outputs/mapping/release/mapping.txt
if [ ! -f "$MAPPING" ]; then
  fail "no mapping.txt — cannot tell what R8 retained"
  exit 1
fi

WORK=$(mktemp -d)
# ⚠️ mktemp rather than a fixed path: a fixed directory would collide between concurrent runs.
trap 'rm -rf "$WORK"' EXIT

# Class lines start at column 0 and end with ':'; member lines are indented.
grep -E '^[^ ].* -> .*:$' "$MAPPING" | sed 's/ -> .*//' | sort -u > "$WORK/retained.txt"
printf '  %s classes retained by R8\n' "$(wc -l < "$WORK/retained.txt" | tr -d ' ')"

# ── Enumerate every @Serializable class in the shipped sources ───────────────
#
# Matches a class declaration on a line following an @Serializable annotation. `grep -A3` covers the
# annotation, an optional blank line, and modifiers before `class`.
: > "$WORK/serializable.txt"
for dir in core/core-model/src/main/kotlin core/core-network/src/main/kotlin; do
  [ -d "$dir" ] || continue
  for file in $(find "$dir" -name '*.kt'); do
    pkg=$(sed -n 's/^package  *\([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -1)
    [ -n "$pkg" ] || continue
    # ⚠️ The comment filter is load-bearing. A KDoc line within the -A3 window that happens
    # to contain the words "class <word>" — e.g. "See the class header." — otherwise mints a
    # phantom class, and the reference scan then fails the build on a name that exists only
    # in prose. Fired for real on BillingResponses.kt's StripeBilling doc.
    grep -A3 -E '^\s*@(kotlinx\.serialization\.)?Serializable\s*$' "$file" \
      | grep -v '^[[:space:]]*[*/]' \
      | sed -n 's/^.*[^A-Za-z0-9_]class  *\([A-Za-z0-9_]*\).*/\1/p' \
      | while read -r cls; do
          [ -n "$cls" ] && printf '%s.%s|%s\n' "$pkg" "$cls" "$file" >> "$WORK/serializable.txt"
        done
  done
done
sort -u "$WORK/serializable.txt" -o "$WORK/serializable.txt"

DECLARED=$(wc -l < "$WORK/serializable.txt" | tr -d ' ')
if [ "$DECLARED" -eq 0 ]; then
  # ⛔ A scan that finds nothing would make this whole script pass by verifying nothing — the exact
  # failure shape the unit-test-count guard in this pipeline exists to prevent.
  fail "found no @Serializable classes to check — the source scan is broken"
  exit 1
fi

# ── Is a stripped DTO's reference itself reachable from the app? ────────────
#
# ⛔ A REFERENCE ONLY COUNTS IF SOMETHING THE APP RUNS CAN REACH IT. Treating any mention outside the
# DTO's own file as "used" fails on DTOs whose only reference is a transport ported for endpoint
# parity that no screen calls yet (e.g. `HttpContactBlockingApi`): R8 removes the transport and its
# DTOs together, correctly, and a naive check reports that as a stripped serializer.
#
# ⚠️ WHY NOT ASK mapping.txt WHETHER THE REFERRER SURVIVED. R8 inlines and merges classes that ARE in
# use, so a live class can be absent from the mapping under its own name: `HttpPersonaApi`, which
# AppContainer constructs, is absent from that same mapping. So the walk is static: from the files
# that name the DTO, follow "which files name a type this file declares" until app/src/main is
# reached or there is nothing left to follow.
#
# ⚠️ IT ERRS TOWARDS "REACHABLE", SO A MISTAKE IS A FALSE FAIL, NOT A SILENT PASS, in every case it
# can see: a file that declares a top-level function or property, or declares no type at all, stops
# the walk as reachable, because a caller of a top-level function is not found by a type-name search.
# The case it cannot see is a type used without ever being named, which Kotlin inference permits; a
# DTO reached only that way would be reported as a NOTE.

# Files other than $2 that name the word $1 in CODE. Comment lines are dropped: a KDoc link such as
# `[ContactBlockingApi]` in another file is not a use, and counting it would make any documented type
# reachable.
code_mentions() {
  grep -rnw --include='*.kt' -- "$1" app/src/main core 2>/dev/null \
    | grep -v '/build/' | grep -v '/src/test/' | grep -v '/src/androidTest/' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' \
    | cut -d: -f1 | sort -u | grep -vxF "$2"
}

# The top-level classes, interfaces and objects a file declares (column 0, after any annotations and
# modifiers). Nested types are reached through their outer type's name.
top_level_types() {
  sed -n -E 's/^(@[A-Za-z0-9_.]+(\([^)]*\))?[[:space:]]+)*((public|internal|private|protected|open|abstract|sealed|data|enum|annotation|value|inline|fun|expect|actual)[[:space:]]+)*(class|interface|object)[[:space:]]+([A-Za-z0-9_]+).*/\6/p' "$1"
}

# Does the file declare a top-level function or property? `fun interface` is a type, not a function.
has_top_level_callable() {
  grep -E '^(@[A-Za-z0-9_.]+(\([^)]*\))?[[:space:]]+)*((public|internal|private|inline|suspend|operator|infix|tailrec|external|const|lateinit)[[:space:]]+)*(fun|val|var)[[:space:]]' "$1" \
    | grep -vqE '^[^(]*fun[[:space:]]+interface[[:space:]]'
}

# Breadth-first from the files in $1 (newline-separated). Prints the first file that counts as
# reachable and succeeds, or prints nothing and fails.
first_reachable_file() {
  printf '%s\n' "$1" > "$WORK/queue"
  : > "$WORK/seen"
  while [ -s "$WORK/queue" ]; do
    f=$(head -n 1 "$WORK/queue")
    tail -n +2 "$WORK/queue" > "$WORK/queue.next"
    mv "$WORK/queue.next" "$WORK/queue"
    [ -n "$f" ] || continue
    if grep -qxF "$f" "$WORK/seen"; then continue; fi
    printf '%s\n' "$f" >> "$WORK/seen"
    case "$f" in
      app/src/main/*) printf '%s\n' "$f"; return 0 ;;
    esac
    if has_top_level_callable "$f"; then printf '%s\n' "$f"; return 0; fi
    types=$(top_level_types "$f")
    if [ -z "$types" ]; then printf '%s\n' "$f"; return 0; fi
    for t in $types; do
      code_mentions "$t" "$f" >> "$WORK/queue"
    done
  done
  return 1
}

echo
echo "== @Serializable models vs what R8 retained =="

PRESENT=0
DEAD=0
while IFS='|' read -r fqcn file; do
  [ -n "$fqcn" ] || continue
  # The generated serializer is the thing that must survive — that is what decodes the response. A
  # surviving Companion (which exposes serializer()) or the class itself is also acceptable evidence,
  # since the explicit `Foo.serializer()` calls this app makes resolve through it.
  if grep -qxF "$fqcn\$\$serializer" "$WORK/retained.txt" 2>/dev/null ||
     grep -qxF "$fqcn\$Companion" "$WORK/retained.txt" 2>/dev/null ||
     grep -qxF "$fqcn" "$WORK/retained.txt" 2>/dev/null; then
    PRESENT=$((PRESENT + 1))
    continue
  fi

  # ⛔ A STRIPPED SERIALIZER IS ONLY A BUG IF THE DTO IS ACTUALLY USED. R8 removes unreachable code by
  # design, so a DTO no screen references yet is *correctly* absent — flagging that as a failure would
  # train everyone to ignore this script. So look for a reference outside the DTO's own file, and only
  # fail when something that IS used lost its serializer.
  cls=$(printf '%s' "$fqcn" | sed 's/.*\.//')
  refs=$(code_mentions "$cls" "$file")

  if [ -z "$refs" ]; then
    # ⚠️ Not a failure. It does mean the DTO is currently unreachable, which is worth knowing: either
    # its screen has not been built yet, or it is genuinely dead and should go.
    note "$fqcn is unreferenced, so R8 stripped it correctly (its feature is not wired up yet)"
    DEAD=$((DEAD + 1))
  elif reached=$(first_reachable_file "$refs"); then
    fail "$fqcn IS referenced but its serializer was stripped ($file)"
    printf '        first reference: %s\n' "$(printf '%s\n' "$refs" | head -n 1)"
    printf '        reachable through: %s\n' "$reached"
  else
    # ⚠️ Not a failure either: everything that names this DTO is itself unreachable, so R8 removed
    # them together. Typically a transport ported ahead of its screen.
    note "$fqcn is referenced only from code the app never reaches ($(printf '%s' "$refs" | tr '\n' ' ')), so R8 stripped it correctly"
    DEAD=$((DEAD + 1))
  fi
done < "$WORK/serializable.txt"

printf '  %s @Serializable classes declared; %s retained; %s correctly stripped as unreachable\n' \
  "$DECLARED" "$PRESENT" "$DEAD"

if [ "$FAILURES" -eq 0 ]; then
  pass "every REFERENCED @Serializable model kept its serializer"
else
  echo
  echo "  ⛔ A REFERENCED DTO WITHOUT A SERIALIZER FAILS TO DECODE IN RELEASE ONLY."
  echo "     Add a keep rule to app/proguard-rules.pro rather than removing the DTO."
fi

# ── The attributes serialization and crash reporting depend on ───────────────
echo
echo "== attributes and rules =="
if grep -q 'RuntimeVisibleAnnotations' app/proguard-rules.pro; then
  pass "RuntimeVisibleAnnotations is kept (needed for @SerialName, e.g. CallFollowUp.sms)"
else
  fail "RuntimeVisibleAnnotations is NOT kept — @SerialName would be lost and fields renamed silently"
fi

if grep -q 'SourceFile,LineNumberTable' app/proguard-rules.pro; then
  pass "line numbers kept, so release stack traces stay readable"
else
  note "line numbers are not kept; release crash reports will be hard to read"
fi

SER=$(grep -c '\$\$serializer' "$MAPPING" 2>/dev/null || echo 0)
printf '  %s serializer entries in mapping.txt\n' "$SER"
# ⛔ The mapping file is what makes a release stack trace readable. It is NOT committed and is
# regenerated per build, so it must be retained alongside any artifact that ships.
note "retain $MAPPING with any uploaded artifact or its crash reports are unreadable"

echo
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32mRELEASE MINIFICATION VERIFIED\033[0m\n'
  echo "⚠️  Static only. A full decode under R8 still needs a signed-in device."
else
  printf '\033[31mFAILURES: %s\033[0m\n' "$FAILURES"
fi
exit "$FAILURES"
