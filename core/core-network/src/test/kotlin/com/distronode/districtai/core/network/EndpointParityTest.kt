package com.distronode.districtai.core.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compares this client's endpoint surface with the iOS client's, from both sources, every build.
 *
 * ⛔ WHY IT IS DERIVED EVERY BUILD. A comparison done once by hand and then quoted can list
 * endpoints as missing on the Kotlin side that already ship here, because nothing re-derives it.
 * A parity claim that nobody re-derives does not stay half-true: it drifts toward whatever the
 * last author believed.
 *
 * ⚠️ THE ANDROID SIDE IS READ LIVE; THE iOS SIDE IS WHATEVER `EndpointID.swift` THE BUILD HANDS
 * IN. By default that is the vendored snapshot at `parity/EndpointID.swift`, because the iOS
 * client lives in its own repository; `DISTRICT_IOS_ENDPOINT_IDS=<path>` substitutes a live iOS
 * checkout. The snapshot is the part that can rot, so it carries its upstream commit in
 * `parity/README.md`, and every failure below names which of the two was read. The path is
 * handed in by absolute path from `core/core-network/build.gradle.kts`, and it is declared there
 * as a task INPUT so a change to it re-runs this test.
 *
 * ⛔ THE ANDROID SIDE IS A DIRECTORY, NOT A FILE, BECAUSE A SINGLE-FILE READ FAILS SILENTLY. Most
 * feature areas declare their endpoints as SIBLING interfaces beside `DistrictApi.kt` in the same
 * package (deliberately, because a 111-method interface is the one file several authors cannot
 * edit at once). Reading `DistrictApi.kt` alone reports those implemented endpoints as missing
 * WITHOUT GOING RED, because a recorded list built the same way still matches what it sees. A
 * gate that can be made wrong by adding a file is a gate that will be. The walk is the fix, and it
 * is why nothing here names an interface or a file.
 *
 * ⚠️ THE ONE THING THIS CANNOT COVER, STATED RATHER THAN IMPLIED: a change that lands ONLY in the
 * iOS client is invisible here until the snapshot is refreshed (or the test is run with the
 * override pointing at that checkout). It fires on the refresh, not on the iOS commit that caused
 * it.
 *
 * ⚠️ THIS CANNOT PASS VACUOUSLY. If either extractor stops matching, that side's name set
 * collapses toward empty and the equality assertions below report the whole surface as a diff. A
 * silently broken extractor fails loudly rather than agreeing with itself.
 */
class EndpointParityTest {

    /**
     * ⚠️ Anchored and terminated. `EndpointID` is a `String`-raw-valued enum whose cases carry no
     * explicit raw value and no trailing code, so a `case` line is the whole declaration. Requiring
     * end-of-line keeps a future `case foo = "bar"` or a `switch` arm out of the set instead of
     * silently mis-reading one.
     */
    private val swiftEnumCase = Regex("^[ \\t]*case[ \\t]+([A-Za-z0-9_]+)[ \\t]*$", RegexOption.MULTILINE)

    /**
     * ⚠️ Anchored to the start of a line, and matched only INSIDE an interface body (see
     * [interfaceBodies]). Anchoring is what keeps `override suspend fun` on the `Http*Api`
     * implementations out of the set — they would otherwise double every endpoint — and the
     * interface-body restriction is what keeps `DistrictApiClient`'s four transport helpers
     * (`get`, `send`, `sendMultipart`, `redirectTarget`) out of it. Those four are not endpoints,
     * they are how an endpoint is spoken, and a directory-wide regex would file them as Android
     * extras iOS lacks.
     *
     * ⚠️ THE OPTIONAL TYPE PARAMETER IS REQUIRED, not defensive: `performSchedulingOp` is declared
     * `suspend fun <T> performSchedulingOp(...)`, and without the `<...>` clause the regex reads
     * the generic parameter as the function name.
     */
    private val kotlinSuspendFun =
        Regex("^[ \\t]*suspend fun[ \\t]+(?:<[^>]*>[ \\t]*)?([A-Za-z0-9_]+)", RegexOption.MULTILINE)

    private val interfaceDeclaration = Regex("\\binterface[ \\t]+[A-Za-z0-9_]+")

    private fun source(property: String, what: String): File {
        val configured = System.getProperty(property)
        assertTrue(
            "System property $property is not set. It is configured in " +
                "core/core-network/build.gradle.kts; without it this test cannot read $what and " +
                "would otherwise pass by verifying nothing.",
            !configured.isNullOrBlank(),
        )
        return File(configured!!)
    }

