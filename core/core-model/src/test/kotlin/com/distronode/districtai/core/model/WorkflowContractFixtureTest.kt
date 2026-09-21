package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automation monitor's half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` FOR THE REASON `DeviceContractFixtureTest` IS ONE:
 * that class reached detekt's LargeClass ceiling as endpoints accumulated, and the healthy answer
 * is another class rather than a raised threshold. The strict decoder and the fixture loader are
 * shared through [ContractFixtures] precisely so the split cannot make one of them lenient — that
 * would silently retire the gate for whichever endpoints lived here.
 *
 * ⛔ WHY THIS SURFACE IS WORTH PINNING MORE THAN MOST. Everywhere else in this gate, a renamed
 * field produces a screen that will not load. Here it produces a screen that loads and says the
 * WRONG THING about live automation: a green badge where a partial run happened, a switch showing
 * "on" for a workflow that is off, "no goal configured yet" for a campaign that has one. Those are
 * answers an operator acts on, and none of them looks like a bug.
 */
class WorkflowContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `the workflow list decodes and covers BOTH latestRun branches`() {
        val response = json.decodeFromString<WorkflowListResponse>(fixture("district-workflows.json"))

        assertEquals(true, response.success)
        assertEquals("the fixture must carry more than one workflow", 2, response.workflows.size)

        // ⛔ THE REGRESSION GUARD THIS FIXTURE EXISTS FOR. `latestRun` is an explicit null for a
        // workflow that has never run — the route sends the key rather than omitting it, because a
        // missing key would be indistinguishable from a stale client. A DTO regressed to non-null
        // would throw on the most ordinary row there is: a workflow created minutes ago whose
        // trigger has not fired yet.
        assertTrue(
            "fixture must cover a workflow that HAS run",
            response.workflows.any { it.latestRun != null },
        )
        assertTrue(
            "fixture must cover a workflow that has NEVER run (the null branch)",
            response.workflows.any { it.latestRun == null },
        )

        // ⛔ BOTH `active` BRANCHES, so the switch's value cannot be pinned to a constant by a
        // fixture regenerated against an all-enabled workspace.
        assertEquals(setOf(true, false), response.workflows.map { it.active }.toSet())

        val ran = response.workflows.first { it.latestRun != null }
        assertEquals("success", ran.latestRun?.status)
        assertTrue("a latest run must carry when it started", !ran.latestRun?.startedAt.isNullOrBlank())

