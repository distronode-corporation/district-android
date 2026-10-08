package com.distronode.districtai.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turns [ContractManifest] from a comment into a gate.
 *
 * ⛔ WHAT THIS PREVENTS, CONCRETELY. `ContractFixtureTest.contracts directory contains fixtures`
 * asserts the directory is NOT EMPTY. That is a floor of one: 165 of the 166 committed fixtures
 * could stop being decoded by anything and the suite would still be green, and a fixture the
 * website's generator adds for a brand-new endpoint arrives silently. These four tests make both
 * events a named, one-line failure.
 *
 * ⛔ HOW "DECODED" IS DERIVED, AND WHY THE OBVIOUS ALTERNATIVE WAS REJECTED. The decoded set is
 * computed by SCANNING THE KOTLIN TEST SOURCES for quoted `*.json` literals, with the source root
 * handed in as a system property exactly the way `district.contracts.dir` already is. The
 * alternative considered first was to have [ContractFixtures.read] record every name it is asked
 * for and to assert the union afterwards. It was rejected for two reasons, both checked against
 * this tree rather than assumed:
 *
 *   1. IT CANNOT SEE ANOTHER MODULE. One fixture is decoded by `:core:core-auth`
 *      (`PkceVectorTest`) and six by `:core:core-network` (`ApiErrorEnvelopeContractTest`). Those
 *      run in their own Gradle test JVMs and do not even use this module's [ContractFixtures], so
 *      a registry living here would report all seven as un-decoded and the skip list would have to
 *      carry seven lies to stay green.
 *   2. IT CAN PASS VACUOUSLY. JUnit does not guarantee an order across test classes, so "a
 *      manifest test that runs last" is not expressible. A registry read too early is empty, and
 *      an empty registry makes "nothing decoded is on the skip list" trivially true.
 *
 * ⚠️ THE SCAN CANNOT PASS VACUOUSLY EITHER, AND THAT IS BY CONSTRUCTION RATHER THAN BY LUCK. If
 * the walk finds nothing — wrong property, moved module, pruned checkout — the decoded set is
 * empty, and `every fixture is decoded or recorded as not yet modelled` then reports all 153
 * decoded fixtures as unaccounted for. The failure is loud and names them.
 *
 * ⚠️ THE ONE SOFTNESS, STATED RATHER THAN HIDDEN: a fixture name written inside a COMMENT in a
 * test file would count as decoded. The bias is therefore toward under-reporting debt, never
 * toward failing a correct tree.
 */
class ContractManifestTest {

    /**
     * ⚠️ Not anchored on `district-`. Every fixture happens to carry that prefix today, and
     * hardcoding it would make a future rename look like an un-decoded fixture. Membership of the
     * on-disk set is the filter that matters.
     */
    private val jsonLiteral = Regex("\"([A-Za-z0-9._-]+\\.json)\"")

    private val testSourceMarker = File.separator + "src" + File.separator + "test"

    /**
     * ⛔ THE MANIFEST IS ITSELF A KOTLIN TEST SOURCE FULL OF FIXTURE NAMES, AND LEAVING IT IN THE
     * WALK RETIRES THE ENTIRE GATE. [ContractManifest.NOT_YET_MODELLED] is 4 quoted `.json`
     * literals under `core-model/src/test/` (it was 54 before the scheduling-admin, persona and
     * message-thread packets), so a naive scan reads every recorded debt as decoded:
     * the skip list looks 100% burned down, `unaccounted` comes out empty, and the whole thing
     * goes green while checking nothing. Caught here before this test ever ran, by counting the
     * scan's hits against `grep`.
     *
     * ⚠️ The exclusion is asserted rather than assumed — see [decodedFixtures], which fails if the
     * walk does not find exactly one file by this name. A rename that silently re-included it
     * would otherwise reintroduce the same vacuous pass.
     */
    private val manifestFileName = "ContractManifest.kt"

    private fun fixturesOnDisk(): Set<String> {
        val dir = ContractFixtures.dir
        assertTrue(
            "Contracts directory ${dir.absolutePath} does not exist.",
            dir.isDirectory,
        )
        return dir.listFiles { f: File -> f.extension == "json" }.orEmpty().map { it.name }.toSet()
    }

