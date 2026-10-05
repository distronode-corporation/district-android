#!/usr/bin/env bash
#
# Print the Google Play release notes for one version, per language.
#
#   scripts/release-notes.sh 2.1               # en-US: the version's CHANGELOG.md section
#   scripts/release-notes.sh 2.1 fr-CA         # another language: release-notes/fr-CA/2.1.txt
#   scripts/release-notes.sh 2.1 --dir <out>   # every language, as <out>/<language>.txt
#
# en-US is the version's CHANGELOG.md section, everything under `## [<version>] - <date>` up to the
# next `## ` heading. Markdown is flattened because Play shows release notes as plain text: `### `
# headings and link reference lines are dropped, wrapped bullet lines are joined, `- ` bullets
# become `• `, and backticks and link targets are removed.
#
# Every other language is a committed plain-text file, release-notes/<language>/<version>.txt
# (the Play language code, for example fr-CA), written exactly as the store shows it. `--dir`
# writes en-US plus one file for every language directory under release-notes/, which is what
# the submission sends (scripts/play-publish.sh submit).
#
# ⛔ IT FAILS RATHER THAN TRUNCATES OR FALLS BACK. Play accepts at most 500 characters of release
# notes per language and rejects the whole release above that. A cut-off sentence in the store is
# worse than a failed check, and so is English shown to a French listing, so a section or file
# that is missing, empty or too long, in any language, stops here. release.yml runs `--dir` on a
# tag before it builds anything, so the problem shows up an hour before submission, not at it.
set -euo pipefail

cd "$(dirname "$0")/.."

usage() { echo "usage: $0 <versionName> [<language> | --dir <out>]" >&2; exit 1; }
fail() { echo "release-notes: $1" >&2; exit 1; }

[ $# -ge 1 ] && [ $# -le 3 ] || usage
version=$1
changelog=${CHANGELOG:-CHANGELOG.md}
notes_root=${RELEASE_NOTES_DIR:-release-notes}
max=500

# The en-US notes: the version's CHANGELOG section, flattened.
changelog_notes() {
    local notes
    notes=$(awk -v want="## [$version]" '
    function emit(text) {
        # Collapse runs of blank lines, and drop blanks before the first line of text.
        if (text == "") { if (n > 0 && out[n - 1] != "") out[n++] = ""; return }
        out[n++] = text
    }
    # The version heading starts the section; any later level-2 heading ends it.
    index($0, want) == 1 { inside = 1; next }
    inside && /^## / { exit }
    inside && /^### / { next }
    inside && /^\[[^]]*\]: / { next }
    inside {
        line = $0
        gsub(/`/, "", line)
        # [text](target) becomes text.
        while (match(line, /\[[^]]*\]\([^)]*\)/)) {
            inner = substr(line, RSTART + 1, RLENGTH - 2)
            sub(/\]\(.*/, "", inner)
            line = substr(line, 1, RSTART - 1) inner substr(line, RSTART + RLENGTH)
        }
        if (line ~ /^- /) {
            if (buf != "") emit(buf)
            buf = "• " substr(line, 3)
        } else if (line ~ /^[[:space:]]*$/) {
            if (buf != "") emit(buf)
            buf = ""
            emit("")
        } else if (buf != "") {
            sub(/^[[:space:]]+/, "", line)
            buf = buf " " line
        } else {
            buf = line
        }
    }
    END {
        if (buf != "") emit(buf)
        while (n > 0 && out[n - 1] == "") n--
        for (i = 0; i < n; i++) print out[i]
    }
' "$changelog")
    [ -n "$notes" ] ||
        fail "$changelog has no section for $version, or it is empty. Add \"## [$version] - YYYY-MM-DD\" with the notes users should see."
    printf '%s' "$notes"
}

# Another language's notes: its committed file, trailing blank lines dropped. Never English instead.
file_notes() { # language
    local file="$notes_root/$1/$version.txt" notes
    [ -f "$file" ] ||
        fail "$file is missing. Every language under $notes_root/ needs its own notes for $version; none is filled in from English."
    notes=$(cat "$file")
    [ -n "${notes//[[:space:]]/}" ] || fail "$file is empty."
    printf '%s' "$notes"
}

notes_for() { # language
    local notes chars
    if [ "$1" = en-US ]; then notes=$(changelog_notes); else notes=$(file_notes "$1"); fi
    # Characters, not bytes: count every byte that does not continue a UTF-8 sequence. Portable
    # across GNU and BSD tools, which disagree on `wc -m` without a UTF-8 locale.
    chars=$(printf '%s' "$notes" | LC_ALL=C tr -d '\200-\277' | wc -c | tr -d ' ')
    [ "$chars" -le "$max" ] ||
        fail "the $version notes for $1 are $chars characters as plain text; Google Play accepts at most $max. Shorten them."
    printf '%s\n' "$notes"
}

case "${2:-}" in
    "")
        [ $# -eq 1 ] || usage
        notes_for en-US
        ;;
    --dir)
        [ $# -eq 3 ] || usage
        out=$3
        # Every language is read and checked before any file is written, so a failure leaves no
        # partial set (and no empty file) behind.
        languages="en-US"
        if [ -d "$notes_root" ]; then
            for dir in "$notes_root"/*/; do
                [ -d "$dir" ] && languages="$languages $(basename "$dir")"
            done
        fi
        for language in $languages; do
            notes_for "$language" > /dev/null
        done
        mkdir -p "$out"
        for language in $languages; do
            notes_for "$language" > "$out/$language.txt"
        done
        written=$languages
        echo "Release notes for $version: $written" >&2
        ;;
    -*) usage ;;
    *)
        [ $# -eq 2 ] || usage
        printf '%s' "$2" | grep -Eq '^[a-z]{2,3}(-[A-Z]{2})?$' || fail "'$2' is not a Play language code (en-US, fr-CA)."
        notes_for "$2"
        ;;
esac