        // ⚠️ `createdAt` and `name` are non-optional columns server-side, so a blank one would mean
        // the DTO's default had silently absorbed a missing key rather than decoding a value.
        assertTrue(
            "every row must carry a name and a createdAt",
            response.workflows.all { it.name.isNotBlank() && it.createdAt.isNotBlank() },
        )
    }

    @Test
    fun `a trigger the client has never seen still decodes`() {
        // ⛔ THE FORWARD-COMPATIBILITY PROPERTY, AND IT IS A REAL RISK RATHER THAN A HYPOTHETICAL:
        // the server's trigger list has already grown once (`call_ended_answered` and
        // `call_ended_unanswered` were added alongside `call_ended`). `trigger` is a bare String
        // column with no Prisma enum, so an installed build has to keep DECODING and DRAWING a
        // workflow whose trigger it cannot label. A `@Serializable enum class` here would throw and
        // take out the entire list the unknown row arrived in.
        val withNewTrigger = """{"success":true,"workflows":[{"id":"w1","name":"N",""" +
            """"active":true,"trigger":"invoice_overdue","createdAt":"2026-08-18T10:00:00.000Z",""" +
            """"latestRun":null}]}"""

        val decoded = json.decodeFromString<WorkflowListResponse>(withNewTrigger)
        assertEquals("invoice_overdue", decoded.workflows.single().trigger)
    }

    @Test
    fun `run history decodes all four statuses and both explanation shapes`() {
        val response =
            json.decodeFromString<WorkflowRunsResponse>(fixture("district-workflow-runs.json"))

        assertEquals(true, response.success)

        // ⛔ ALL FOUR STATUSES THE ENGINE WRITES. The client tones them differently and `partial` is
        // the one that matters: some actions ran and some did not, which draws exactly like a
        // healthy run if it is toned as a success.
        assertEquals(
            setOf("success", "partial", "failed", "skipped"),
            response.runs.map { it.status }.toSet(),
        )

        // ⛔ A SKIP CARRIES ITS `reason` AND AN `ok` DOES NOT. That asymmetry is why `reason` is
        // nullable: a DTO that required it would throw on every successful action, and one that
        // dropped it would discard the only actionable text on the card.
        val skipped = response.runs
            .flatMap { it.actionResults }
            .filter { it.outcome == "skipped" }
        assertTrue("fixture must cover a skipped action", skipped.isNotEmpty())
        assertTrue("a skipped action must explain itself", skipped.all { !it.reason.isNullOrBlank() })
        assertTrue(
            "fixture must cover an action with NO reason (the null branch)",
            response.runs.flatMap { it.actionResults }.any { it.reason == null },
        )

        // ⛔ THE WHOLE-RUN FAILURE WITH AN EMPTY `actionResults`. The engine throws before any
        // action executes, so `error` is the ONLY explanation that exists — a client rendering only
        // the per-action rows would draw this as a failure with nothing beside it.
        val failed = response.runs.single { it.status == "failed" }
        assertEquals(emptyList<WorkflowActionResult>(), failed.actionResults)
        assertNotNull("a failed run must carry its error", failed.error)
        // ⚠️ And it did not finish. Null rather than an absent key, and a state rather than a gap.
        assertNull(failed.finishedAt)
        assertTrue(
            "fixture must cover a run that DID finish (the non-null branch)",
            response.runs.any { it.finishedAt != null },
        )
        assertTrue(
            "fixture must cover a run with NO whole-run error (the null branch)",
            response.runs.any { it.error == null },
        )

        // ⛔ PAGING IS THE SERVER'S ANSWER, NOT A DERIVATION. `hasMore` comes from a real `total`,
        // so a client re-deriving it as `runs.size < limit` would end the list early whenever a run
        // was written between two requests. Pinned as TRUE here so the "load more" half of the
        // contract is exercised rather than recorded as a constant false.
        assertTrue("the fixture must exercise the paging branch", response.hasMore)
        assertTrue("total must exceed the page", response.total > response.runs.size)
        // ⚠️ THE ECHO. The route clamps `limit` to 1..50 and REPLACES a non-numeric value rather
        // than clamping it, so what was asked for and what was applied are different questions and
        // only the echo answers the second.
        assertEquals(4, response.limit)
        assertEquals(0, response.offset)
    }

    @Test
    fun `the toggle answers success and nothing else`() {
        // ⛔ THE ABSENCE OF AN ECHO IS THE CONTRACT, AND THE CLIENT'S TOGGLE DESIGN DEPENDS ON IT.
        // `PATCH /api/district/workflows` returns no `workflow`, no `active`, nothing about the row
        // it wrote — which is why the app flips optimistically and reverts on failure rather than
        // adopting a response. If a field ever appears here, that choice should be revisited, and
        // this is where it surfaces.
        val raw = fixture("district-workflow-toggle.json")
        val decoded = json.decodeFromString<WorkflowToggleResponse>(raw)

        assertEquals(true, decoded.success)
        assertFalse("an echoed row here would change the client's toggle design", raw.contains("active"))
        assertFalse(raw.contains("workflow"))
    }

    @Test
    fun `campaign status decodes both the populated and the all-absent branch`() {
        val populated =
            json.decodeFromString<CampaignStatusResponse>(fixture("district-campaign-status.json"))
        assertEquals(true, populated.success)
        // ⚠️ `campaign` IS NULLABLE ON THE DTO ONLY SO A `{}` BODY CAN DECODE AT ALL. The route
        // always sends the object on a 200, and `WorkflowsRepository` treats an absent one as
        // contract drift rather than as an unconfigured campaign — so a null here would mean the
        // fixture had stopped exercising the thing the screen renders.
        assertNotNull("the route always sends a campaign object", populated.campaign)
        val running = populated.campaign!!
        assertEquals(true, running.infiniteSdrEnabled)
        assertEquals(25, running.sdrBatchSize)
        assertEquals("Book demos with lapsed trials", running.sdrCampaignGoal)

        // ⛔ THE ALL-ABSENT BRANCH, AND IT IS THE COMMON ONE. `campaignSettings` is a nullable Json
        // column; a workspace that never opened the campaigns tab has no key at all. The route
        // normalises absent and off into ONE rendering, which is what stops "we have no campaign"
        // and "the campaign is off" being two different screens for the same state.
        //
        // ⚠️ `sdrBatchSize` IS NULL RATHER THAN 0, deliberately: the settings PATCH floors it to at
        // least 1, so 0 is a value that could never have been stored and a rendered "0" would be a
        // batch size nobody configured. Same for the goal — the PATCH writes `goal || ""`, so an
        // empty string and an absent key are the same state to a reader and only one is on the wire.
        val empty = json.decodeFromString<CampaignStatusResponse>(
            fixture("district-campaign-status-empty.json"),
        )
        assertEquals(true, empty.success)
        val idle = empty.campaign!!
        assertEquals(false, idle.infiniteSdrEnabled)
        assertNull("null, never 0 — see the ⚠️ above", idle.sdrBatchSize)
        assertNull(idle.sdrCampaignGoal)
    }

    @Test
    fun `the pause PATCH answers in the GET's shape and proves the merge`() {
        // ⛔ ONE DTO FOR BOTH VERBS, AND THIS FIXTURE IS WHAT MAKES THAT CLAIM CHECKABLE. The
        // route derives its reply from the object it merged rather than re-reading it, so the
        // client renders the result of a pause without a second round trip. A PATCH that started
        // answering `{success:true}` — the shape every other write here uses — would decode
        // perfectly into this type with `campaign: null`, and the repository would report contract
        // drift rather than a paused campaign. That is why the null branch is asserted below.
        val paused =
            json.decodeFromString<CampaignStatusResponse>(fixture("district-campaign-pause.json"))

        assertEquals(true, paused.success)
        assertNotNull("the PATCH must echo the campaign, not a bare success", paused.campaign)
        val campaign = paused.campaign!!
        assertEquals(false, campaign.infiniteSdrEnabled)

        // ⛔ THE MERGE, WHICH IS THE ENTIRE REASON THIS ROUTE EXISTS RATHER THAN
        // `campaign-settings`. That route rebuilds all three SDR fields from its request body
        // (`sdrCampaignGoal: goal || ""`, `sdrBatchSize: floor(Number(size) || 1)`), so the same
        // `{infiniteSdrEnabled:false}` sent one path over answers 200 with the goal WIPED and the
        // batch size at 1. Both survivors are pinned here by VALUE, because a regression to the
        // destructive route produces a perfectly well-formed response — an empty goal and a batch
        // size of 1 are decodable, plausible, and wrong.
        assertEquals(
            "the stored batch size must survive a pause — a 1 here means the wrong route was used",
            25,
            campaign.sdrBatchSize,
        )
        assertEquals(
            "the goal must survive a pause — a null here means the wrong route was used",
            "Book demos with lapsed trials",
            campaign.sdrCampaignGoal,
        )

        // ⚠️ AND IT IS THE OPPOSITE OF THE GET FIXTURE'S VALUE. Both files carry the same batch
        // size and goal and disagree on exactly one field, which is the pair that shows a pause
        // changed one key and nothing else.
        val running =
            json.decodeFromString<CampaignStatusResponse>(fixture("district-campaign-status.json"))
        assertEquals(true, running.campaign!!.infiniteSdrEnabled)
        assertEquals(running.campaign!!.sdrBatchSize, campaign.sdrBatchSize)
        assertEquals(running.campaign!!.sdrCampaignGoal, campaign.sdrCampaignGoal)
    }

    @Test
    fun `workflow fixtures survive a round trip in both encodings`() {
        // ⛔ THE RUN HISTORY ESPECIALLY. One run has `finishedAt: null` and an EMPTY
        // `actionResults`, and one action has no `reason` — all three of which the terse encoding
        // writes as ABSENT, the same shape the server produces. A default that swallowed any of
        // them (an empty string for the reason, say) would decode and re-encode to something
        // different, which is what this catches and what no decode assertion above can.
        val verbose = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val terse = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        fun <T> roundTrip(serializer: KSerializer<T>, fixtureName: String) {
            val decoded = json.decodeFromString(serializer, fixture(fixtureName))
            assertEquals(
                "$fixtureName must survive a round trip through an explicit-nulls encoding",
                decoded,
                json.decodeFromString(serializer, verbose.encodeToString(serializer, decoded)),
            )
            assertEquals(
                "$fixtureName must survive a round trip in the server's own omit-defaults shape",
                decoded,
                json.decodeFromString(serializer, terse.encodeToString(serializer, decoded)),
            )
        }

        roundTrip(WorkflowListResponse.serializer(), "district-workflows.json")
        roundTrip(WorkflowRunsResponse.serializer(), "district-workflow-runs.json")
        roundTrip(WorkflowToggleResponse.serializer(), "district-workflow-toggle.json")
        roundTrip(CampaignStatusResponse.serializer(), "district-campaign-status.json")
        roundTrip(CampaignStatusResponse.serializer(), "district-campaign-status-empty.json")
        roundTrip(CampaignStatusResponse.serializer(), "district-campaign-pause.json")
    }

    @Test
    fun `an unmodelled field on a workflow row is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO. The three contract classes now
        // share one `Json` instance, so if this ever passes, the shared decoder has been relaxed
        // and EVERY half of the gate has quietly stopped protecting anything. Duplicated
        // deliberately, exactly as `DeviceContractFixtureTest` duplicates it.
        val withExtraField = """{"success":true,"workflows":[{"id":"w1","name":"N","active":true,""" +
            """"trigger":"call_ended","createdAt":"2026-08-18T10:00:00.000Z","latestRun":null,""" +
            """"brandNewServerField":"boom"}]}"""

        val failure = runCatching { json.decodeFromString<WorkflowListResponse>(withExtraField) }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
