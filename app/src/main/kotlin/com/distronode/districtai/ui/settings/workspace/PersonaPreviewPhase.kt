package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.ui.FailureText

/**
 * Where one persona audition is.
 *
 * ⛔ NOT `CallConnectionState`, AND THE DIFFERENCE IS TWO STATES THAT ONLY EXIST HERE. An audition
 * has a MINTING step that spends a rate-limit slot and starts a charge, and a "connected but the
 * agent has not arrived yet" state that is the whole question the screen answers. Reusing the media
 * layer's enum would mean every reader deciding which of its cases apply to an audition.
 */
sealed interface PersonaPreviewPhase {

    /**
     * ⛔ THE STATE THE SHEET OPENS IN, ALWAYS. Minting a token on appear would charge for a screen
     * somebody merely looked at.
     */
    data object Idle : PersonaPreviewPhase

    /**
     * The credential is being minted.
     *
     * ⚠️ ITS OWN CASE BECAUSE IT IS THE STEP THAT IS NOT FREE AND NOT IDEMPOTENT. The route is
     * capped at 10/min per workspace and nothing in this client retries it.
     */
    data object Minting : PersonaPreviewPhase

    data object Connecting : PersonaPreviewPhase

    /**
     * Joined, and the agent is not in the room yet.
     *
     * ⛔ NOT "live". The agent is dispatched to a `preview_*` room and takes a moment to arrive;
     * telling somebody to start talking before it has would have them speak into a room nothing is
     * listening to, and then conclude the persona is broken.
     */
    data object Waiting : PersonaPreviewPhase

    /** The agent is in the room. */
    data object Live : PersonaPreviewPhase

    /**
     * ⛔ RENDERED, NEVER BRANCHED TO AN ERROR. A phone handing over between wifi and its radio
     * reconnects routinely and the SDK resumes the session itself.
     */
    data object Reconnecting : PersonaPreviewPhase

    data class Ended(val reason: PersonaPreviewEnding) : PersonaPreviewPhase

    /** ⚠️ The sentence lives on the state, not in the case, so phases stay comparable in a test. */
    data object Failed : PersonaPreviewPhase
}

/**
 * Why an audition stopped.
 *
 * ⛔ TWO ENDINGS, NOT ONE, BECAUSE THE OPERATOR DID ONLY THE FIRST OF THEM. A session dropped by
 * the server is not "you stopped it" — and on a screen whose next action costs money, telling
 * somebody they stopped something they did not is how a second billed session gets started.
 */
sealed interface PersonaPreviewEnding {

    data object Stopped : PersonaPreviewEnding

    /**
     * ⚠️ A null REASON IS THE COMMON SHAPE: an SFU removing a participant, a room being deleted and
     * a token expiring all arrive with nothing attached.
     */
    data class DroppedRemotely(val reason: String?) : PersonaPreviewEnding
}

/**
 * ⚠️ AN EXHAUSTIVE `when` WITH NO `else`, so a new phase has to be classified rather than silently
 * counted as not-running — which here would mean offering a second billed session on top of a live
 * one.
 */
val PersonaPreviewPhase.isRunning: Boolean
    get() = when (this) {
        PersonaPreviewPhase.Minting,
        PersonaPreviewPhase.Connecting,
        PersonaPreviewPhase.Waiting,
        PersonaPreviewPhase.Live,
        PersonaPreviewPhase.Reconnecting,
        -> true
        PersonaPreviewPhase.Idle,
        PersonaPreviewPhase.Failed,
        is PersonaPreviewPhase.Ended,
        -> false
    }

/**
 * One audition, as the sheet sees it.
 *
 * ⛔ [cooling] IS THE FEW SECONDS AFTER A SESSION, DURING WHICH ANOTHER MAY NOT BE STARTED. The
 * route's 10/min ceiling is the only thing bounding a loop of billed sessions, and a disabled
 * button for a moment is a cheaper guard than the ceiling. ⚠️ It runs after a REFUSAL too, and that
 * is the case it matters most in: the commonest refusal is the ceiling itself, and a button that
 * re-arms instantly invites somebody to spend the rest of the minute's slots finding out.
 */
data class PersonaPreviewUiState(
    val phase: PersonaPreviewPhase = PersonaPreviewPhase.Idle,
    /**
     * ⚠️ THE MINT FAILING, HELD SEPARATELY FROM [phase]. The engine never connected, so its own
     * state is still idle and would overwrite anything set there.
     */
    val failure: FailureText? = null,
    /** ⚠️ WHAT THE SDK ACCEPTED, not what was asked for. */
    val micEnabled: Boolean = false,
    /**
     * ⚠️ SAID OUT LOUD RATHER THAN LEFT AS SILENCE. A denied microphone still leaves a usable
     * audition — the agent greets and can be heard — but it is fatal to the point of one, so the
     * session goes ahead and the screen says why nothing is being heard back.
     */
    val microphoneDenied: Boolean = false,
    val agentPresent: Boolean = false,
    /**
     * ⚠️ THE ANSWER TO "is it actually saying anything", which on an audition is the question. The
     * Android media layer publishes `isSpeaking` per participant rather than a level, so this is a
     * boolean rather than iOS's bar — a difference in the SDK, not in the intent.
     */
    val agentSpeaking: Boolean = false,
    val cooling: Boolean = false,
    /** ⚠️ Incremented to ask the screen for the OS microphone prompt, mirroring the dialer. */
    val microphoneRequest: Int = 0,
) {

    val canStart: Boolean get() = !phase.isRunning && !cooling
}
