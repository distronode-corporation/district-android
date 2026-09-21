package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.DirectoryEntry
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.directoryPatch
import com.distronode.districtai.core.model.routingRulesRequest
import com.distronode.districtai.core.model.ToolConfig
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read every workspace-settings form hydrates from, and the two writes that follow it.
 *
 * ⛔ WHAT THIS FILE IS ACTUALLY PROTECTING. `PATCH workspace/tools` replaces
 * `toolConfig.allowedTools` with exactly the array it receives, so the two ways this layer can
 * cause real damage are (1) reporting a structurally-empty 200 as a usable config, which a form
 * would then save back as a deletion, and (2) reporting a landed write as failed, which invites a
 * second save from stale state. Both have their own test below.
 */
class WorkspaceConfigRepositoryTest {

    private val loadedConfig = WorkspaceConfig(
        aiPersona = AiPersona(name = "Ada", greeting = "Hello", dgiEnabled = true),
        toolConfig = ToolConfig(
            allowedTools = listOf("search_knowledge_base", "transfer_to_agent", "leave_message"),
            supportPhoneNumber = "+14165550123",
        ),
    )

    private fun api(config: WorkspaceConfig = loadedConfig) = FakeDistrictApi().apply {
        workspaceConfigResult = ApiResult.Success(
            WorkspaceConfigResponse(success = true, config = config),
        )
    }

    // ── The read ─────────────────────────────────────────────────────────────

    @Test
    fun `load returns the config and sends the workspace it was asked for`() = runTest {
        val api = api()
        val result = WorkspaceConfigRepository(api).load("ws-1")

        assertEquals(loadedConfig, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), api.configRequests)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an unconfigured workspace`() =
        runTest {
            // ⛔ THE ONE THAT WOULD CAUSE A WIPE. Every field of the response DTO has a default, so
            // a `{}` body decodes into a perfectly well-formed "no persona, no tools, no
            // directory" — which is exactly the shape a form would then save back through a route
            // that replaces the stored array. It has to reach the caller as a FAILURE so the
            // screen renders retry rather than an empty form.
            val api = FakeDistrictApi().apply {
                workspaceConfigResult = ApiResult.Success(WorkspaceConfigResponse())
            }

            val result = WorkspaceConfigRepository(api).load("ws-1")

            assertTrue(
                "an unaffirmed envelope must be a DecodeFailure, never an empty config",
                result is ApiResult.DecodeFailure,
            )
        }

    @Test
    fun `success with no config at all is also drift rather than an empty config`() = runTest {
        // ⚠️ The route always emits `config`, so its absence cannot be a state. Defaulting it to
        // an empty [WorkspaceConfig] here would be the same wipe by a different door.
        val api = FakeDistrictApi().apply {
            workspaceConfigResult = ApiResult.Success(WorkspaceConfigResponse(success = true))
        }

        val result = WorkspaceConfigRepository(api).load("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a viewer's 403 is passed through rather than translated`() = runTest {
        // ⚠️ Unusually for this surface, the READ excludes `viewer` too — the payload is staff
        // transfer numbers and the operator's prompt. The UI hides the entry, but the role can
        // change between the list being fetched and the screen being opened, so this has to
        // survive as a Forbidden the screen can word properly.
        val api = FakeDistrictApi().apply {
            workspaceConfigResult = ApiResult.Forbidden("Forbidden")
        }

        val result = WorkspaceConfigRepository(api).load("ws-1")

        assertTrue(result is ApiResult.Forbidden)
    }

    @Test
    fun `an absent allowlist stays absent, because absent means every tool is on`() = runTest {
        // ⛔ THE FRESH-WORKSPACE BRANCH. `toolConfig` is null on the day a workspace is created,
        // and the web reads that as EVERY capability enabled. Nothing in this layer may quietly
        // substitute an empty list for it.
        val api = api(WorkspaceConfig())

        val result = WorkspaceConfigRepository(api).load("ws-1")

        assertNull((result as ApiResult.Success).value.toolConfig?.allowedTools)
    }

    // ── The persona write ────────────────────────────────────────────────────

    @Test
    fun `savePersona sends the request verbatim and then RE-READS`() = runTest {
        // ⛔ THE RE-READ IS NOT BOOKKEEPING. Neither route echoes the config it wrote, so without
        // it the screen would keep rendering its own optimistic edit as though the server had
        // confirmed it — and the next save would be built on a client-side belief.
        val api = api()
        val request = PersonaPatchRequest(workspaceId = "ws-1", greeting = "")

        val outcome = WorkspaceConfigRepository(api).savePersona(request)

        assertEquals(listOf(request), api.personaPatches)
        // ⚠️ ONE read, and it is the one AFTER the write: `load` was never called by the caller
        // here, so a second entry would mean the repository was reading twice per save.
        assertEquals(listOf("ws-1"), api.configRequests)
        assertEquals(loadedConfig, (outcome as SaveOutcome.Saved).config)
    }

