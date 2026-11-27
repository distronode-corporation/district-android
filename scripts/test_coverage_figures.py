"""Tests for coverage-figures.py: python3 -m unittest discover -s scripts -p 'test_*.py'

The script prints the figures the Kover floors are held to, so these hold it to reading the
report-level counters and never a class's or a package's, and to failing rather than printing a
figure it could not read.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import tempfile
import unittest
import unittest.mock
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("coverage_figures", HERE / "coverage-figures.py")
figures = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(figures)

# The shape JaCoCo writes (Kover's JaCoCo engine): a package and a class with their own counters,
# then the report-level ones, last.
REPORT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="unit">
  <package name="com/example">
    <class name="com/example/A">
      <counter type="LINE" missed="5" covered="5"/>
      <counter type="BRANCH" missed="1" covered="1"/>
    </class>
    <counter type="LINE" missed="5" covered="5"/>
    <counter type="BRANCH" missed="1" covered="1"/>
  </package>
  <counter type="INSTRUCTION" missed="3" covered="97"/>
  <counter type="LINE" missed="9" covered="19800"/>
  <counter type="BRANCH" missed="95" covered="6960"/>
</report>
"""


class Figures(unittest.TestCase):
    def test_reads_the_report_level_counters(self):
        self.assertEqual(figures.figures(REPORT), [("line", 19800, 19809), ("branch", 6960, 7055)])

    def test_percent_has_two_places(self):
        self.assertEqual(figures.percent(19800, 19809), "99.95")
        self.assertEqual(figures.percent(6960, 7055), "98.65")

    def test_fails_on_what_it_cannot_read(self):
        cases = {
            "not XML": "<report",
            "another root": "<sessions/>",
            "no branch counter": '<report><counter type="LINE" missed="0" covered="1"/></report>',
            "a class's counter only": (
                '<report><package><counter type="LINE" missed="0" covered="1"/>'
                '<counter type="BRANCH" missed="0" covered="1"/></package></report>'
            ),
            "not a number": (
                '<report><counter type="LINE" missed="x" covered="1"/>'
                '<counter type="BRANCH" missed="0" covered="1"/></report>'
            ),
            "missing attribute": (
                '<report><counter type="LINE" covered="1"/><counter type="BRANCH" missed="0" covered="1"/></report>'
            ),
            "negative": (
                '<report><counter type="LINE" missed="-1" covered="1"/>'
                '<counter type="BRANCH" missed="0" covered="1"/></report>'
            ),
            "nothing counted": (
                '<report><counter type="LINE" missed="0" covered="0"/>'
                '<counter type="BRANCH" missed="0" covered="1"/></report>'
            ),
        }
        for name, xml in cases.items():
            with self.subTest(name), self.assertRaises(figures.ReportError):
                figures.figures(xml)


class Main(unittest.TestCase):
    def run_main(self, argv: list[str], env: dict[str, str]) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with (
            unittest.mock.patch.dict(os.environ, env, clear=True),
            contextlib.redirect_stdout(out),
            contextlib.redirect_stderr(err),
        ):
            code = figures.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_prints_both_figures_and_the_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            report, summary = Path(tmp, "reportUnit.xml"), Path(tmp, "summary.md")
            report.write_text(REPORT, encoding="utf-8")
            code, out, _ = self.run_main([str(report)], {"GITHUB_STEP_SUMMARY": str(summary)})
            self.assertEqual(code, 0)
            self.assertEqual(
                out,
                "ANDROID AGGREGATE line coverage: 99.95% (19800 of 19809)\n"
                "ANDROID AGGREGATE branch coverage: 98.65% (6960 of 7055)\n",
            )
            self.assertIn("| branch | 6960 | 7055 | 98.65 |", summary.read_text(encoding="utf-8"))

    def test_no_summary_without_the_variable(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp, "reportUnit.xml")
            report.write_text(REPORT, encoding="utf-8")
            code, out, _ = self.run_main([str(report)], {})
            self.assertEqual(code, 0)
            self.assertEqual(sorted(os.listdir(tmp)), ["reportUnit.xml"])
            self.assertIn("line coverage: 99.95%", out)

    def test_a_missing_or_bad_report_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = str(Path(tmp, "none.xml"))
            code, out, err = self.run_main([missing], {})
            self.assertEqual((code, out), (1, ""))
            self.assertIn("none.xml", err)
            bad = Path(tmp, "bad.xml")
            bad.write_text("<sessions/>", encoding="utf-8")
            code, out, err = self.run_main([str(bad)], {})
            self.assertEqual((code, out), (1, ""))
            self.assertIn("not <report>", err)

    def test_usage(self):
        code, _, err = self.run_main([], {})
        self.assertEqual(code, 2)
        self.assertIn("usage", err)


if __name__ == "__main__":
    unittest.main()
