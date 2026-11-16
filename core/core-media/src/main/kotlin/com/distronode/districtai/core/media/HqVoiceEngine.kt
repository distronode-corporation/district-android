package com.distronode.districtai.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The media side of a District HQ voice session: join one encrypted room, publish the microphone
 * and nothing else, and report what the room says. The app's session holds the rules.
 *
 * ⛔ A SECOND ENGINE BESIDE [CallEngine], NOT A WIDENING OF IT. Nothing in the call path is touched:
 * the HQ room has no video, no participant grid and no Telecom connection, and what it needs that a
 * call does not (the transcript, the agent's state, the proposal packets, the focus loss) is here.
 *
 * ⛔ NO SDK TYPE CROSSES THIS INTERFACE, for [CallEngine]'s reason. Packets arrive as raw bytes and
 * the agent's state as its raw attribute, so the parsing stays in plain, tested code.
 */
interface HqVoiceEngine {
    /** What the room says, in order. Ends when [disconnect] is called. */
    val events: Flow<HqVoiceEngineEvent>

    /** Joins the room; throws when the join fails. The microphone is OFF after it. */
    suspend fun connect(url: String, token: String)

    /** Turns the microphone on or off; answers the state the SDK is left in. */
    suspend fun setMicrophone(enabled: Boolean): Boolean

    /** Leaves the room and releases the audio. Idempotent. */
    suspend fun disconnect()
}

/** What an HQ voice room tells the session. */
sealed interface HqVoiceEngineEvent {
    /** The room closed on its own. */
    data object Disconnected : HqVoiceEngineEvent

    /** The agent participant left the room. */
    data object AgentLeft : HqVoiceEngineEvent

    /**
     * ⛔ THE AUDIO WAS TAKEN: a phone call, a District call ringing, or another app's audio. The
     * session ends, as iOS ends it for a ring: the member cannot hear HQ or be heard.
     */
    data object AudioInterrupted : HqVoiceEngineEvent

    /** One finished piece of the transcript. [fromMember] is the member's own words, otherwise HQ's. */
    data class Transcript(val id: String, val text: String, val fromMember: Boolean) : HqVoiceEngineEvent

    /** A packet on the proposal topic, unparsed. */
    class Packet(val payload: ByteArray) : HqVoiceEngineEvent

    /** The agent's state attribute changed (raw; unknown values are the session's to ignore). */
    data class AgentState(val wire: String) : HqVoiceEngineEvent
}

/**
 * Builds one engine per session. The production factory wires the SDK.
 *
 * ⚠️ THE CALLER SUPPLIES THE SCOPE, for [CallEngineFactory]'s reason: the engine's collectors must
 * die with the session that owns them.
 */
fun interface HqVoiceEngineFactory {
    fun create(scope: CoroutineScope, e2eeKey: String): HqVoiceEngine
}
