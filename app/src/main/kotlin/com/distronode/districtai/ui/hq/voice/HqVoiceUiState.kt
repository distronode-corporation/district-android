// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.core.model.HqResolutionStatus
import com.distronode.districtai.core.model.HqVoiceProposal
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import com.distronode.districtai.ui.FailureText

/** Everything the voice bar draws. */
data class HqVoiceUiState(
    val phase: HqVoicePhase = HqVoicePhase.Idle,
    /** Final transcript segments, in order. */
    val segments: List<HqVoiceSegment> = emptyList(),
    val agentState: HqAgentState? = null,
    val proposals: List<HqVoiceProposalCard> = emptyList(),
    val micMode: HqMicMode = HqMicMode.OPEN,
    val isMicrophoneOn: Boolean = false,
    /**
     * Above zero when the screen should ask for the microphone permission.
     *
     * ⚠️ A COUNTER, CONSUMED ONCE LAUNCHED ([HqVoiceSession.onMicrophoneRequested]), so a rotation
     * or a return to the screen never asks again, as on the Live calls screen.
     */
    val microphoneRequest: Int = 0,
)

/** Where a session stands. */
sealed interface HqVoicePhase {
    /** Whether a session is under way: Talk is replaced by its controls. */
    val isActive: Boolean get() = false

    data object Idle : HqVoicePhase

    data object Minting : HqVoicePhase {
        override val isActive: Boolean get() = true
    }

    /** ⛔ THE CONSENT NOTICE IS UP. Nothing is joined and the microphone has not been asked for. */
    data class Notice(val credential: HqVoiceTokenResponse) : HqVoicePhase {
        override val isActive: Boolean get() = true
    }

    /** The member said Start; the system is asking for the microphone. */
    data class Permission(val credential: HqVoiceTokenResponse) : HqVoicePhase {
        override val isActive: Boolean get() = true
    }

    data object Connecting : HqVoicePhase {
        override val isActive: Boolean get() = true
    }

    data object Live : HqVoicePhase {
        override val isActive: Boolean get() = true
    }

    data class Ended(val reason: HqVoiceEndReason) : HqVoicePhase
}

/** Why a session ended, which decides the sentence under Talk. */
sealed interface HqVoiceEndReason {
    /** Whether the sentence is a failure (drawn in the destructive colour). */
    val isFailure: Boolean get() = true

    data object ByMember : HqVoiceEndReason {
        override val isFailure: Boolean get() = false
    }

    /** ⚠️ ANDROID: a call, a District call's ring or another app took the audio focus. */
    data object Interrupted : HqVoiceEndReason {
        override val isFailure: Boolean get() = false
    }

    data object TimedOut : HqVoiceEndReason {
        override val isFailure: Boolean get() = false
    }

    /** The room closed on its own. */
    data object Disconnected : HqVoiceEndReason {
        override val isFailure: Boolean get() = false
    }

    /** The agent left the room while it was live. */
    data object AgentLeft : HqVoiceEndReason {
        override val isFailure: Boolean get() = false
    }

    /** Refused before anything started: a call or a meeting holds the audio. */
    data object Busy : HqVoiceEndReason

    /** No agent joined within [com.distronode.districtai.core.model.HqVoice.AGENT_JOIN_SECONDS]. Discarded. */
    data object NoAgent : HqVoiceEndReason

    data object MicrophoneDenied : HqVoiceEndReason

    data object JoinFailed : HqVoiceEndReason

    /** The service refused the token in its own sentence (the allowance or capacity). */
    data class Refused(val failure: FailureText) : HqVoiceEndReason

    data class Failed(val failure: FailureText) : HqVoiceEndReason
}

/** One finished piece of the transcript. */
data class HqVoiceSegment(val id: String, val text: String, val fromMember: Boolean)

/** A proposal the member has not resolved yet, or one just resolved. */
data class HqVoiceProposalCard(
    val proposal: HqVoiceProposal,
    val outcome: HqResolutionStatus? = null,
    val isWorking: Boolean = false,
) {
    val id: String get() = proposal.proposalId
}
