package com.distronode.districtai.call

/**
 * An [IncomingRinger] that records what it was asked to do.
 *
 * ⚠️ THE ORDER IS THE ASSERTION, as with the foreground host: a ring that starts and is never
 * stopped is a phone that rings on after the call is gone, and that is readable from a list.
 */
internal class RecordingRinger : IncomingRinger {
    val calls: MutableList<String> = mutableListOf()

    /** True between a start and the next stop. */
    val ringing: Boolean get() = calls.lastOrNull() == "start"

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }
}
