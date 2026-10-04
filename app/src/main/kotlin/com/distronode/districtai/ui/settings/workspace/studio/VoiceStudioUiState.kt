package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioFields
import com.distronode.districtai.core.model.VoiceStudioRecipe
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.ui.FailureText

/** What the last save did, as the Studio reports it. */
sealed interface StudioSaveState {

    data object Idle : StudioSaveState

    data object Saving : StudioSaveState

    /** Written, read back, and the re-read holds what was sent: the server's `saved` label. */
    data object Saved : StudioSaveState

    /**
     * ⛔ WRITTEN WITH A 200, AND THE RE-READ DOES NOT HOLD WHAT WAS SENT. The PATCH ignores some
     * values silently; the server's `saveFailed` label is shown over the re-read state, which is
     * what the workspace really runs on now.
     */
    data object Mismatch : StudioSaveState

    /** ⛔ Written; only the re-read failed. Not a failure: told "not saved", an operator re-saves. */
    data class SavedButStale(val failure: FailureText) : StudioSaveState

    /** Nothing was written (a refused chain is one of these). The edits are kept. */
    data class Failed(val failure: FailureText) : StudioSaveState
}

sealed interface VoiceStudioUiState {

    data object Loading : VoiceStudioUiState

    /** ⛔ A failed read offers a retry and NOTHING editable: there is no catalogue to edit against. */
    data class LoadFailed(val failure: FailureText) : VoiceStudioUiState

    /**
     * The Studio, loaded, with what it holds now.
     *
     * ⛔ EVERY TRANSITION IS A PURE FUNCTION HERE, so the ViewModel only routes calls and the rules
     * are tested without coroutines. ⚠️ A transition during a save is refused by the ViewModel,
     * not here: a keystroke landing mid-request would change the state the request was built from.
     */
    data class Ready(
        val studio: VoiceStudioResponse,
        val held: StudioState,
        /** `stable` or `latest`. */
        val tier: String,
        /** The recipe the held engine started from ("Based on Fastest, 2 changes"). */
        val baseRecipe: String,
        /** The engine as that recipe applied it, for the change count and Reset. */
        val baseEngine: StudioEngine,
        /** The leg the editor is open on. */
        val leg: String,
        val save: StudioSaveState,
    ) : VoiceStudioUiState {

        val savedEngine: StudioEngine get() = StudioEngine.of(studio.current.chain)

        /** What the persona saves as now: the baseline every edit is diffed against. */
        val savedFields: VoiceStudioFields get() = studio.current.fields

        /** What the held state would save as. */
        val heldFields: VoiceStudioFields
            get() = StudioSave.fieldsOf(held, studio.catalog.presets, studio.language)

        /** The keys a save would send. */
        val pending: Set<StudioKey> get() = StudioDiff.changedKeys(savedFields, heldFields)

        val dirty: Boolean get() = pending.isNotEmpty()

        val canSave: Boolean get() = dirty && save != StudioSaveState.Saving

        /** How many settings differ from the recipe the held engine started from. */
        val changes: Int get() = StudioDiff.countChanges(baseEngine, held.engine)

        val tiles: List<VoiceStudioRecipe> get() = StudioRecipes.tiles(studio, tier)

        /** The name of the recipe the held engine started from, as this tier calls it ("" when it has none). */
        val baseName: String get() = tiles.firstOrNull { it.id == baseRecipe }?.name.orEmpty()

        /**
         * Stable or Latest. The chosen recipe is re-applied on the new tier; "Your chain" is not a
         * tier's, and a recipe the other tier lacks leaves the held engine alone.
         */
        fun withTier(next: String): Ready {
            val moved = copy(tier = next, save = StudioSaveState.Idle)
            return if (baseRecipe == StudioRecipes.CUSTOM) moved else moved.withRecipe(baseRecipe)
        }

        /** Apply one of this tier's tiles: its engine and its bilingual flag. */
        fun withRecipe(id: String): Ready {
            val recipe = tiles.firstOrNull { it.id == id } ?: return this
            val engine = StudioRecipes.applied(recipe, savedEngine, held.engine, studio)
            return copy(
                held = held.copy(engine = engine, bilingual = recipe.bilingual),
                baseRecipe = id,
                baseEngine = engine,
                leg = legFor(engine, leg),
                save = StudioSaveState.Idle,
            )
        }

        /** Back to the engine the recipe applied. */
        fun withReset(): Ready = withHeld(held.copy(engine = baseEngine))

        fun withLeg(next: String): Ready = copy(leg = next)

        /** A chain edit. ⚠️ A no-op on a realtime engine, which has no mix. */
        fun withMix(transform: (EngineMix) -> EngineMix): Ready {
            val engine = held.engine as? StudioEngine.Chained ?: return this
            return withHeld(held.copy(engine = StudioEngine.Chained(transform(engine.mix))))
        }

        fun withHeld(next: StudioState): Ready =
            copy(held = next, leg = legFor(next.engine, leg), save = StudioSaveState.Idle)

        companion object {
            /** The Studio as the server describes the saved persona. */
            fun of(studio: VoiceStudioResponse, save: StudioSaveState = StudioSaveState.Idle): Ready {
                val held = StudioRecipes.initialState(studio)
                return Ready(
                    studio = studio,
                    held = held,
                    tier = studio.current.tier,
                    baseRecipe = studio.current.recipeId,
                    baseEngine = held.engine,
                    leg = legFor(held.engine, StudioLegEdits.EAR),
                    save = save,
                )
            }

            /** A realtime engine has one block; a chain opens on the leg it was on, or the ear. */
            private fun legFor(engine: StudioEngine, leg: String): String = when {
                engine is StudioEngine.Realtime -> StudioLegEdits.REALTIME
                leg == StudioLegEdits.REALTIME -> StudioLegEdits.EAR
                else -> leg
            }
        }
    }
}
