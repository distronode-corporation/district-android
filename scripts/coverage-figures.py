#!/usr/bin/env python3
"""Print the measured line and branch coverage from Kover's aggregate XML report.

    python3 scripts/coverage-figures.py build/reports/kover/reportUnit.xml

`koverVerifyUnit` holds both floors in build.gradle.kts but prints neither figure, and `koverLogUnit`
prints the line figure only. This reads the report-level counters (the `<counter>` elements directly
under `<report>`, which total every class the `unit` variant measures) and prints both, as covered of
total and a percentage to two places. With GITHUB_STEP_SUMMARY set, it also writes them there as a
table. A report without both counters, or with a counter that makes no sense, fails: a figure this
cannot read is not printed as zero.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET

# The counters printed, in order, with the label each is printed under.
METRICS = (("LINE", "line"), ("BRANCH", "branch"))


class ReportError(Exception):
    pass


def figures(xml: str) -> list[tuple[str, int, int]]:
    """(label, covered, total) for each metric, from the report-level counters."""
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise ReportError(f"not an XML report: {error}") from error
    if root.tag != "report":
        raise ReportError(f"the root element is <{root.tag}>, not <report>")
    counters = {counter.get("type"): counter for counter in root.findall("counter")}
    found = []
    for kind, label in METRICS:
        counter = counters.get(kind)
        if counter is None:
            raise ReportError(f"the report has no report-level {kind} counter")
        try:
            missed, covered = int(counter.get("missed", "")), int(counter.get("covered", ""))
        except ValueError:
            raise ReportError(f"the {kind} counter's missed and covered are not whole numbers") from None
        if missed < 0 or covered < 0 or missed + covered == 0:
            raise ReportError(f"the {kind} counter's missed {missed} and covered {covered} make no sense")
        found.append((label, covered, missed + covered))
    return found


def percent(covered: int, total: int) -> str:
    return f"{100 * covered / total:.2f}"


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: coverage-figures.py <Kover XML report>", file=sys.stderr)
        return 2
    try:
        with open(argv[0], encoding="utf-8") as report:
            measured = figures(report.read())
    except (OSError, ReportError) as error:
        print(f"coverage-figures.py: {argv[0]}: {error}", file=sys.stderr)
        return 1
    for label, covered, total in measured:
        print(f"ANDROID AGGREGATE {label} coverage: {percent(covered, total)}% ({covered} of {total})")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("| coverage | covered | total | percent |\n|---|---|---|---|\n")
            for label, covered, total in measured:
                out.write(f"| {label} | {covered} | {total} | {percent(covered, total)} |\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