    /**
     * Comment and string content replaced by spaces, preserving length and every line break.
     *
     * ⛔ WITHOUT THIS, THE PROSE IS PART OF THE SURFACE. These files carry more KDoc than code and
     * the KDoc quotes function names, route shapes and Swift case names constantly; a raw regex
     * over the text picks up whatever a sentence happened to mention. Blanking rather than
     * deleting keeps offsets and line numbers intact, which is what lets the line-anchored
     * [kotlinSuspendFun] and the brace walk below agree about the same file.
     *
     * ⚠️ KOTLIN BLOCK COMMENTS NEST, and this counts them properly. That is not a hypothetical: a
     * KDoc line containing a glob like `district/desk/…` opens a nested comment that never closes,
     * and compilation fails with "Unclosed comment" pointing at the last line of the file rather
     * than at the prose that caused it.
     */
    private fun stripCommentsAndStrings(text: String): String = CommentStripper(text).stripped()

    /**
     * The brace-matched end of the block opening at [open], or the end of [text].
     *
     * ⚠️ Extracted from [interfaceBodies] so that loop owns one `break` and no `continue`; the
     * walk itself is unchanged.
     */
    private fun endOfBlock(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '{') {
                depth++
            } else if (text[i] == '}') {
                depth--
                if (depth == 0) {
                    break
                }
            }
            i++
        }
        return minOf(i, text.length)
    }

    /**
     * The stripped body of every interface in [text].
     *
     * ⛔ A BODYLESS INTERFACE MUST BE SKIPPED OR IT STEALS THE NEXT ONE'S BODY, AND THE SYMPTOM IS
     * A DUPLICATE RATHER THAN AN ERROR. `interface DistrictApi : WorkspaceApi, OverviewApi, …`
     * declares nothing and has no braces at all, so "the first `{` after the declaration" is the
     * opening brace of `interface WorkspaceApi` — which then gets read twice and contributes
     * `workspaceList` a second time. Measured: 86 names from a file that holds 85. The guard is
     * that the body brace must come before the NEXT interface declaration.
     */
    private fun interfaceBodies(text: String): List<String> {
        val stripped = stripCommentsAndStrings(text)
        val declarations = interfaceDeclaration.findAll(stripped).map { it.range }.toList()
        return declarations.mapNotNull { declaration ->
            val brace = stripped.indexOf('{', declaration.last + 1)
            val next = declarations.firstOrNull { it.first > declaration.first }?.first
            val bodyless = brace < 0 || (next != null && next < brace)
            if (bodyless) null else stripped.substring(brace, endOfBlock(stripped, brace))
        }
    }

    /**
     * Which `EndpointID.swift` the build handed in: `vendored` (the snapshot under `parity/`) or
     * the name of the environment variable that overrode it. Every iOS-side message carries it,
     * because "the file is missing" means something different for each.
     */
    private fun iosOrigin(): String = System.getProperty("district.ios.endpointid.origin") ?: "unknown"

    private fun iosEndpoints(): Set<String> {
        val file = source("district.ios.endpointid", "the iOS EndpointID.swift")
        assertTrue(
            "The iOS EndpointID.swift (source: ${iosOrigin()}) is not a file at " +
                "${file.absolutePath}. The vendored snapshot lives at parity/EndpointID.swift; an " +
                "override set through DISTRICT_IOS_ENDPOINT_IDS must name an existing file, and a " +
                "relative one resolves against the repository root.",
            file.isFile,
        )
        val names = swiftEnumCase.findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertTrue(
            "Extracted no enum cases from ${file.absolutePath} (source: ${iosOrigin()}). The " +
                "file exists, so this is the regex having stopped matching, not an empty iOS client.",
            names.isNotEmpty(),
        )
        assertEquals(
            "EndpointID.swift declares a duplicate case, which Swift would not compile. The " +
                "extraction is wrong, not the source.",
            names.size,
            names.toSet().size,
        )
        return names.toSet()
    }

    /**
     * ⚠️ THE DIRECTORY IS READ, NOT A LIST OF FILES, SO A NEW API INTERFACE IS IN THE GATE THE DAY
     * IT IS WRITTEN. An explicit file list here would be one more thing to forget, and forgetting
     * it produces the silent under-report described in this class's ⛔ rather than a failure.
     */
    private fun androidSources(): List<File> {
        val dir = source("district.api.kotlin.dir", "this module's API interface sources")
        assertTrue(
            "district.api.kotlin.dir ${dir.absolutePath} is not a directory.",
            dir.isDirectory,
        )
        val sources = dir.listFiles { f: File -> f.isFile && f.extension == "kt" }.orEmpty().toList()
        assertTrue(
            "Found no Kotlin sources under ${dir.absolutePath}. The walk is how this gate knows " +
                "this client's endpoint surface; finding none means the property points at the " +
                "wrong tree, not that this client has no endpoints.",
            sources.isNotEmpty(),
        )
        assertTrue(
            "DistrictApi.kt is not among the ${sources.size} sources found under " +
                "${dir.absolutePath}. It declares the composed interface and every section of it, " +
                "so its absence means the package moved and this gate is reading somewhere else.",
            sources.any { it.name == "DistrictApi.kt" },
        )
        return sources.sortedBy { it.name }
    }

    private fun androidRawEndpoints(): Set<String> {
        val raw = androidSources()
            .flatMap { file -> interfaceBodies(file.readText()) }
            .flatMap { body -> kotlinSuspendFun.findAll(body).map { it.groupValues[1] } }
            .toList()
        assertTrue(
            "Extracted no suspend funs from the API interfaces. The sources exist, so this is " +
                "the extraction having stopped matching, not an empty Android client.",
            raw.isNotEmpty(),
        )
        val duplicates = raw.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertEquals(
            "The same endpoint name was extracted twice: $duplicates. Two interfaces in this " +
                "package declare one name, or a bodyless interface has absorbed the next one's " +
                "body — see the ⛔ on interfaceBodies.",
            emptyList<String>(),
            duplicates,
        )
        return raw.toSet()
    }

    private fun androidEndpoints(): Set<String> {
        val raw = androidRawEndpoints()
        val normalised = raw.map { EndpointParity.SPELLING_DELTAS[it] ?: it }
        val collisions = normalised.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertEquals(
            "Normalisation collapsed two distinct endpoints onto one name. A SPELLING_DELTAS " +
                "entry now collides with a real function: $collisions",
            normalised.size,
            normalised.toSet().size,
        )
        return normalised.toSet()
    }

    /**
     * ⛔ A STALE NORMALISATION RULE IS INVISIBLE WITHOUT THIS. If `enableScheduling` were renamed
     * to `schedulingEnable` here, the map entry would stop firing and go on looking correct; the
     * day a genuinely new divergence needed the same spelling, someone would find a rule already
     * "handling" it. Both halves of every entry are therefore required to exist on their own side.
     */
    @Test
    fun `every spelling delta names a function and a case that both still exist`() {
        val ios = iosEndpoints()
        val androidRaw = androidRawEndpoints()

        val deadKeys = EndpointParity.SPELLING_DELTAS.keys.filterNot { it in androidRaw }.sorted()
        assertEquals(
            "These SPELLING_DELTAS keys name no `suspend fun` on any API interface in this " +
                "package, so the rule normalises nothing and silently looks like it is working. " +
                "Delete the entry, or correct it to the function's current name.",
            emptyList<String>(),
            deadKeys,
        )

        val deadValues = EndpointParity.SPELLING_DELTAS.values.filterNot { it in ios }.sorted()
        assertEquals(
            "These SPELLING_DELTAS values name no case in EndpointID.swift, so the rule maps an " +
                "Android endpoint onto an iOS name that does not exist — which reports the " +
                "endpoint as missing on iOS AND as missing on Android at the same time.",
            emptyList<String>(),
            deadValues,
        )
    }

    /**
     * ⛔ THE RATCHET, AND THE HALF THAT MATTERS MOST. Asserting EQUALITY means a new `EndpointID`
     * case fails here until somebody decides what it is: implemented on Kotlin, or written into
     * [EndpointParity.MISSING_ON_ANDROID]. Writing it down is then blocked by the baseline test
     * below, so the cheap way out is closed by construction and the next iOS feature cannot land
     * silently on this side.
     */
    @Test
    fun `the endpoints iOS has and this client lacks are exactly the recorded list`() {
        val onlyOnIos = (iosEndpoints() - androidEndpoints()).sorted()
        assertEquals(
            "The iOS-only endpoint set has changed (iOS source: ${iosOrigin()}). If iOS ADDED " +
                "one, implement it on an API interface or record it in " +
                "EndpointParity.MISSING_ON_ANDROID, and note that " +
                "recording it means raising MISSING_ON_ANDROID_BASELINE, which the shrink-only " +
                "test forbids. If Kotlin CAUGHT UP, delete the entry and lower the baseline by " +
                "the same number in the same commit.",
            EndpointParity.MISSING_ON_ANDROID.sorted(),
            onlyOnIos,
        )
    }

    @Test
    fun `the endpoints this client has and iOS lacks are exactly the recorded list`() {
        val onlyOnAndroid = (androidEndpoints() - iosEndpoints()).sorted()
        assertEquals(
            "The Android-only endpoint set has changed (iOS source: ${iosOrigin()}). Record the " +
                "new endpoint in EndpointParity.MISSING_ON_IOS with a sentence saying whether iOS " +
                "is expected to " +
                "gain it or this client is expected to lose it. There is no baseline on this " +
                "direction: shipping here first is a normal outcome, being undocumented is not.",
            EndpointParity.MISSING_ON_IOS.sorted(),
            onlyOnAndroid,
        )
    }

    /**
     * ⛔ SHRINK-ONLY. The same contract as `ContractManifest.NOT_YET_MODELLED_BASELINE`.
     */
    @Test
    fun `the iOS-missing list is shrink-only`() {
        assertTrue(
            "EndpointParity.MISSING_ON_ANDROID holds " +
                "${EndpointParity.MISSING_ON_ANDROID.size} entries against a baseline of " +
                "${EndpointParity.MISSING_ON_ANDROID_BASELINE}. This list may only SHRINK. If " +
                "you implemented an endpoint, lower MISSING_ON_ANDROID_BASELINE to the new size " +
                "in the same commit. If you are trying to ADD one, the answer is the endpoint, " +
                "not a bigger baseline.",
            EndpointParity.MISSING_ON_ANDROID.size <= EndpointParity.MISSING_ON_ANDROID_BASELINE,
        )
        assertEquals(
            "The baseline has slack: it is larger than the list, so the next divergence can be " +
                "absorbed without anyone noticing. A baseline with slack is a budget, not a " +
                "ratchet. Lower it to the list's size.",
            EndpointParity.MISSING_ON_ANDROID.size,
            EndpointParity.MISSING_ON_ANDROID_BASELINE,
        )
    }
}

