package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.media.HqVoiceEngine
import com.distronode.districtai.core.media.HqVoiceEngineEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A scripted [HqVoiceEngine]: no room, no network. */
class FakeHqVoiceEngine(val key: String) : HqVoiceEngine {
    private val channel = Channel<HqVoiceEngineEvent>(Channel.UNLIMITED)
    override val events: Flow<HqVoiceEngineEvent> = channel.receiveAsFlow()

    var connectGate: CompletableDeferred<Unit>? = null
    var connectError: Exception? = null
    val joined = mutableListOf<Pair<String, String>>()

    /** Each microphone change asked for, in order. */
    val microphone = mutableListOf<Boolean>()
    val microphoneGates = ArrayDeque<CompletableDeferred<Unit>>()
    var microphoneAnswer: (Boolean) -> Boolean = { it }
    var disconnects = 0

    override suspend fun connect(url: String, token: String) {
        joined += url to token
        connectGate?.await()
        connectError?.let { throw it }
    }

    override suspend fun setMicrophone(enabled: Boolean): Boolean {
        microphone += enabled
        microphoneGates.removeFirstOrNull()?.await()
        return microphoneAnswer(enabled)
    }

    override suspend fun disconnect() {
        disconnects++
        channel.close()
    }

    /** ⚠️ Fails loudly after [disconnect]: an event no session can hear proves nothing. */
    fun emit(event: HqVoiceEngineEvent) {
        check(channel.trySend(event).isSuccess) { "emit after disconnect" }
    }
}
