#!/usr/bin/env bash
#
# Print the Google Play release notes for one version: its CHANGELOG.md section, as plain text.
#
#   scripts/release-notes.sh 1.1
#
# The section is everything under `## [<version>] - <date>` up to the next `## ` heading. Markdown
# is flattened because Play shows release notes as plain text: `### ` headings and link reference
# lines are dropped, wrapped bullet lines are joined, `- ` bullets become `• `, and backticks and
# link targets are removed.
#
# ⛔ IT FAILS RATHER THAN TRUNCATES. Play accepts at most 500 characters of release notes per
# language and rejects the whole release above that. A cut-off sentence in the store is worse than
# a failed check, so a section that is missing, empty or too long stops here, and release.yml runs
# this on a tag before it builds anything so the problem shows up an hour before submission, not
# at it.
set -euo pipefail

cd "$(dirname "$0")/.."

[ $# -eq 1 ] || { echo "usage: $0 <versionName>" >&2; exit 1; }
version=$1
changelog=${CHANGELOG:-CHANGELOG.md}
max=500

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

[ -n "$notes" ] || {
    echo "release-notes: $changelog has no section for $version, or it is empty. Add \"## [$version] - YYYY-MM-DD\" with the notes users should see." >&2
    exit 1
}

# Characters, not bytes: count every byte that does not continue a UTF-8 sequence. Portable
# across GNU and BSD tools, which disagree on `wc -m` without a UTF-8 locale.
chars=$(printf '%s' "$notes" | LC_ALL=C tr -d '\200-\277' | wc -c | tr -d ' ')
[ "$chars" -le "$max" ] || {
    echo "release-notes: the $version section is $chars characters as plain text; Google Play accepts at most $max. Shorten it in $changelog." >&2
    exit 1
}

printf '%s\n' "$notes"
