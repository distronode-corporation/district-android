package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.data.VoiceStudioRepository
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The persona form's state machine.
 *
 * ⛔ TWO PROPERTIES ARE UNDER TEST HERE AND BOTH ARE ABOUT NOT DESTROYING CONFIGURATION. A failed
 * load must produce no editable state at all, and a save must name ONLY the fields a human
 * actually changed — the server preserves what a request omits, so a form that posted its whole
 * state would overwrite the engine choice, the per-engine response-length map and the avatar
 * settings with whatever defaults it happened to hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonaFormViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val storedPersona = AiPersona(
        name = "Ada",
        greeting = "Thanks for calling.",
        personality = "Warm and concise.",
        modelId = "deepgram-pipeline",
        responseLength = mapOf("deepgram-pipeline" to "concise"),
        dgiEnabled = true,
    )

    private fun api(config: WorkspaceConfig = WorkspaceConfig(aiPersona = storedPersona)) =
        FakeDistrictApi().apply {
            workspaceConfigResult =
                ApiResult.Success(WorkspaceConfigResponse(success = true, config = config))
        }

    /**
     * ⚠️ THE SECOND REPOSITORY IS DEFAULTED TO A CATALOGUE THAT LOADS. Every test here predates the
     * engine half and is about the three free-text fields; a default that FAILED would put every
     * one of them into the read-only branch and change what they assert.
     */
    private fun viewModel(
        api: FakeDistrictApi,
        personaApi: FakePersonaApi = FakePersonaApi(),
    ) = PersonaFormViewModel(
        WorkspaceConfigRepository(api),
        PersonaOptionsRepository(personaApi),
        VoiceStudioRepository(personaApi, api),
        workspaceId = "ws-1",
    )

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load reaches no editable state, and no save is possible from it`() = runTest {
        // ⛔ THE CENTRAL RULE OF THIS PACKAGE, ASSERTED AT THE STATE LEVEL. The screen test asserts
        // the same thing at the pixel level; both are needed, because a state that CAN be built
        // will eventually be rendered by something.
        val api = api().apply { workspaceConfigResult = ApiResult.NetworkFailure(IOException("down")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.load is ConfigState.LoadFailed)
        assertNull("no persona is reachable", state.persona)
        assertFalse("and therefore no save", state.canSave)

        // A keystroke arriving in this state must not accumulate a draft — that draft would be the
        // input to a save built against a baseline that was never read.
        vm.edit(PersonaField.GREETING, "typed anyway")
        assertEquals(emptyMap<PersonaField, String>(), vm.state.value.edits)
        assertFalse(vm.state.value.canSave)

        vm.save()
        advanceUntilIdle()
        assertTrue("nothing may be written", api.personaPatches.isEmpty())
    }

    @Test
    fun `retry after a failed load recovers into an editable state`() = runTest {
        val api = api().apply { workspaceConfigResult = ApiResult.NetworkFailure(IOException("down")) }
        val vm = viewModel(api)
        advanceUntilIdle()
        assertTrue(vm.state.value.load is ConfigState.LoadFailed)

        api.workspaceConfigResult = ApiResult.Success(
            WorkspaceConfigResponse(success = true, config = WorkspaceConfig(aiPersona = storedPersona)),
        )
        vm.load()
        advanceUntilIdle()

        assertTrue(vm.state.value.load is ConfigState.Ready)
        assertEquals("Ada", vm.state.value.value(PersonaField.NAME))
    }

    @Test
    fun `a fresh workspace with no persona renders empty boxes rather than failing`() = runTest {
        // ⚠️ Null persona is a STATE, not an error — the ordinary day-one shape. Every box is
        // empty and nothing is dirty until someone types, mirroring `initialData?.name || ""`.
        val vm = viewModel(api(WorkspaceConfig()))
        advanceUntilIdle()

        assertTrue(vm.state.value.load is ConfigState.Ready)
        assertEquals("", vm.state.value.value(PersonaField.NAME))
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    // ── Dirty tracking ───────────────────────────────────────────────────────

    @Test
    fun `a field typed and typed back is not dirty`() = runTest {
        // ⛔ DIRTINESS IS MEASURED AGAINST THE LOADED BASELINE, NOT AGAINST "WAS TOUCHED". The whole
        // contract of this form is that a request names only what someone changed.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.edit(PersonaField.NAME, "Bea")
        assertTrue(vm.state.value.hasUnsavedChanges)

        vm.edit(PersonaField.NAME, "Ada")
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `save sends ONLY the dirty fields, and the untouched ones stay off the wire`() = runTest {
        // ⛔ THE ASSERTION THAT KEEPS A PHONE FROM REWRITING THE CALL BRAIN. The server merges
        // `x !== undefined ? x : existing`, so a null on the wire is a field left alone — and the
        // encoder drops nulls. Anything present here that a human did not touch is an overwrite.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.GREETING, "Good afternoon.")
        vm.save()
        advanceUntilIdle()

        val sent = api.personaPatches.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals("Good afternoon.", sent.greeting)
        assertNull("name was untouched and must be preserved server-side", sent.name)
        assertNull("personality was untouched too", sent.personality)
        assertNull("and the enrichment flag belongs to another screen entirely", sent.dgiEnabled)
    }

    @Test
    fun `clearing a box sends an empty string, not null`() = runTest {
        // ⛔ `""` CLEARS AND `null` PRESERVES, AND CONFUSING THEM IS A SILENT NO-OP WITH A SUCCESS
        // MESSAGE. The web form posts the empty string for a cleared box; this matches it.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.GREETING, "")
        assertTrue(vm.state.value.hasUnsavedChanges)

        vm.save()
        advanceUntilIdle()

        assertEquals("", api.personaPatches.single().greeting)
    }

    @Test
    fun `save is a no-op when nothing is dirty`() = runTest {
        // ⚠️ A request carrying only a workspaceId still spends a 30/min rate-limit slot and still
        // stamps `updatedAt` — it would report an edit nobody made.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertTrue(api.personaPatches.isEmpty())
    }

    // ── The save outcomes ────────────────────────────────────────────────────

    @Test
    fun `a successful save adopts the re-read config as the new baseline`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        // The server's re-read reports the saved value, which is what makes the field stop being
        // dirty rather than the client assuming it.
        api.workspaceConfigResult = ApiResult.Success(
            WorkspaceConfigResponse(
                success = true,
                config = WorkspaceConfig(aiPersona = storedPersona.copy(greeting = "Good afternoon.")),
            ),
        )
        vm.edit(PersonaField.GREETING, "Good afternoon.")
        vm.save()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.save)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals("Good afternoon.", vm.state.value.value(PersonaField.GREETING))
        // ⚠️ Two config reads: the initial load and the one the save is obliged to do, because the
        // route answers `{success:true}` and echoes nothing.
        assertEquals(listOf("ws-1", "ws-1"), api.configRequests)
    }

    @Test
    fun `a failed save KEEPS the edits`() = runTest {
        // ⛔ A FAILED SAVE THAT ALSO DISCARDED WHAT SOMEONE TYPED WOULD BE TWO LOSSES FOR ONE
        // FAULT — and on a form holding customer-authored prose, the second one is the worse.
        val api = api().apply { savePersonaResult = ApiResult.HttpFailure(500, "boom") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.GREETING, "Good afternoon.")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.Failed)
        assertEquals("Good afternoon.", vm.state.value.value(PersonaField.GREETING))
        assertTrue("and it is still savable", vm.state.value.canSave)
    }

    @Test
    fun `a write that landed but could not be re-read reports SavedButStale`() = runTest {
        // ⛔ NOT A FAILURE. Telling the operator the change did not save would invite a second save
        // from state the client can no longer vouch for.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.NAME, "Bea")
        api.workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.SavedButStale)
        // The draft is cleared: it is what the server now holds, so leaving it as "pending" would
        // invite exactly the re-save the wording warns against.
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals(1, api.personaPatches.size)
    }

    @Test
    fun `a second save while one is in flight is dropped, not queued`() = runTest {
        // ⚠️ A save is followed by a re-read; a queued second one would race that read and could
        // write a draft the operator has already seen replaced.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.NAME, "Bea")
        vm.save()
        vm.save()
        advanceUntilIdle()

        assertEquals(1, api.personaPatches.size)
    }

    @Test
    fun `a keystroke retires the previous save banner`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(PersonaField.NAME, "Bea")
        vm.save()
        advanceUntilIdle()
        assertEquals(SaveState.Saved, vm.state.value.save)

        vm.edit(PersonaField.NAME, "Cara")
        assertEquals(
            "leaving 'Saved' up while the form is dirty again claims something that is not true",
            SaveState.Idle,
            vm.state.value.save,
        )
    }

    // ── The engine half ──────────────────────────────────────────────────────

    private fun catalogueApi() = FakePersonaApi().apply {
        optionsResult = ApiResult.Success(TEST_PERSONA_OPTIONS)
    }

    @Test
    fun `a failed catalogue read leaves the text fields savable and sends no engine field`() =
        runTest {
            // ⛔ THE TEXT HALF SURVIVES A FAILED CATALOGUE READ, AND THE ENGINE HALF SENDS NOTHING.
            // Any engine value on the wire here would come from a picker that was never offered.
            val api = api()
            val personaApi = FakePersonaApi().apply {
                optionsResult = ApiResult.NetworkFailure(IOException("down"))
            }
            val vm = viewModel(api, personaApi)
            advanceUntilIdle()

            assertTrue(vm.state.value.options is PersonaOptionsState.LoadFailed)
            vm.selectLanguage("it-IT")
            vm.selectResponseLength("balanced")
            assertNull("a picker edit without a catalogue is a no-op", vm.state.value.draft)

            vm.edit(PersonaField.PERSONALITY, "Brisk.")
            vm.save()
            advanceUntilIdle()

            val sent = api.personaPatches.single()
            assertEquals("Brisk.", sent.personality)
            assertNull(sent.modelId)
            assertNull(sent.voice)
            assertNull(sent.responseLength)
            assertEquals(SaveState.Saved, vm.state.value.save)
            assertTrue(
                "a save does not conjure a catalogue it never read",
                vm.state.value.options is PersonaOptionsState.LoadFailed,
            )
        }

    @Test
    fun `a failed config read reports itself in the language section too`() = runTest {
        // ⛔ HYDRATING FROM A CATALOGUE WITHOUT THE STORED PERSONA would start every picker on a
        // default, and saving that would replace the workspace's voice with one nobody chose.
        val api = api().apply { workspaceConfigResult = ApiResult.NetworkFailure(IOException("down")) }
        val vm = viewModel(api, catalogueApi())
        advanceUntilIdle()

        assertTrue(vm.state.value.options is PersonaOptionsState.LoadFailed)
        assertNull(vm.state.value.draft)
        assertFalse(vm.state.value.canPreview)
    }

    @Test
    fun `a language change on the language-keyed engine moves the voice, and only what changed is sent`() =
        runTest {
            // ⛔ `aura-2-asteria-en` CANNOT SPEAK ITALIAN, and the route would store the mismatch.
            val api = api()
            val vm = viewModel(api, catalogueApi())
            advanceUntilIdle()
            assertTrue(vm.state.value.canPreview)

            vm.selectLanguage("it-IT")
            assertEquals("aura-2-alba-it", vm.state.value.draft!!.values.voice)
            assertTrue(vm.state.value.hasUnsavedChanges)

            api.workspaceConfigResult = ApiResult.Success(
                WorkspaceConfigResponse(
                    success = true,
                    config = WorkspaceConfig(
                        aiPersona = storedPersona.copy(language = "it-IT", voice = "aura-2-alba-it"),
                    ),
                ),
            )
            vm.save()
            advanceUntilIdle()

            val sent = api.personaPatches.single()
            assertEquals("it-IT", sent.language)
            assertEquals("aura-2-alba-it", sent.voice)
            // ⛔ THE ENGINE AND ITS TUNING ARE THE VOICE STUDIO'S: never sent from this form.
            assertNull(sent.modelId)
            assertNull(sent.temperature)
            assertNull(sent.engineMix)
            assertNull(sent.responseLength)
            assertEquals(SaveState.Saved, vm.state.value.save)
            assertFalse(vm.state.value.hasUnsavedChanges)
        }

    @Test
    fun `an answer length change carries the stored engine id, because the level is stored under it`() =
        runTest {
            val api = api()
            val vm = viewModel(api, catalogueApi())
            advanceUntilIdle()

            vm.selectResponseLength("balanced")
            vm.save()
            advanceUntilIdle()

            val sent = api.personaPatches.single()
            assertEquals("balanced", sent.responseLength)
            assertEquals("deepgram-pipeline", sent.modelId)
            assertNull(sent.language)
            assertNull(sent.voice)
        }

    @Test
    fun `the factory builds a ViewModel that reads both halves for the workspace it was given`() =
        runTest {
            val api = api()
            val vm = PersonaFormViewModel
                .factory(
                    WorkspaceConfigRepository(api),
                    PersonaOptionsRepository(catalogueApi()),
                    VoiceStudioRepository(catalogueApi(), api),
                    "ws-1",
                )
                .create(PersonaFormViewModel::class.java, CreationExtras.Empty)
            advanceUntilIdle()

            assertEquals(listOf("ws-1"), api.configRequests)
            assertEquals("Ada", vm.state.value.value(PersonaField.NAME))
            assertTrue(vm.state.value.options is PersonaOptionsState.Ready)
        }

    // ── A new language for a chain of the member's own ──────────────────────

    private val custom = storedPersona.copy(modelId = "custom-pipeline", language = "en-US")
    private fun customApi() = api(WorkspaceConfig(aiPersona = custom))

    private val fixture = VoiceStudioFixture.studio
    private val mix = fixture.current.chain.engineMix!!

    /** The Studio read for the stored custom chain, before the language save. */
    private val before =
        fixture.copy(current = fixture.current.copy(modelId = "custom-pipeline", engineMix = mix))

    /** The Studio read for the new language: the stored chain no longer fits, and [mouths] speak it. */
    private fun unfit(mouths: Set<String> = setOf("sonic-3")) = fixture.copy(
        language = "it-IT",
        current = fixture.current.copy(modelId = "custom-pipeline", engineMix = null),
        catalog = fixture.catalog.copy(
            stt = fixture.catalog.stt.map { it.copy(forLanguage = it.model == "flux-general-multi") },
            tts = fixture.catalog.tts.map { it.copy(forLanguage = it.model in mouths) },
        ),
    )

    private fun studioApi(vararg reads: ApiResult<VoiceStudioResponse>) = catalogueApi().apply {
        voiceStudioResults = ArrayDeque(reads.toList())
    }

    private fun TestScope.languageSaved(
        api: FakeDistrictApi,
        personaApi: FakePersonaApi,
    ): PersonaFormViewModel {
        val vm = viewModel(api, personaApi)
        advanceUntilIdle()
        vm.selectLanguage("it-IT")
        vm.save()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `a stranded chain is moved to models that speak the new language, saved and read back`() = runTest {
        val api = customApi()
        val refitted = before.copy(language = "it-IT")
        val personaApi = studioApi(
            ApiResult.Success(before),
            ApiResult.Success(unfit()),
            ApiResult.Success(refitted),
        )

        val vm = languageSaved(api, personaApi)

        assertEquals(PersonaRefit.Refitted, vm.state.value.refit)
        assertEquals(3, personaApi.voiceStudioCalls.size)
        val (language, moved) = api.personaPatches
        assertEquals("it-IT", language.language)
        assertNull("the language save leaves the chain alone", language.engineMix)
        assertEquals("custom-pipeline", moved.modelId)
        assertEquals("flux-general-multi", moved.engineMix!!.stt.model)
        assertEquals("sonic-3", moved.engineMix!!.tts.model)
        assertEquals(moved.engineMix!!.tts.voice, moved.voice)
        assertNull("only the chain rides on the second save", moved.language)
        assertFalse("nothing is left to save", vm.state.value.canSave)
    }

    @Test
    fun `a chain the new language still accepts is left alone and nothing is said`() = runTest {
        val api = customApi()
        val personaApi = studioApi(ApiResult.Success(before), ApiResult.Success(before))

        val vm = languageSaved(api, personaApi)

        assertNull(vm.state.value.refit)
        assertEquals(1, api.personaPatches.size)
    }

    @Test
    fun `a moved chain the read back does not show fitting says so`() = runTest {
        val api = customApi()
        val stale = studioApi(ApiResult.Success(before), ApiResult.Success(unfit()), ApiResult.Success(unfit()))
        assertEquals(PersonaRefit.NotSeen, languageSaved(api, stale).state.value.refit)

        val unread = studioApi(ApiResult.Success(before), ApiResult.Success(unfit()))
        assertEquals(PersonaRefit.NotSeen, languageSaved(customApi(), unread).state.value.refit)
    }

    @Test
    fun `a second save that fails says the chain could not be moved`() = runTest {
        val api = object : FakeDistrictApi() {
            override suspend fun savePersona(
                request: PersonaPatchRequest,
            ): ApiResult<WorkspaceConfigSaveResponse> {
                super.savePersona(request)
                return if (request.engineMix == null) {
                    ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
                } else {
                    ApiResult.NetworkFailure(IOException("down"))
                }
            }
        }.apply {
            workspaceConfigResult = ApiResult.Success(
                WorkspaceConfigResponse(success = true, config = WorkspaceConfig(aiPersona = custom)),
            )
        }
        val personaApi = studioApi(ApiResult.Success(before), ApiResult.Success(unfit()))

        val vm = languageSaved(api, personaApi)

        assertTrue(vm.state.value.refit is PersonaRefit.Failed)
        assertEquals(2, api.personaPatches.size)
        assertEquals(SaveState.Saved, vm.state.value.save)
    }

    @Test
    fun `a read that fails, before or after the language save, says the chain could not be moved`() = runTest {
        val down = ApiResult.NetworkFailure(IOException("down"))
        val after = studioApi(ApiResult.Success(before), down)
        assertTrue(languageSaved(customApi(), after).state.value.refit is PersonaRefit.Failed)

        val beforeDown = studioApi(down, ApiResult.Success(unfit()))
        val api = customApi()
        assertTrue(languageSaved(api, beforeDown).state.value.refit is PersonaRefit.Failed)
        assertEquals("the language save still lands", 1, api.personaPatches.size)
    }

    @Test
    fun `nothing that speaks the language, or no stored chain, is no fit`() = runTest {
        val noVoice = studioApi(ApiResult.Success(before), ApiResult.Success(unfit(mouths = emptySet())))
        assertEquals(PersonaRefit.NoFit, languageSaved(customApi(), noVoice).state.value.refit)

        val unsanitised = before.copy(current = before.current.copy(engineMix = null))
        val noChain = studioApi(ApiResult.Success(unsanitised), ApiResult.Success(unfit()))
        assertEquals(PersonaRefit.NoFit, languageSaved(customApi(), noChain).state.value.refit)
    }

    @Test
    fun `no refit for a fixed engine, for a save without a language, or for a language save that failed`() = runTest {
        val fixed = studioApi()
        languageSaved(api(), fixed)
        assertTrue("a fixed engine reads no Studio", fixed.voiceStudioCalls.isEmpty())

        val text = studioApi()
        val vm = viewModel(customApi(), text)
        advanceUntilIdle()
        vm.edit(PersonaField.NAME, "Grace")
        vm.save()
        advanceUntilIdle()
        assertTrue("a text-only save reads no Studio", text.voiceStudioCalls.isEmpty())

        val refused = customApi().apply {
            savePersonaResult = ApiResult.NetworkFailure(IOException("down"))
        }
        val failed = studioApi(ApiResult.Success(before))
        assertNull(languageSaved(refused, failed).state.value.refit)
        assertEquals("only the read before the save", 1, failed.voiceStudioCalls.size)
    }
}
