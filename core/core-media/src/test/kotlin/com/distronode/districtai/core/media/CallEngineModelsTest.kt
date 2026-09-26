package com.distronode.districtai.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam's data types, and the model contract they promise their consumers (load-bearing for
 * Compose). The SDK wrapper is tested against a mocked SDK in `LiveKitCallEngineTest`.
 */
class CallEngineModelsTest {

    @Test
    fun `video track handles are equal by track identity, not by box`() {
        // ⛔ COMPOSE SKIPS RECOMPOSITION ON EQUALITY. Two handles boxing the same track
        // must compare equal or every participant-list refresh re-renders every video
        // tile; two different tracks must not, or a track swap is silently not drawn.
        val sdkTrackA = Any()
        val sdkTrackB = Any()

        assertEquals(VideoTrackHandle(sdkTrackA, "TR_1"), VideoTrackHandle(sdkTrackB, "TR_1"))
        assertNotEquals(VideoTrackHandle(sdkTrackA, "TR_1"), VideoTrackHandle(sdkTrackA, "TR_2"))
        assertEquals(
            VideoTrackHandle(sdkTrackA, "TR_1").hashCode(),
            VideoTrackHandle(sdkTrackB, "TR_1").hashCode(),
        )
    }

    @Test
    fun `a handle is never equal to its bare track sid`() {
        // Equality is by sid, but only between handles: the sid string a handle wraps is not
        // the same tile.
        assertFalse(VideoTrackHandle(Any(), "TR_1").equals("TR_1"))
    }

    @Test
    fun `participants are value-compared so state flows deduplicate`() {
        // MutableStateFlow drops a value equal to the current one; participant refresh
        // fires on every room event, so without value equality every mute/unmute
        // anywhere re-emits the whole list to every collector.
        val a = MediaParticipant(identity = "user-abc", name = "Ada")
        val b = MediaParticipant(identity = "user-abc", name = "Ada")

        assertEquals(a, b)
        assertEquals(listOf(a), listOf(b))
    }

    @Test
    fun `connection states carry what the UI renders and nothing more`() {
        val disconnected: CallConnectionState = CallConnectionState.Disconnected("CLIENT_INITIATED")
        val failed: CallConnectionState = CallConnectionState.Failed("token expired")

        assertTrue(disconnected is CallConnectionState.Disconnected)
        assertEquals("CLIENT_INITIATED", (disconnected as CallConnectionState.Disconnected).reason)
        assertEquals("token expired", (failed as CallConnectionState.Failed).message)
        // Reason/message are optional: a clean local hang-up has neither.
        assertEquals(CallConnectionState.Disconnected(), CallConnectionState.Disconnected(null))
    }

    @Test
    fun `a join with no key CLEARS the previous room's key rather than inheriting it`() {
        // ⛔ THE RESET, WHICH IS THE WHOLE REASON THIS FUNCTION EXISTS. `LiveKitCallEngine` keeps
        // its Room as a long-lived `val` and the SDK's `e2eeOptions` survives a disconnect, so an
        // `if` with no `else` would carry an encrypted meet_ room's key into the next join. If that
        // next join is a `call_` answer, the call is encrypted against a key the SIP bridge and the
        // agent do not hold — they receive undecryptable media and the human hears one-way dead
        // air, with no exception raised anywhere. Null in must mean null out, not "unchanged".
        assertEquals(null, sharedRoomKey(null))
    }

    @Test
    fun `a blank key is normalised to no encryption rather than passed to the SDK`() {
        // ⛔ BLANK IS NOT "UNENCRYPTED". An empty passphrase derives a perfectly real AES key that
        // no other participant derives, so forwarding it would encrypt everything with a key unique
        // to this handset. Joining in the clear is the recoverable outcome; a private key is not.
        // ⚠️ Guarded here as well as in ActiveRoomViewModel on purpose: SoftphoneSession and
        // InboundCallSession reach the engine without passing through that ViewModel.
        assertEquals(null, sharedRoomKey(""))
        assertEquals(null, sharedRoomKey("   "))
    }

    @Test
    fun `a real key passes through byte for byte and is never decoded`() {
        // ⛔ THE CROSS-PLATFORM CONTRACT. Every LiveKit SDK treats this string as a PASSPHRASE:
        // it UTF-8-encodes the 44 ASCII characters and derives the AES key with PBKDF2. Decoding
        // it to 32 raw bytes here would select HKDF instead and produce a different key from the
        // same input — the client would join, publish, and be unable to decrypt anyone.
        val key = "cUwBK6pBSbabCBfQZ8VZW+bW5dOg2dwb3JG8q7E4Jc4="

        assertEquals(key, sharedRoomKey(key))
        assertEquals("the base64 TEXT of 32 bytes, not the bytes", 44, sharedRoomKey(key)!!.length)
    }

    @Test
    fun `defaults describe a joined audio participant`() {
        // A participant with no video and an open mic is the softphone's normal case;
        // the defaults must describe it so the dial screen can build one from identity
        // alone.
        val p = MediaParticipant(identity = "user-abc", name = null)

        assertTrue(p.isMicrophoneEnabled)
        assertEquals(null, p.videoTrack)
        assertEquals(false, p.isSpeaking)
    }
}
