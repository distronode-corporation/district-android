package com.distronode.districtai.ui.hq.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceSettingsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The member's voice settings: read, edited, and saved once. */
@OptIn(ExperimentalCoroutinesApi::class)
class HqVoiceSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeHqVoiceApi()
    private fun viewModel() = HqVoiceSettingsViewModel(HqVoiceRepository(api, FakeDistrictApi()), "ws-1")

    @Test
    fun `the settings load, and each edit changes only its own field`() = runTest(dispatcher) {
        val model = viewModel()
        assertFalse("nothing to save before the read", model.state.value.isDirty)
        advanceUntilIdle()
        assertEquals(FakeHqVoiceApi.SETTINGS, model.state.value.draft)
        assertFalse(model.state.value.isDirty)

        model.edit(HqVoiceSetting.VOICE, "Kore")
        model.edit(HqVoiceSetting.LENGTH, "brief")
        model.edit(HqVoiceSetting.PACE, "faster")
        model.edit(HqVoiceSetting.LANGUAGE, "fr")
        model.edit(HqVoiceSetting.MIC_MODE, "push")

        assertEquals(HqVoiceSettings("Kore", "brief", "faster", "fr", "push"), model.state.value.draft)
        assertTrue(model.state.value.isDirty)
    }

    @Test
    fun `a save sends the draft once, and answers it as stored`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.edit(HqVoiceSetting.MIC_MODE, "push")
        val gate = CompletableDeferred<Unit>()
        api.saveGate = gate

        model.save()
        model.save()
        advanceUntilIdle()
        assertTrue(model.state.value.isSaving)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, api.savedSettings.size)
        assertEquals("push", api.savedSettings.single().micMode)
        assertTrue(model.state.value.isSaved)
        assertFalse(model.state.value.isDirty)

        model.edit(HqVoiceSetting.VOICE, "Aoede")
        assertFalse("an edit clears the saved note", model.state.value.isSaved)
    }

    @Test
    fun `an edit made while saving is kept, and a failed save keeps the draft`() = runTest(dispatcher) {
        val model = viewModel()
        advanceUntilIdle()
        model.edit(HqVoiceSetting.VOICE, "Kore")
        val gate = CompletableDeferred<Unit>()
        api.saveGate = gate
        model.save()
        advanceUntilIdle()
        model.edit(HqVoiceSetting.VOICE, "Fenrir")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Fenrir", model.state.value.draft?.voice)
        assertEquals("Kore", model.state.value.loaded?.voice)

        api.saveGate = null
        api.saveResult = ApiResult.NetworkFailure(IOException("offline"))
        model.save()
        advanceUntilIdle()
        assertEquals("Fenrir", model.state.value.draft?.voice)
        assertTrue(model.state.value.saveFailure != null)
        assertFalse(model.state.value.isSaving)
    }

    @Test
    fun `a failed read is a failure with a retry, and nothing edits or saves before a read`() = runTest(dispatcher) {
        api.settingsResult = ApiResult.NetworkFailure(IOException("offline"))
        val model = viewModel()
        advanceUntilIdle()
        assertTrue(model.state.value.loadFailure != null)
        model.edit(HqVoiceSetting.VOICE, "Kore")
        model.save()
        advanceUntilIdle()
        assertNull(model.state.value.draft)
        assertTrue(api.savedSettings.isEmpty())

        api.settingsResult = ApiResult.Success(HqVoiceSettingsResponse(true, FakeHqVoiceApi.SETTINGS))
        model.load()
        advanceUntilIdle()
        assertNull(model.state.value.loadFailure)
        assertEquals(FakeHqVoiceApi.SETTINGS, model.state.value.draft)
    }
}