    @Test
    fun `an empty string reaches the wire, because that is how a field is cleared`() = runTest {
        // ⛔ NULL AND `""` ARE DIFFERENT INSTRUCTIONS. The server merges presence-based, so a null
        // means "leave it alone" and an empty string is stored verbatim — exactly what the web
        // form posts for a cleared box. Anything here that "helpfully" normalised blank to null
        // would make a cleared greeting silently un-clearable, with a success message.
        val api = api()
        val request = PersonaPatchRequest(workspaceId = "ws-1", name = "Ada", greeting = "")

        WorkspaceConfigRepository(api).savePersona(request)

        assertEquals("", api.personaPatches.single().greeting)
        assertEquals("Ada", api.personaPatches.single().name)
        assertNull(
            "an untouched field must stay null so the server preserves it",
            api.personaPatches.single().personality,
        )
    }

    @Test
    fun `a save that did not affirm success is NotSaved, and nothing is re-read`() = runTest {
        val api = api().apply {
            savePersonaResult = ApiResult.Success(WorkspaceConfigSaveResponse())
        }

        val outcome = WorkspaceConfigRepository(api)
            .savePersona(PersonaPatchRequest(workspaceId = "ws-1", name = "Ada"))

        assertTrue(outcome is SaveOutcome.NotSaved)
        // ⚠️ No re-read: there is nothing to re-read for, and one would suggest the write landed.
        assertTrue(api.configRequests.isEmpty())
    }

    @Test
    fun `a rate-limited save is NotSaved and carries the server's own refusal`() = runTest {
        // ⚠️ 30/min per WORKSPACE on the persona route. Surfaced rather than absorbed, because
        // "try again shortly" is the honest advice and a silent swallow would look like a save.
        val api = api().apply {
            savePersonaResult = ApiResult.RateLimited("Too many persona updates for this workspace.")
        }

        val outcome = WorkspaceConfigRepository(api)
            .savePersona(PersonaPatchRequest(workspaceId = "ws-1", name = "Ada"))

        assertTrue((outcome as SaveOutcome.NotSaved).failure is ApiResult.RateLimited)
        assertTrue(api.configRequests.isEmpty())
    }

    @Test
    fun `a write that landed but could not be re-read is SavedButStale, NOT a failure`() = runTest {
        // ⛔ THE DISTINCTION THIS TYPE EXISTS FOR. Reporting a landed write as failed is the
        // dangerous direction: the operator changes the form back and saves again, through a route
        // that replaces the stored array wholesale, from state that is now stale.
        val api = api().apply {
            workspaceConfigResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }

        val outcome = WorkspaceConfigRepository(api)
            .savePersona(PersonaPatchRequest(workspaceId = "ws-1", name = "Ada"))

        assertTrue(outcome is SaveOutcome.SavedButStale)
        // The write was still attempted and still accepted.
        assertEquals(1, api.personaPatches.size)
    }

    // ── The wholesale-replace write ──────────────────────────────────────────

    @Test
    fun `saveTools sends the COMPLETE list, in order, and re-reads`() = runTest {
        // ⛔ THE ONE WRITE ON THIS SURFACE THAT REPLACES RATHER THAN MERGES. Order and content are
        // both contract: the server stores the array verbatim, so a reorder is a change and a
        // dropped entry is a deletion.
        val api = api()
        val tools = listOf("search_knowledge_base", "transfer_to_creator", "leave_message")

        val outcome = WorkspaceConfigRepository(api)
            .saveTools(ToolsPatchRequest(workspaceId = "ws-1", allowedTools = tools))

        assertEquals(tools, api.toolsPatches.single().allowedTools)
        assertEquals(listOf("ws-1"), api.configRequests)
        assertEquals(loadedConfig, (outcome as SaveOutcome.Saved).config)
    }

    @Test
    fun `saveTools sends nothing else, so no other toolConfig field can be cleared`() = runTest {
        // ⛔ EVERY OTHER `toolConfig` KEY IS WRITTEN WHEN PRESENT AND `.trim()`-ed, so sending `""`
        // for the calendar id or the sender identity would CLEAR it. Omitting them is the only way
        // to leave them alone, and the request type has no field for any of them — this asserts
        // that stays true if one is ever added.
        val api = api()

        WorkspaceConfigRepository(api)
            .saveTools(ToolsPatchRequest(workspaceId = "ws-1", allowedTools = listOf("leave_message")))

        val sent = api.toolsPatches.single()
        assertEquals(
            "the tools request must carry the workspace and the list, and nothing else",
            ToolsPatchRequest(workspaceId = "ws-1", allowedTools = listOf("leave_message")),
            sent,
        )
    }

    @Test
    fun `an explicitly empty allowlist is a legitimate save`() = runTest {
        // ⚠️ Turning everything off is a real thing an operator may do, and it has to be
        // expressible — the guard against a wipe is that the list came from a LOADED baseline, not
        // that a short list is refused here.
        val api = api()

        val outcome = WorkspaceConfigRepository(api)
            .saveTools(ToolsPatchRequest(workspaceId = "ws-1", allowedTools = emptyList()))

        assertEquals(emptyList<String>(), api.toolsPatches.single().allowedTools)
        assertTrue(outcome is SaveOutcome.Saved)
    }