    /**
     * ⚠️ Supplied by `core/core-model/build.gradle.kts`, which also declares the same file set as a
     * task INPUT. Without the input declaration Gradle keeps this task UP-TO-DATE across a
     * test-source change and the gate silently never re-runs — the identical trap the fixtures
     * themselves hit, recorded in that build file.
     */
    private fun kotlinTestSources(): List<File> {
        val configured = System.getProperty("district.android.root")
        assertTrue(
            "System property district.android.root is not set. It is configured in " +
                "core/core-model/build.gradle.kts; without it this test cannot find the Kotlin " +
                "test sources and would otherwise pass by verifying nothing.",
            !configured.isNullOrBlank(),
        )
        val root = File(configured!!)
        assertTrue(
            "district.android.root ${root.absolutePath} is not a directory.",
            root.isDirectory,
        )
        return root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && !it.name.startsWith(".gradle") }
            .filter { it.isFile && it.extension == "kt" && it.path.contains(testSourceMarker) }
            .toList()
    }

    private fun decodedFixtures(onDisk: Set<String>): Set<String> {
        val sources = kotlinTestSources()
        assertTrue(
            "Found no Kotlin test sources under district.android.root. The walk is how this gate " +
                "knows which fixtures are decoded; finding none means the property points at the " +
                "wrong tree, not that nothing is decoded.",
            sources.isNotEmpty(),
        )
        val (manifest, decoders) = sources.partition { it.name == manifestFileName }
        assertEquals(
            "Expected exactly one $manifestFileName in the walk, found ${manifest.size}. That " +
                "file holds NOT_YET_MODELLED as quoted literals and MUST be excluded from the " +
                "scan, or every recorded debt reads as decoded and this gate checks nothing. If " +
                "the file was renamed, update manifestFileName in the same commit.",
            1,
            manifest.size,
        )
        return decoders
            .flatMap { file -> jsonLiteral.findAll(file.readText()).map { it.groupValues[1] } }
            .filter { it in onDisk }
            .toSet()
    }

    @Test
    fun `the fixture corpus is exactly the size the manifest records`() {
        val onDisk = fixturesOnDisk()
        assertEquals(
            "contracts/ holds ${onDisk.size} fixtures, the manifest expects " +
                "${ContractManifest.EXPECTED_FIXTURE_COUNT}. A fixture arriving from the " +
                "website's generator must be ACKNOWLEDGED here, not absorbed: bump " +
                "EXPECTED_FIXTURE_COUNT and decode the new file. iOS pins the same number in " +
                "ContractManifest.swift and the two must agree.",
            ContractManifest.EXPECTED_FIXTURE_COUNT,
            onDisk.size,
        )
    }

    @Test
    fun `every fixture is decoded or recorded as not yet modelled`() {
        val onDisk = fixturesOnDisk()
        val decoded = decodedFixtures(onDisk)
        val unaccounted = (onDisk - decoded - ContractManifest.NOT_YET_MODELLED).sorted()
        assertEquals(
            "These fixtures are decoded by no Kotlin test and are not recorded in " +
                "ContractManifest.NOT_YET_MODELLED, so nothing but a non-empty-directory check " +
                "stands behind them. Write the DTO and a test that reads the file. Recording them " +
                "as debt instead is NOT available: NOT_YET_MODELLED_BASELINE may not rise.",
            emptyList<String>(),
            unaccounted,
        )
    }

    @Test
    fun `the not yet modelled list holds no stale and no already-decoded entry`() {
        val onDisk = fixturesOnDisk()
        val decoded = decodedFixtures(onDisk)
        val gone = (ContractManifest.NOT_YET_MODELLED - onDisk).sorted()
        assertEquals(
            "ContractManifest.NOT_YET_MODELLED names fixtures that are not on disk. The corpus " +
                "moved underneath this file; delete these lines and lower " +
                "NOT_YET_MODELLED_BASELINE by the same number in the same commit.",
            emptyList<String>(),
            gone,
        )
        val alreadyDecoded = ContractManifest.NOT_YET_MODELLED.filter { it in decoded }.sorted()
        assertEquals(
            "These fixtures ARE decoded by a Kotlin test and are still listed as debt. That is " +
                "the burn-down having stopped being true: delete these lines and lower " +
                "NOT_YET_MODELLED_BASELINE by the same number in the same commit.",
            emptyList<String>(),
            alreadyDecoded,
        )
    }

    /**
     * ⛔ THE RATCHET. The list may shrink and must never grow, and the
     * baseline is kept EQUAL to the entry count so there is never slack to spend.
     */
    @Test
    fun `the not-yet-modelled list is shrink-only`() {
        assertTrue(
            "ContractManifest.NOT_YET_MODELLED holds ${ContractManifest.NOT_YET_MODELLED.size} " +
                "entries against a baseline of ${ContractManifest.NOT_YET_MODELLED_BASELINE}. " +
                "This list may only SHRINK. If you removed an entry, lower " +
                "NOT_YET_MODELLED_BASELINE to the new size in the same commit. If you are trying " +
                "to ADD one, the answer is a DTO and a test, not a bigger baseline.",
            ContractManifest.NOT_YET_MODELLED.size <= ContractManifest.NOT_YET_MODELLED_BASELINE,
        )
        assertEquals(
            "The baseline has slack: it is larger than the list. A baseline with slack is a " +
                "budget, not a ratchet — the website's coverage exclude list sat at 108 against " +
                "92 entries and enforced nothing for months. Lower it to the list's size.",
            ContractManifest.NOT_YET_MODELLED.size,
            ContractManifest.NOT_YET_MODELLED_BASELINE,
        )
    }
}
