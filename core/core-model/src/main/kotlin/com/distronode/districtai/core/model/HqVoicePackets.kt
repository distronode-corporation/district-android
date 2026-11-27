// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A change the voice agent proposed. Nothing has been changed yet. */
data class HqVoiceProposal(
    val proposalId: String,
    /** The write, for example `update_persona`. Not for display. */
    val tool: String,
    /** The write's arguments, returned untouched on confirmation. */
    val args: JsonObject,
    /** One sentence saying what the change would do. May be empty. */
    val summary: String,
)

/** What became of a proposal the member resolved by voice. */
enum class HqResolutionStatus(val wire: String) {
    APPLIED("applied"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(wire: String?): HqResolutionStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** A proposal resolved by voice. */
data class HqVoiceResolution(
    val proposalId: String,
    val status: HqResolutionStatus,
    /** Why a change could not be made, in English: for diagnostics, not for a screen. */
    val error: String?,
)

/** The agent's state, from its [HqVoice.AGENT_STATE_ATTRIBUTE]. */
enum class HqAgentState(val wire: String) {
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    ;

    companion object {
        /** The state, or null for a value this client does not know. */
        fun fromWire(wire: String?): HqAgentState? = entries.firstOrNull { it.wire == wire }
    }
}

/** One packet the agent sends on [HqVoice.PROPOSAL_TOPIC]. */
sealed interface HqVoicePacket {
    data class Proposal(val proposal: HqVoiceProposal) : HqVoicePacket

    data class Resolved(val resolution: HqVoiceResolution) : HqVoicePacket

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The packet in [payload], or null for anything that is not one: not JSON, not an object,
         * an unknown type, or a required field missing, empty or of the wrong kind. Never throws.
         *
         * The web client's rules, as iOS reads them: a proposal needs a non-empty id and tool, an
         * object of arguments and a string summary (which may be empty); a resolution needs a
         * non-empty id, one of the three statuses and `via` equal to `voice`.
         */
        fun read(payload: ByteArray): HqVoicePacket? {
            val fields = runCatching { json.parseToJsonElement(payload.decodeToString()) }
                .getOrNull() as? JsonObject ?: return null
            return when (fields.string("type")) {
                "HQ_PROPOSAL" -> proposal(fields)
                "HQ_PROPOSAL_RESOLVED" -> resolved(fields)
                else -> null
            }
        }

        private fun proposal(fields: JsonObject): HqVoicePacket? {
            val proposalId = fields.filled("proposalId") ?: return null
            val tool = fields.filled("tool") ?: return null
            val args = fields["args"] as? JsonObject ?: return null
            val summary = fields.string("summary") ?: return null
            return Proposal(HqVoiceProposal(proposalId, tool, args, summary))
        }

        private fun resolved(fields: JsonObject): HqVoicePacket? {
            val proposalId = fields.filled("proposalId") ?: return null
            val status = HqResolutionStatus.fromWire(fields.string("status")) ?: return null
            if (fields.string("via") != "voice") return null
            return Resolved(HqVoiceResolution(proposalId, status, fields.filled("error")))
        }

        /** A JSON string's content; null for a missing key, a number, a boolean or a null. */
        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.filled(key: String): String? = string(key)?.takeIf { it.isNotEmpty() }
    }
}