    @Test
    fun `a failed tools write leaves the config unread and reports NotSaved`() = runTest {
        val api = api().apply {
            saveToolsResult = ApiResult.HttpFailure(400, "Invalid payload")
        }

        val outcome = WorkspaceConfigRepository(api)
            .saveTools(ToolsPatchRequest(workspaceId = "ws-1", allowedTools = listOf("leave_message")))

        assertEquals(400, ((outcome as SaveOutcome.NotSaved).failure as ApiResult.HttpFailure).status)
        assertTrue(api.configRequests.isEmpty())
    }

    // ── The two destructive arrays ───────────────────────────────────────────

    @Test
    fun `saveDirectory sends the array whole and then RE-READS`() = runTest {
        // ⛔ THE RE-READ IS NOT BOOKKEEPING. The route answers `{success:true}` with no echo, so
        // without it the screen would keep rendering its own optimistic edit as though the server
        // had confirmed it — and the NEXT save would replace the stored array from that belief.
        val api = api()
        val entries = listOf(
            DirectoryEntry.newEntry("Ops desk", "+14165550177"),
            DirectoryEntry.newEntry("Night desk", "+14165550100"),
        )

        val outcome = WorkspaceConfigRepository(api)
            .saveDirectory(directoryPatch("ws-1", entries))

        val sent = api.directoryPatches.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(2, sent.callDirectory.size)
        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(listOf("ws-1"), api.configRequests)
    }

    @Test
    fun `an EMPTY directory save is not refused here, because the guard is the confirmation`() =
        runTest {
            // ⛔ THIS IS THE WIPE, AND IT IS ALLOWED. An operator who genuinely wants no transfer
            // targets must be able to say so; refusing it in the repository would be this layer
            // second-guessing a request it was given, and would put the real guard somewhere nobody
            // can see. The guard is the screen's confirmation, whose wording names the consequence
            // rather than a count — `DirectoryEditorScreenTest` asserts that.
            val api = api()

            val outcome = WorkspaceConfigRepository(api)
                .saveDirectory(directoryPatch("ws-1", emptyList()))

            assertEquals(0, api.directoryPatches.single().callDirectory.size)
            assertTrue(outcome is SaveOutcome.Saved)
        }

    @Test
    fun `a directory write that lands but cannot be re-read is SavedButStale, not a failure`() =
        runTest {
            // ⛔ THE MOST DANGEROUS THING TO GET WRONG ON THIS ROUTE. Reporting a landed wholesale
            // replace as failed invites a second save from state the client can no longer vouch
            // for — and this is the route that removes every human a caller can be transferred to.
            val api = api().apply {
                saveDirectoryResult = ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
                workspaceConfigResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
            }

            val outcome = WorkspaceConfigRepository(api)
                .saveDirectory(directoryPatch("ws-1", emptyList()))

            assertTrue(
                "a landed write with a failed re-read is stale, not unsaved",
                outcome is SaveOutcome.SavedButStale,
            )
        }

    @Test
    fun `a directory response that does not affirm success never reaches the re-read`() = runTest {
        // ⚠️ Same envelope rule as every sibling: a structurally empty 200 is contract drift, and
        // reporting it as a save would tell an operator their directory is what they see.
        val api = api().apply {
            saveDirectoryResult = ApiResult.Success(WorkspaceConfigSaveResponse())
        }

        val outcome = WorkspaceConfigRepository(api)
            .saveDirectory(directoryPatch("ws-1", emptyList()))

        assertTrue(outcome is SaveOutcome.NotSaved)
        assertTrue(api.configRequests.isEmpty())
    }

    @Test
    fun `saveRoutingRules sends the array whole and then RE-READS`() = runTest {
        val api = api()
        val rules = listOf(RoutingRule.newRule("rule-1"), RoutingRule.newRule("rule-2"))

        val outcome = WorkspaceConfigRepository(api)
            .saveRoutingRules(routingRulesRequest("ws-1", rules))

        val sent = api.routingPatches.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(2, sent.routingRules.size)
        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(listOf("ws-1"), api.configRequests)
    }

    @Test
    fun `a routing 400 is surfaced verbatim, because it names the value the workspace refused`() =
        runTest {
            // ⚠️ NOT A CLIENT FAULT. A workspace that restricts voices or models rejects a rule
            // naming one outside its allow-list, BY NAME — and this client cannot see either list,
            // so it deliberately does not pre-validate. Swallowing the message would leave an
            // operator with a save that fails and nothing to act on.
            val api = api().apply {
                saveRoutingResult = ApiResult.HttpFailure(400, "Invalid voice identifier: Nova")
            }

            val outcome = WorkspaceConfigRepository(api)
                .saveRoutingRules(routingRulesRequest("ws-1", listOf(RoutingRule.newRule("r"))))

            val failure = (outcome as SaveOutcome.NotSaved).failure as ApiResult.HttpFailure
            assertEquals("Invalid voice identifier: Nova", failure.message)
            assertTrue(api.configRequests.isEmpty())
        }
}
