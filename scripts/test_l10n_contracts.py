"""Tests for l10n-contracts.py: python3 -m unittest discover -s scripts -p 'test_*.py'

The generator rewrites l10n/api_errors.toml and l10n/wire_labels.toml from contracts/i18n; these hold it
to the website's French winning wherever the contract has one, to keeping the French that
`tools/l10n/l10n.py import` wrote where it has none while the English is unchanged, and to FR_OVERRIDES.
"""

from __future__ import annotations

import importlib.util
import shutil
import subprocess
import tempfile
import tomllib
import unittest
import unittest.mock
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("l10n_contracts", HERE / "l10n-contracts.py")
gen = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gen)

PREVIOUS = """[meta]
catalog = "wire_labels"
platform = "android"

[wire.call_status_canceled]
en = "Canceled"
fr = "Annulé (avant le site Web)"
ctx = "label"
screen = "call log"
tier = "T3"
state = "reviewed"
fr_variant = "A wording the website has since changed."
note = "old note"

[wire.call_status_completed]
en = "completed"
fr = "Terminé"
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
fr = "Sans réponse"
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
            "call_status_canceled": gen.entry("Canceled", "Annulé", "label", "call log", "T3", "new note"),
            "call_status_completed": gen.entry("completed", "Terminé", "label", "call log", "T3", "new note"),
            "call_status_failed": gen.entry("Call failed", "Échec", "label", "call log", "T3", "new note"),
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
        self.assertEqual((e["fr"], e["state"], e["fr_variant"]), ("Terminé", "reviewed", "Reviewed wording."))
        self.assertEqual(e["note"], "new note", "the note is the contract's, not a translation field")

    def test_imported_french_without_a_contract_french_survives(self) -> None:
        e = self.kept()["call_status_unknown"]
        self.assertEqual((e["fr"], e["state"]), ("Statut inconnu ({value})", "reviewed"))

    def test_changed_english_takes_the_contract_french_as_draft(self) -> None:
        e = self.kept()["call_status_failed"]
        self.assertEqual((e["en"], e["fr"], e["state"]), ("Call failed", "Échec", "draft"))

    def test_new_entry_takes_the_contract(self) -> None:
        e = self.kept()["call_status_busy"]
        self.assertEqual((e["fr"], e["state"]), ("Occupé", "draft"))

    def test_english_moved_to_the_portal_label_keeps_the_reviewed_french(self) -> None:
        e = self.kept()["call_status_no_answer"]
        self.assertEqual((e["en"], e["fr"], e["state"]), ("No answer", "Sans réponse", "reviewed"))
        self.assertNotIn(gen.WAS, e, "the earlier English is read, never written")

    def test_a_change_of_letter_case_alone_keeps_the_french(self) -> None:
        data = fresh()
        data["wire"]["call_status_completed"]["en"] = "COMPLETED"
        e = gen.keep_translations(data, self.previous)["wire"]["call_status_completed"]
        self.assertEqual((e["fr"], e["state"]), ("Terminé", "reviewed"))

    def test_the_websites_french_wins_over_the_committed_french_as_a_draft(self) -> None:
        # Until 3.3.0 an unchanged English kept the committed French, so the website's French-only fixes
        # (« offert à », the NBSP before « Mo ») never reached the app.
        e = self.kept()["call_status_canceled"]
        self.assertEqual((e["en"], e["fr"], e["state"]), ("Canceled", "Annulé", "draft"))
        self.assertNotIn("fr_variant", e, "a note about the old wording does not describe the new one")

    def test_an_override_holds_while_the_contract_says_what_it_replaced(self) -> None:
        data = fresh()
        data["wire"]["call_status_completed"]["fr"] = "Terminé (site Web)"
        with unittest.mock.patch.dict(gen.FR_OVERRIDES, {"wire.call_status_completed": ("Terminé (site Web)", "Terminé", "why")}):
            e = gen.keep_translations(data, self.previous)["wire"]["call_status_completed"]
        self.assertEqual((e["fr"], e["state"], e["fr_variant"]), ("Terminé", "reviewed", "Reviewed wording."))

    def test_an_override_yields_once_the_website_rewords_the_sentence(self) -> None:
        data = fresh()
        data["wire"]["call_status_completed"]["fr"] = "Terminé (nouveau)"
        with unittest.mock.patch.dict(gen.FR_OVERRIDES, {"wire.call_status_completed": ("Terminé (site Web)", "Terminé", "why")}):
            e = gen.keep_translations(data, self.previous)["wire"]["call_status_completed"]
        self.assertEqual((e["fr"], e["state"]), ("Terminé (nouveau)", "draft"))

    def test_a_new_override_entry_is_a_draft(self) -> None:
        data = fresh()
        with unittest.mock.patch.dict(gen.FR_OVERRIDES, {"wire.call_status_busy": ("Occupé", "Ligne occupée", "why")}):
            e = gen.keep_translations(data, self.previous)["wire"]["call_status_busy"]
        self.assertEqual((e["fr"], e["state"]), ("Ligne occupée", "draft"))

    def test_every_override_still_replaces_the_contracts_french(self) -> None:
        # A stale override is dead: the website reworded the sentence, so the contract's French now wins
        # and the override must be removed or rewritten against the new wording.
        generated = {}
        for data in (gen.api_errors()[0], gen.wire_labels()[0]):
            for group, table in data.items():
                if group != "meta":
                    generated.update({f"{group}.{seg}": fields.get("fr") for seg, fields in table.items()})
        for key, (theirs, ours, why) in gen.FR_OVERRIDES.items():
            self.assertEqual(generated.get(key), theirs, f"{key}: the contract no longer says what the override replaced")
            self.assertNotEqual(ours, theirs, key)
            self.assertTrue(why.strip(), key)
        self.assertIn("paramètres", gen.FR_OVERRIDES["api_error.live_changed"][1])

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
        self.assertEqual(self.table["workflow_action_reason_http_status"]["en"], "HTTP {{status}}")
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
        # An upper-case enum is sentence-cased, never shown in capitals.
        self.assertEqual(self.table["a2p_registration_status_in_progress"]["en"], "In progress")
        self.assertEqual(self.table["toll_free_verification_status_twilio_approved"]["en"], "Twilio approved")
        self.assertEqual(gen.core_label("2FA"), "2fa")
        # Initialisms stay in capitals.
        self.assertEqual(self.table["number_capability_sms"]["en"], "SMS")
        self.assertEqual(self.table["workflow_action_outcome_ok"]["en"], "OK")
        self.assertEqual(gen.core_label("missing-webhook-url"), "Missing webhook URL")
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


class SyncTest(unittest.TestCase):
    """--sync: the contract files from a monorepo ref, byte for byte, without touching its checkout."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.mono = self.tmp / "mono"
        src = self.mono / gen.MONOREPO_CONTRACTS
        src.mkdir(parents=True)
        for name in gen.CONTRACT_FILES:
            (src / name).write_bytes(f"{name} \u00a0\n".encode())
        git = ["git", "-C", str(self.mono), "-c", "user.name=t", "-c", "user.email=t@example.com"]
        subprocess.run(["git", "init", "-q", str(self.mono)], check=True)
        subprocess.run([*git, "add", "."], check=True)
        subprocess.run([*git, "commit", "-qm", "one"], check=True)
        self.dest = self.tmp / "dest"
        self.dest.mkdir()

    def test_copies_every_file_byte_for_byte_and_reports_what_changed(self) -> None:
        (self.dest / "format-vectors.json").write_bytes("format-vectors.json \u00a0\n".encode())
        self.assertEqual(gen.fetch_contracts(self.mono, "HEAD", self.dest), ["api-errors.json", "wire-vocabulary.json"])
        for name in gen.CONTRACT_FILES:
            self.assertEqual((self.dest / name).read_bytes(), (self.mono / gen.MONOREPO_CONTRACTS / name).read_bytes())
        self.assertEqual(gen.fetch_contracts(self.mono, "HEAD", self.dest), [])

    def test_reads_the_ref_not_the_working_tree(self) -> None:
        (self.mono / gen.MONOREPO_CONTRACTS / "api-errors.json").write_text("uncommitted", encoding="utf-8")
        gen.fetch_contracts(self.mono, "HEAD", self.dest)
        self.assertEqual((self.dest / "api-errors.json").read_bytes(), "api-errors.json \u00a0\n".encode())

    def test_a_ref_missing_a_file_writes_nothing(self) -> None:
        git = ["git", "-C", str(self.mono), "-c", "user.name=t", "-c", "user.email=t@example.com"]
        subprocess.run([*git, "rm", "-q", f"{gen.MONOREPO_CONTRACTS}/wire-vocabulary.json"], check=True)
        subprocess.run([*git, "commit", "-qm", "two"], check=True)
        with self.assertRaises(SystemExit):
            gen.fetch_contracts(self.mono, "HEAD", self.dest)
        self.assertEqual(list(self.dest.iterdir()), [])

    def test_commit_of_resolves_a_ref_and_refuses_an_unknown_one(self) -> None:
        self.assertRegex(gen.commit_of(self.mono, "HEAD"), r"^[0-9a-f]{40}$")
        with self.assertRaises(SystemExit):
            gen.commit_of(self.mono, "no-such-ref")

    def test_sync_and_check_together_are_refused(self) -> None:
        with self.assertRaises(SystemExit), unittest.mock.patch("sys.stderr"):
            gen.main(["--check", "--sync", str(self.mono)])


if __name__ == "__main__":
    unittest.main()
