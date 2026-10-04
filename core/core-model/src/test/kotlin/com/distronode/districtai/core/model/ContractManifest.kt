package com.distronode.districtai.core.model

/**
 * The coordination point for the contracts directory, and the Kotlin mirror of the iOS client's
 * `ContractManifest.swift`.
 *
 * ⛔ WHY THIS EXISTS. Without it, the ONLY thing standing behind fixtures nothing decodes is
 * `ContractFixtureTest.contracts directory contains fixtures`, which asserts the directory is
 * non-empty. A fixture nothing decodes is indistinguishable from a fixture nothing NEEDS, so the
 * corpus could grow endpoints this client has no DTO for and every gate would stay green. The debt
 * is written down by name instead.
 *
 * ⛔ THE COUNT IS ASSERTED EXACTLY, NOT AS A FLOOR. A floor (`>= 1`) goes green against a broken
 * `district.contracts.dir` that happens to resolve somewhere else, and never notices a fixture the
 * server's generator added. An exact count turns both into one failure that names the files it
 * did not expect. It WILL fail the day a fixture is added, and that is the design: nothing else on
 * this side watches the generator.
 *
 * ⚠️ THE TWO NUMBERS MOVE INDEPENDENTLY, AND CONFLATING THEM IS THE EASY MISTAKE.
 * [EXPECTED_FIXTURE_COUNT] counts FILES ON DISK. [NOT_YET_MODELLED_BASELINE] counts the BURN-DOWN.
 * Writing a DTO for a fixture that already exists moves the second and leaves the first alone.
 */
internal object ContractManifest {

    /**
     * Every `.json` file in the contracts directory.
     *
     * This is the same corpus the iOS client's `ContractManifest.expectedFixtureCount` pins. The two
     * files describe ONE directory and must agree; if they ever disagree, one of them was edited
     * without re-counting. A new fixture should arrive DECODED by its own test, so the count moves
     * and [NOT_YET_MODELLED] does not grow, which it may not.
     */
    const val EXPECTED_FIXTURE_COUNT: Int = 156

    /**
     * ⛔ SHRINK-ONLY RATCHET. [NOT_YET_MODELLED] may LOSE entries and must never gain one.
     * When a DTO lands, delete the fixture's line AND lower this literal in the same commit.
     *
     * ⚠️ THE BASELINE IS KEPT EQUAL TO THE ENTRY COUNT ON PURPOSE. A baseline that sits above its
     * list enforces nothing until the slack is used up. A baseline with slack is not a ratchet, it
     * is a budget.
     */
    const val NOT_YET_MODELLED_BASELINE: Int = 4

    /**
     * Fixtures that no Kotlin test decodes.
     *
     * ⛔ ONE EXPLICIT LIST, NEVER A PATTERN AND NEVER A "SKIP IF NOTHING DECODES IT" FALLBACK. A
     * rule that derives the skip set from whatever happens to be implemented cannot tell "not
     * ported yet" from "silently stopped being verified", and would be counting itself. Every
     * entry here is a debt somebody wrote down.
     *
     * ⛔ STALE ENTRIES FAIL IN BOTH DIRECTIONS. A name here that is not on disk means the corpus
     * moved underneath this file; a name here that IS decoded means the burn-down stopped being
     * true. [ContractManifestTest] checks both.
     *
     * ⚠️ ALPHABETICAL, NOT GROUPED BY OWNER. The groups below are comments over a single sorted
     * run so that a `git diff` of an addition or removal is one line in an obvious place.
     */
    val NOT_YET_MODELLED: Set<String> = setOf(
        // ── Alternate branches of endpoints this client already decodes (4) ──────────────────
        // ⚠️ NOT ONE MISSING TYPE BETWEEN THEM, AND THAT IS THE WHOLE REMAINING LIST. The two
        // `*-patch` bodies are the bare `{"success": true}` a single `SuccessResponse` already
        // covers, and `district-dial-dormant` / `district-workspace-list-partial` are the SECOND
        // branch of a route whose first branch is gated today. What is missing is a test that
        // reads the branch, not a DTO to read it with.
        "district-dial-dormant.json",
        "district-directory-patch.json",
        "district-routing-patch.json",
        "district-workspace-list-partial.json",
    )
}
