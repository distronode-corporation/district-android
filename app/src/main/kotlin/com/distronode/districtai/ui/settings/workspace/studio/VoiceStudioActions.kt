package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix

/**
 * Everything the Studio screen can ask for. [VoiceStudioViewModel] is the implementation; a test
 * records the calls instead.
 *
 * ⚠️ AN INTERFACE RATHER THAN A CLASS OF LAMBDAS, because nine constructor parameters is a detekt
 * `LongParameterList`, and because the screen then names the ViewModel's own operations rather
 * than a second vocabulary for them. Back is not here: it is navigation, not a Studio operation.
 */
interface VoiceStudioActions {

    /** Read the Studio again (retry). */
    fun load()

    fun selectTier(tier: String)

    fun applyRecipe(id: String)

    /** Back to the engine the recipe applied. */
    fun reset()

    fun selectLeg(leg: String)

    /** A chain edit, for example `{ StudioLegEdits.brainModel(it, model, studio) }`. */
    fun editMix(transform: (EngineMix) -> EngineMix)

    /** An edit of anything else the Studio holds: the realtime engine, its temperature, the voice style. */
    fun updateHeld(transform: (StudioState) -> StudioState)

    fun save()
}