/**
 * Blanks comments and string literals so the endpoint regexes cannot match inside prose.
 *
 * ⚠️ A CLASS RATHER THAN NESTED FUNCTIONS, for a detekt reason that is also a readability one:
 * the single-function version carried the cursor, the comment depth and the output buffer as
 * captured locals three blocks deep, which `NestedBlockDepth` flagged and which made each branch
 * hard to read in isolation. The algorithm is unchanged.
 *
 * ⚠️ KOTLIN BLOCK COMMENTS NEST, and this counts them properly. A KDoc line containing a glob like
 * `district/desk/…` opens a nested comment that never closes, and the module fails to compile with
 * "Unclosed comment" pointing at the last line of the file rather than at the prose that caused it.
 */
private class CommentStripper(private val text: String) {
    private val out = StringBuilder(text)
    private var i = 0
    private var depth = 0

    fun stripped(): String {
        while (i < text.length) {
            if (depth > 0) insideComment() else outsideComment()
        }
        return out.toString()
    }

    private fun blank(from: Int, to: Int) {
        for (k in from until to) {
            if (out[k] != '\n') {
                out[k] = ' '
            }
        }
    }

    private fun insideComment() {
        when {
            text.startsWith("/*", i) -> {
                depth++
                blank(i, i + 2)
                i += 2
            }
            text.startsWith("*/", i) -> {
                depth--
                blank(i, i + 2)
                i += 2
            }
            else -> {
                blank(i, i + 1)
                i++
            }
        }
    }

    private fun outsideComment() {
        when {
            text.startsWith("/*", i) -> {
                depth = 1
                blank(i, i + 2)
                i += 2
            }
            text.startsWith("//", i) -> skipLineComment()
            text.startsWith("\"\"\"", i) -> skipRawString()
            text[i] == '"' -> skipString()
            else -> i++
        }
    }

    private fun skipLineComment() {
        var j = i
        while (j < text.length && text[j] != '\n') {
            j++
        }
        blank(i, j)
        i = j
    }

    private fun skipRawString() {
        val found = text.indexOf("\"\"\"", i + 3)
        val end = if (found >= 0) found + 3 else text.length
        blank(i, end)
        i = end
    }

    private fun skipString() {
        var j = i + 1
        while (j < text.length && text[j] != '"') {
            if (text[j] == '\\') {
                j++
            }
            j++
        }
        val end = minOf(j + 1, text.length)
        blank(i, end)
        i = end
    }
}
