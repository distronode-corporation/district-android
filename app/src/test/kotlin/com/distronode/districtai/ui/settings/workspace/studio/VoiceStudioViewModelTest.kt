package com.distronode.districtai.ui.settings.workspace.studio

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.data.VoiceStudioRepository
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Studio's load, edits and save.
 *
 * ⛔ A SAVE IS ONLY "SAVED" WHEN THE RE-READ HOLDS WHAT WAS SENT. The PATCH answers 200 for things
 * it silently ignores, so a 200 alone is never reported as a save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceStudioViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val studio = VoiceStudioFixture.studio

    private val persona = FakePersonaApi()
    private val api = FakeDistrictApi()

    private fun viewModel(vararg reads: ApiResult<VoiceStudioResponse>): VoiceStudioViewModel {
        persona.voiceStudioResults.addAll(reads)
        return VoiceStudioViewModel(VoiceStudioRepository(persona, api), "ws-1")
    }

    private fun VoiceStudioViewModel.ready() = state.value as VoiceStudioUiState.Ready

    /** The fixture with its current engine moved to [engine], as a re-read after that save would say. */
    private fun savedAs(engine: StudioEngine, fieldsFrom: VoiceStudioResponse = studio): VoiceStudioResponse {
        val state = StudioRecipes.initialState(fieldsFrom).copy(engine = engine)
        val fields = StudioSave.fieldsOf(state, fieldsFrom.catalog.presets, fieldsFrom.language)
        val chain = when (engine) {
            is StudioEngine.Chained -> studio.current.chain.copy(engineMix = engine.mix, voice = engine.voice)
            is StudioEngine.Realtime -> studio.current.chain.copy(
                kind = "realtime",
                engineMix = null,
                realtimeModelId = engine.modelId,
                voice = engine.voice,
            )
        }
        return studio.copy(current = studio.current.copy(fields = fields, chain = chain))
    }

    @Test
    fun `a read lands as the saved persona, with nothing pending`() = runTest {
        val vm = viewModel(ApiResult.Success(studio))
        assertEquals(VoiceStudioUiState.Loading, vm.state.value)
        advanceUntilIdle()

        val ready = vm.ready()
        assertEquals(studio.current.recipeId, ready.baseRecipe)
        assertEquals(StudioLegEdits.EAR, ready.leg)
        assertTrue(ready.pending.isEmpty())
        assertEquals(listOf("ws-1"), persona.voiceStudioCalls)
    }

    @Test
    fun `a failed read offers nothing to edit, and a retry reads again`() = runTest {
        val vm = viewModel(ApiResult.Forbidden("Forbidden"), ApiResult.Success(studio))
        advanceUntilIdle()
        assertTrue(vm.state.value is VoiceStudioUiState.LoadFailed)

        vm.applyRecipe("realtime")
        vm.save()
        assertTrue("an edit without a loaded studio is a no-op", vm.state.value is VoiceStudioUiState.LoadFailed)
        vm.selectLeg(StudioLegEdits.BRAIN)

        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value is VoiceStudioUiState.Ready)
    }

    @Test
    fun `a voice change saves only the voice, and the re-read that holds it is a save`() = runTest {
        val luna = StudioRecipes.withVoice(StudioEngine.of(studio.current.chain), "aura-2-luna-en")
        val vm = viewModel(ApiResult.Success(studio), ApiResult.Success(savedAs(luna)))
        advanceUntilIdle()

        vm.updateHeld { it.copy(engine = StudioRecipes.withVoice(it.engine, "aura-2-luna-en")) }
        assertEquals(setOf(StudioKey.VOICE), vm.ready().pending)
        vm.save()
        assertEquals(StudioSaveState.Saving, vm.ready().save)
        vm.save()
        advanceUntilIdle()

        val sent = api.personaPatches.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals("aura-2-luna-en", sent.voice)
        assertNull(sent.modelId)
        assertEquals(StudioSaveState.Saved, vm.ready().save)
        assertTrue(vm.ready().pending.isEmpty())
    }

    @Test
    fun `a re-read that does not hold what was sent says the save failed and shows what is stored`() = runTest {
        val vm = viewModel(ApiResult.Success(studio), ApiResult.Success(studio))
        advanceUntilIdle()

        vm.applyRecipe("realtime")
        vm.save()
        advanceUntilIdle()

        assertEquals(StudioSaveState.Mismatch, vm.ready().save)
        assertEquals(StudioEngine.of(studio.current.chain), vm.ready().held.engine)
    }

    @Test
    fun `a refused chain keeps the edits and says why`() = runTest {
        val vm = viewModel(ApiResult.Success(studio))
        api.savePersonaResult = ApiResult.HttpFailure(400, "That voice chain cannot be saved.", "invalid_engine_mix")
        advanceUntilIdle()

        vm.editMix { it.copy(llm = it.llm.copy(temperature = 1.2)) }
        vm.save()
        advanceUntilIdle()

        val failed = vm.ready().save as StudioSaveState.Failed
        assertEquals(UiText.Literal("That voice chain cannot be saved."), failed.failure.message)
        assertEquals(setOf(StudioKey.MODEL_ID, StudioKey.ENGINE_MIX), vm.ready().pending)
    }

    @Test
    fun `a landed write whose re-read failed is stale, never a failure`() = runTest {
        val vm = viewModel(ApiResult.Success(studio), ApiResult.NetworkFailure(IOException("offline")))
        advanceUntilIdle()

        vm.applyRecipe("realtime")
        vm.save()
        advanceUntilIdle()

        val stale = vm.ready().save as StudioSaveState.SavedButStale
        assertTrue("a re-read is worth retrying", stale.failure.retryable)
    }

    @Test
    fun `nothing pending saves nothing`() = runTest {
        val vm = viewModel(ApiResult.Success(studio))
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertTrue(api.personaPatches.isEmpty())
    }

    @Test
    fun `nothing moves while a save is in the air, except which leg is shown`() = runTest {
        val vm = viewModel(ApiResult.Success(studio), ApiResult.Success(studio))
        advanceUntilIdle()
        vm.applyRecipe("realtime")
        vm.save()

        val during = vm.ready()
        vm.applyRecipe("fastest")
        vm.selectTier(StudioRecipes.LATEST)
        vm.reset()
        vm.editMix { it }
        vm.updateHeld { it.copy(realtimeTemperature = 0.1) }
        assertEquals(during, vm.ready())
        vm.selectLeg(StudioLegEdits.BRAIN)
        assertEquals(StudioLegEdits.BRAIN, vm.ready().leg)
        advanceUntilIdle()
    }

    @Test
    fun `tier, recipe, reset and mix edits route to the state`() = runTest {
        val vm = viewModel(ApiResult.Success(studio))
        advanceUntilIdle()

        vm.selectTier(StudioRecipes.LATEST)
        assertEquals(StudioRecipes.LATEST, vm.ready().tier)
        vm.applyRecipe("in-region")
        assertEquals("in-region", vm.ready().baseRecipe)
        vm.editMix { it.copy(preemptiveTts = true) }
        assertEquals(1, vm.ready().changes)
        vm.reset()
        assertEquals(0, vm.ready().changes)
    }

    @Test
    fun `the factory builds a ViewModel that reads the workspace it was given`() = runTest {
        persona.voiceStudioResults.add(ApiResult.Success(studio))
        val factory = VoiceStudioViewModel.factory(VoiceStudioRepository(persona, api), "ws-9")

        factory.create(VoiceStudioViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()

        assertEquals(listOf("ws-9"), persona.voiceStudioCalls)
    }
}
