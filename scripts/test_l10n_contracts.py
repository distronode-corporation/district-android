"""Tests for l10n-contracts.py: python3 -m unittest discover -s scripts -p 'test_*.py'

The generator rewrites l10n/api_errors.toml and l10n/wire_labels.toml from contracts/i18n; these hold it
to keeping the French that `tools/l10n/l10n.py import` wrote, while the English is unchanged.
"""

from __future__ import annotations

import importlib.util
import shutil
import tempfile
import tomllib
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("l10n_contracts", HERE / "l10n-contracts.py")
gen = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gen)

PREVIOUS = """[meta]
catalog = "wire_labels"
platform = "android"

[wire.call_status_completed]
en = "completed"
fr = "Terminé (révisé)"
ctx = "label"
screen = "call log"
tier = "T3"
state = "reviewed"
fr_variant = "Reviewed wording."
note = "old note"

[wire.call_status_failed]
en = "failed"
fr = "Échec"
ctx = "label"
screen = "call log"
tier = "T3"
state = "signed"
note = "old note"

[wire.call_status_no_answer]
en = "no-answer"
fr = "Sans réponse (révisé)"
ctx = "label"
screen = "call log"
tier = "T3"
state = "reviewed"
note = "old note"

[wire.call_status_unknown]
en = "{value}"
fr = "Statut inconnu ({value})"
ctx = "label"
screen = "call log"
tier = "T3"
args = { value = "str" }
state = "reviewed"
note = "old note"
"""


def fresh() -> dict:
    """What the generator builds from the contract before it looks at the committed catalog."""
    return {
        "meta": {"catalog": "wire_labels", "platform": "android"},
        "wire": {
            "call_status_completed": gen.entry("completed", "Terminé", "label", "call log", "T3", "new note"),
            "call_status_failed": gen.entry("Failed", "Échec", "label", "call log", "T3", "new note"),
            "call_status_unknown": gen.entry("{value}", None, "label", "call log", "T3", "new note", {"value": "str"}),
            "call_status_busy": gen.entry("busy", "Occupé", "label", "call log", "T3", "new note"),
            # Moved from the raw value to the portal's label in 3.0.1: the generator records what it was.
            "call_status_no_answer": {
                **gen.entry("No answer", "Sans réponse", "label", "call log", "T3", "new note"),
                gen.WAS: "no-answer",
            },
        },
    }


class KeepTranslationsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.previous = self.tmp / "wire_labels.toml"
        self.previous.write_text(PREVIOUS, encoding="utf-8")

    def kept(self) -> dict:
        return gen.keep_translations(fresh(), self.previous)["wire"]

    def test_unchanged_english_keeps_french_state_and_variant(self) -> None:
        e = self.kept()["call_status_completed"]
        self.assertEqual((e["fr"], e["state"], e["fr_variant"]), ("Terminé (révisé)", "reviewed", "Reviewed wording."))
        self.assertEqual(e["note"], "new note", "the note is the contract's, not a translation field")

    def test_imported_french_without_a_contract_french_survives(self) -> None:
        e = self.kept()["call_status_unknown"]
        self.assertEqual((e["fr"], e["state"]), ("Statut inconnu ({value})", "reviewed"))

    def test_changed_english_takes_the_contract_french_as_draft(self) -> None:
        e = self.kept()["call_status_failed"]
        self.assertEqual((e["en"], e["fr"], e["state"]), ("Failed", "Échec", "draft"))

    def test_new_entry_takes_the_contract(self) -> None:
        e = self.kept()["call_status_busy"]
        self.assertEqual((e["fr"], e["state"]), ("Occupé", "draft"))

    def test_english_moved_to_the_portal_label_keeps_the_reviewed_french(self) -> None:
        e = self.kept()["call_status_no_answer"]
        self.assertEqual((e["en"], e["fr"], e["state"]), ("No answer", "Sans réponse (révisé)", "reviewed"))
        self.assertNotIn(gen.WAS, e, "the earlier English is read, never written")

    def test_no_committed_catalog(self) -> None:
        expected = fresh()
        del expected["wire"]["call_status_no_answer"][gen.WAS]
        self.assertEqual(gen.keep_translations(fresh(), self.tmp / "missing.toml"), expected)

    def test_emitted_catalog_is_valid(self) -> None:
        data = gen.keep_translations(fresh(), self.previous)
        cat = gen.l10n.catalog_from_data(self.previous, data)
        self.assertFalse(gen.l10n.validate([cat]))
        parsed = tomllib.loads(gen.l10n.emit_catalog(cat))
        self.assertEqual(parsed["wire"]["call_status_unknown"]["fr"], "Statut inconnu ({value})")

    def test_committed_catalogs_are_what_the_script_writes(self) -> None:
        self.assertEqual(gen.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()


class WireLabelsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.data, self.kotlin = gen.wire_labels()
        self.table = self.data["wire"]

    def test_every_vocabulary_category_is_generated(self) -> None:
        src = gen.json.loads((gen.CONTRACTS / "wire-vocabulary.json").read_text(encoding="utf-8"))
        self.assertEqual(set(src["categories"]), set(gen.WIRE_CATEGORIES))

    def test_a_value_spelled_unknown_does_not_take_the_unknown_value_key(self) -> None:
        self.assertEqual(self.table["call_status_value_unknown"]["en"], "Unknown")
        self.assertEqual(self.table["call_status_unknown"]["en"], "{value}")
        self.assertIn('"unknown" to R.string.wire_call_status_value_unknown,', self.kotlin)

    def test_a_brace_in_a_value_is_literal(self) -> None:
        self.assertEqual(gen.display("http-{status}", "raw"), "http-{{status}}")
        self.assertEqual(self.table["workflow_action_reason_http_status"]["en"], "Http {{status}}")
        self.assertIn('"http-{status}" to R.string.wire_workflow_action_reason_http_status,', self.kotlin)

    def test_a_dossier_key_reads_as_the_app_shows_its_heading(self) -> None:
        self.assertEqual(gen.humanize("executiveSummary"), "Executive summary")
        self.assertEqual(gen.humanize("next_best_action"), "Next best action")
        self.assertEqual(gen.humanize("_"), "_")
        self.assertEqual(gen.display("ca", "upper"), "CA")
        self.assertEqual(self.table["contact_research_key_caller_intel"]["en"], "Caller intel")

    def test_every_placeholder_is_a_label_whose_english_is_the_value_and_every_spelling_maps_to_it(self) -> None:
        caller = self.table["placeholder_unknown_caller"]
        self.assertEqual((caller["en"], caller["ctx"], caller["screen"]), ("Unknown Caller", "label", "wire placeholder"))
        self.assertNotIn("fr", caller)  # the contract has no French for it; the catalog's comes from an import
        self.assertEqual(self.table["placeholder_distronode_support"]["fr"], "Soutien Distronode")
        self.assertIn('"Unknown Caller" to R.string.wire_placeholder_unknown_caller,', self.kotlin)
        self.assertIn(
            '"No answer \\u2014 no conversation took place." to '
            "R.string.wire_placeholder_no_answer_no_conversation_took_place,",
            self.kotlin,
        )

    def test_a_portal_category_reads_as_the_portal_names_it_in_english_too(self) -> None:
        self.assertEqual(self.table["a2p_campaign_type_customer_care"]["en"], "Customer Care")
        self.assertEqual(self.table["number_provider_twilio"]["en"], "Twilio")
        self.assertEqual(self.table["sip_trunk_status_active"]["en"], "Active")
        self.assertEqual(self.table["workflow_action_send_sms"]["en"], "Send SMS")
        self.assertEqual(self.table["number_type_toll_free"]["en"], "Toll-Free")
        # A value the portal has no label for is shown as sent, and an unknown one generically.
        self.assertEqual(self.table["number_provider_value_unknown"]["en"], "unknown")
        self.assertEqual(self.table["sip_trunk_status_unknown"]["en"], "{value}")

    def test_english_reads_the_portal_label_or_the_value_humanized_as_the_cores_do(self) -> None:
        # visual QA A-S7: English used to show these as sent ("completed", "no-answer", "paid").
        self.assertEqual(self.table["call_status_completed"]["en"], "Completed")
        self.assertEqual(self.table["call_status_no_answer"]["en"], "No answer")
        self.assertEqual(self.table["sentiment_negative"]["en"], "Friction")
        self.assertEqual(self.table["disposition_booked"]["en"], "Booked")
        self.assertEqual(self.table["billing_invoice_status_paid"]["en"], "Paid")
        self.assertEqual(self.table["number_status_pending_verification"]["en"], "Pending verification")
        self.assertEqual(self.table["messaging_credential_source_byok"]["en"], "Bring Your Own Keys")
        self.assertEqual(gen.core_label(" not-interested "), "Not interested")
        # The region chip and the dossier headings keep their own display.
        self.assertEqual(self.table["region_ca"]["en"], "CA")
        # Every moved entry carries its earlier English for keep_translations, and only those.
        self.assertEqual(self.table["call_status_completed"][gen.WAS], "completed")
        self.assertNotIn(gen.WAS, self.table["region_ca"])
        self.assertNotIn(gen.WAS, self.table["number_provider_twilio"])

    def test_a_category_fallback_is_its_unknown_label_in_both_languages(self) -> None:
        unknown = self.table["number_status_unknown"]
        self.assertEqual((unknown["en"], unknown["fr"]), ("Active", "Actif"))
        self.assertNotIn("args", unknown)
        self.assertEqual(self.table["transfer_reason_unknown"]["en"], "Transfer attempted but not connected")
        self.assertEqual(self.table["desk_ticket_status_unknown"]["fr"], "Inconnu")
        self.assertEqual(self.table["knowledge_document_status_unknown"]["fr"], "Inconnu")
        self.assertEqual(self.table["meeting_status_unknown"]["en"], "{value}")
        self.assertIn("unknownTakesValue = false", self.kotlin)
