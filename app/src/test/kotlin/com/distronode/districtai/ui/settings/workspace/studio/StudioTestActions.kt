package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix

/**
 * A Studio without a ViewModel: the same pure transitions, applied to a plain field, so a callback
 * built by [StudioLegControls] or [StudioTuningControls] is checked by what the state became.
 */
internal class StudioTestActions(var state: VoiceStudioUiState.Ready) : VoiceStudioActions {

    val calls = mutableListOf<String>()

    val mix: EngineMix get() = (state.held.engine as StudioEngine.Chained).mix

    override fun load() {
        calls += "load"
    }

    override fun selectTier(tier: String) {
        state = state.withTier(tier)
    }

    override fun applyRecipe(id: String) {
        state = state.withRecipe(id)
    }

    override fun reset() {
        state = state.withReset()
    }

    override fun selectLeg(leg: String) {
        state = state.withLeg(leg)
    }

    override fun editMix(transform: (EngineMix) -> EngineMix) {
        state = state.withMix(transform)
    }

    override fun updateHeld(transform: (StudioState) -> StudioState) {
        state = state.withHeld(transform(state.held))
    }

    override fun save() {
        calls += "save"
    }
}
