package com.distronode.districtai.telecom

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Telecom layer, to the depth Robolectric can reach.
 *
 * ⛔ WHAT ROBOLECTRIC **CAN** DO HERE, AND IT IS MORE THAN EXPECTED: it constructs a real
 * [android.telecom.ConnectionService] subclass, builds a real [ConnectionRequest], and runs the
 * real [Connection] state machine — `setDialing`, `setActive`, `setDisconnected`, `destroy` and
 * `getState` all work against the framework classes rather than shadows, because `Connection` is
 * mostly plain Java holding an int and a listener list. So the properties that matter for an
 * OUTBOUND-only self-managed call are genuinely under test: the connection starts DIALING rather
 * than ACTIVE, it carries `PROPERTY_SELF_MANAGED`, a hang-up from the OS reaches the app, and the
 * registry never holds two.
 *
 * ⛔ WHAT IT **CANNOT** DO, and this is a real gap rather than a slow test:
 *   - `TelecomManager.placeCall` is not exercised. It requires a registered account plus device
 *     state (no emergency call in progress) that Robolectric does not model, and it THROWS rather
 *     than returning a status — which is precisely why [AndroidTelecomBridge] wraps it in
 *     `runCatching`. Nothing here proves that call reaches the framework.
 *   - The framework never actually BINDS the service, so the path from `placeCall` to
 *     `onCreateOutgoingConnection` is invoked directly by this test rather than by Telecom.
 *   - Audio routing is not modelled at all. Whether LiveKit's AudioSwitch or Telecom wins the
 *     route — the sleeper bug this milestone inherited — is an on-device question and is
 *     explicitly deferred. Nothing in this file should be read as evidence about it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictConnectionServiceTest {

    private val service = DistrictConnectionService()

    @After
    fun tearDown() {
        DistrictCallRegistry.disarm()
        DistrictCallRegistry.current()?.let { DistrictCallRegistry.release(it) }
    }

    private fun request(number: String? = "+14165550100"): ConnectionRequest {
        val handle = PhoneAccountHandle(
            ComponentName(
                ApplicationProvider.getApplicationContext<android.content.Context>(),
                DistrictConnectionService::class.java,
            ),
            AndroidTelecomBridge.ACCOUNT_ID,
        )
        val address = number?.let { Uri.fromParts(PhoneAccount.SCHEME_TEL, it, null) }
        return ConnectionRequest(handle, address, Bundle())
    }

    @Test
    fun `an outgoing connection starts DIALING, not ACTIVE`() {
        // ⛔ THE SERVER RETURNS BEFORE THE CALLEE'S PHONE HAS RUNG — it deliberately does not wait
        // for an answer — so a connection that went straight to ACTIVE would tell the OS a
        // conversation is under way while the far end is still ringing, and would start the
        // system's own call duration counter at the wrong moment.
        val connection = service.onCreateOutgoingConnection(null, request())

        assertNotNull(connection)
        assertEquals(Connection.STATE_DIALING, connection!!.state)
    }

    @Test
    fun `the connection is self-managed and addresses the number it was asked to dial`() {
        // ⛔ WITHOUT `PROPERTY_SELF_MANAGED` Telecom treats this as a managed connection and
        // expects the default dialer's in-call UI to drive it — so the framework draws call UI
        // this app does not own, and the two disagree immediately.
        val connection = service.onCreateOutgoingConnection(null, request())!!

        assertTrue(
            connection.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0,
        )
        assertEquals("+14165550100", connection.address.schemeSpecificPart)
        assertEquals(PhoneAccount.SCHEME_TEL, connection.address.scheme)
    }

    @Test
    fun `a request with no address is refused rather than shown as a call to nowhere`() {
        val connection = service.onCreateOutgoingConnection(null, request(number = null))

        // ⚠️ `createFailedConnection` answers a connection already in DISCONNECTED with a cause,
        // which is how a ConnectionService says "not this one" without leaving anything alive.
        assertEquals(Connection.STATE_DISCONNECTED, connection!!.state)
        assertEquals(DisconnectCause.ERROR, connection.disconnectCause.code)
    }

    @Test
    fun `a missing request or a blank number is refused the same way`() {
        listOf(
            service.onCreateOutgoingConnection(null, null),
            service.onCreateOutgoingConnection(null, request(number = "  ")),
        ).forEach { connection ->
            assertEquals(Connection.STATE_DISCONNECTED, connection!!.state)
            assertEquals(DisconnectCause.ERROR, connection.disconnectCause.code)
        }
        assertNull("nothing refused is published", DistrictCallRegistry.current())
    }

    @Test
    fun `the connection is published so the bridge can drive it`() {
        // ⛔ A PROCESS-SCOPED REGISTRY EXISTS BECAUSE THE API GIVES NO OTHER ANSWER: `placeCall`
        // returns void and the connection is constructed later, by the framework, on a different
        // object. There is no handle to pass back.
        val connection = service.onCreateOutgoingConnection(null, request())

        assertSame(connection, DistrictCallRegistry.current())
    }

    @Test
    fun `the state machine follows the call, dialing then active then disconnected`() {
        val connection = service.onCreateOutgoingConnection(null, request()) as DistrictConnection

        assertEquals(Connection.STATE_DIALING, connection.state)
        connection.setAnswered()
        assertEquals(Connection.STATE_ACTIVE, connection.state)
        connection.setDisconnectedAndDestroy(DisconnectCause(DisconnectCause.LOCAL))
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(DisconnectCause.LOCAL, connection.disconnectCause.code)
        // ⚠️ RELEASED, so nothing later drives a connection Telecom has already torn down.
        assertNull(DistrictCallRegistry.current())
    }

    @Test
    fun `a hang-up from the OS reaches the armed handler`() {
        // ⛔ WITHOUT THIS PATH THE MEDIA OUTLIVES THE USER'S BELIEF THAT THEY HUNG UP. A
        // self-managed call gets an OS notification and responds to a headset button; both arrive
        // as `onDisconnect`, and a live microphone the user thinks is off is the outcome of
        // ignoring them.
        var hangUps = 0
        DistrictCallRegistry.arm(onSystemHangUp = { hangUps++ })
        val connection = service.onCreateOutgoingConnection(null, request()) as DistrictConnection

        connection.onDisconnect()
        // ⚠️ `onAbort` IS HANDLED IDENTICALLY, deliberately: Telecom cancelling a call that never
        // connected is, for this app, a dial nobody answered — and the response is the same one
        // thing that must happen exactly once.
        connection.onAbort()

        assertEquals(2, hangUps)
    }

    @Test
    fun `the app's own hang-up does not re-enter through the OS path`() {
        // ⛔ THE BRIDGE DISARMS BEFORE IT DISCONNECTS. Driving the connection to a terminal state
        // can make the framework answer with `onDisconnect`, and re-entering the ViewModel's
        // teardown from the far side would make an ordinary hang-up look like the OS's.
        var hangUps = 0
        DistrictCallRegistry.arm(onSystemHangUp = { hangUps++ })
        service.onCreateOutgoingConnection(null, request())

        DistrictCallRegistry.disarm()
        DistrictCallRegistry.requestHangUp()

        assertEquals(0, hangUps)
    }

    @Test
    fun `a second connection replaces the first and disconnects it`() {
        // ⛔ AT MOST ONE, AND IT IS A PRODUCT INVARIANT: one engine, one audio focus. A leaked
        // connection Telecom still believes is live keeps audio focus and keeps the OS suppressing
        // the ringer, indefinitely.
        val first = service.onCreateOutgoingConnection(null, request()) as DistrictConnection
        val second = service.onCreateOutgoingConnection(null, request("+14165550111")) as DistrictConnection

        assertEquals(Connection.STATE_DISCONNECTED, first.state)
        assertSame(second, DistrictCallRegistry.current())
    }

    @Test
    fun `adopting the connection already held does not disconnect it`() {
        val connection = service.onCreateOutgoingConnection(null, request()) as DistrictConnection

        DistrictCallRegistry.adopt(connection)

        assertEquals(Connection.STATE_DIALING, connection.state)
        assertSame(connection, DistrictCallRegistry.current())
    }

    @Test
    fun `a late teardown of an old connection does not evict the newer call`() {
        val first = service.onCreateOutgoingConnection(null, request()) as DistrictConnection
        val second = service.onCreateOutgoingConnection(null, request("+14165550111")) as DistrictConnection

        DistrictCallRegistry.release(first)

        assertSame(second, DistrictCallRegistry.current())
    }

    @Test
    fun `a refused outgoing connection disarms the handler`() {
        // ⚠️ Telecom refused before the call existed — an emergency call in progress, or an account
        // it will not honour. Leaving a handler armed would let a later stray callback hang up a
        // call this one has nothing to do with.
        var hangUps = 0
        DistrictCallRegistry.arm(onSystemHangUp = { hangUps++ })

        service.onCreateOutgoingConnectionFailed(null, request())
        DistrictCallRegistry.requestHangUp()

        assertEquals(0, hangUps)
    }

    // ── Inbound ──────────────────────────────────────────────────────────────

    @Test
    fun `an incoming connection starts RINGING, not DIALING and not ACTIVE`() {
        // ⛔ RINGING IS WHAT MAKES THE REST OF TELECOM WORK: it suppresses a second incoming call,
        // it is what a headset button's answer reaches, and it is what the OS's own call surfaces
        // render. ACTIVE would be a call nobody agreed to; DIALING would be an outbound call the OS
        // then offers to hang up rather than to answer.
        val connection = service.onCreateIncomingConnection(null, request())

        assertEquals(Connection.STATE_RINGING, connection.state)
        assertTrue(connection.connectionProperties and Connection.PROPERTY_SELF_MANAGED != 0)
    }

    @Test
    fun `an incoming connection carries no address, because the push carries no caller`() {
        // ⛔ THE PAYLOAD'S PRIVACY DISCIPLINE REACHING THE TELECOM LAYER. The server's push sender sends
        // identifiers only — no number, no name — because a push is readable by the OS and by any
        // notification-listener app. Inventing an address (the call id in a `tel:` URI, say) would
        // write a meaningless string into the system call log, on every device, forever.
        val connection = service.onCreateIncomingConnection(null, request())

        assertNull(connection.address)
        assertEquals(TelecomManager.PRESENTATION_UNKNOWN, connection.addressPresentation)
    }

    @Test
    fun `an incoming request is never refused for missing data, unlike an outgoing one`() {
        // ⛔ THE OPPOSITE RULE FROM THE OUTGOING PATH, AND RIGHT FOR THE OPPOSITE REASON. An outgoing
        // request echoes back the address the bridge supplied, so a missing one means Telecom would
        // not know what to display. An incoming one carries whatever `addNewIncomingCall`'s extras
        // carried, and this app deliberately supplies none — so there is no missing datum that could
        // justify a failed connection, and one here would leave the ring notification and the
        // controller believing a call is live while Telecom had torn its half down.
        val connection = service.onCreateIncomingConnection(null, request(number = null))

        assertEquals(Connection.STATE_RINGING, connection.state)
    }

    @Test
    fun `an incoming connection is published so the controller can drive it`() {
        val connection = service.onCreateIncomingConnection(null, request())

        assertSame(connection, DistrictCallRegistry.current())
    }

    @Test
    fun `an answer from a system surface reaches the armed handler`() {
        // ⛔ WITHOUT THIS WIRE, A CAR HEAD UNIT'S Answer BUTTON PUTS THE CONNECTION ACTIVE WHILE THIS
        // APP NEVER JOINS THE ROOM — silence, on a call the OS says is connected.
        var answers = 0
        DistrictCallRegistry.arm(onSystemHangUp = {}, onSystemAnswer = { answers++ })
        val connection = service.onCreateIncomingConnection(null, request()) as DistrictConnection

        connection.onAnswer()

        assertEquals(1, answers)
    }

    @Test
    fun `an outbound call arms no answer handler, so a stray answer does nothing`() {
        // ⛔ AN OUTBOUND CALL CANNOT BE ANSWERED BY THE OS — it is dialling — so the outbound arm
        // leaves the handler null. Acting on a stray callback there would put a call ACTIVE that
        // this app never joined.
        var answers = 0
        DistrictCallRegistry.arm(onSystemHangUp = {})
        DistrictCallRegistry.requestAnswer()

        assertEquals(0, answers)
    }

    @Test
    fun `a rejection from a system surface is handled as a hang-up, sending nothing`() {
        // ⛔ A DECLINED RING AND AN UNANSWERED ONE MUST LOOK IDENTICAL FROM OUTSIDE — see
        // IncomingCallController — so there is no separate path for `onReject` to take.
        var hangUps = 0
        DistrictCallRegistry.arm(onSystemHangUp = { hangUps++ })
        val connection = service.onCreateIncomingConnection(null, request()) as DistrictConnection

        connection.onReject()

        assertEquals(1, hangUps)
    }

    @Test
    fun `disarm clears the answer handler too`() {
        // ⛔ A DISARM THAT LEFT THE ANSWER HANDLER BEHIND WOULD LET A STRAY CALLBACK FROM A CALL THE
        // OS HAS ALREADY TORN DOWN ANSWER THE NEXT ONE — the same stale-handler failure the hang-up
        // half was written to prevent, in the one direction where the consequence is joining a
        // stranger's conversation rather than ending your own.
        var answers = 0
        DistrictCallRegistry.arm(onSystemHangUp = {}, onSystemAnswer = { answers++ })

        DistrictCallRegistry.disarm()
        DistrictCallRegistry.requestAnswer()

        assertEquals(0, answers)
    }

    @Test
    fun `a refused incoming connection disarms the handler`() {
        // ⚠️ Telecom refuses an incoming call it cannot honour — most commonly because a cellular
        // call is already in progress, which is exactly when a softphone ring is least welcome.
        var hangUps = 0
        DistrictCallRegistry.arm(onSystemHangUp = { hangUps++ })

        service.onCreateIncomingConnectionFailed(null, request())
        DistrictCallRegistry.requestHangUp()

        assertEquals(0, hangUps)
    }

    @Test
    fun `an answered inbound connection goes ACTIVE only when it is told to`() {
        // ⛔ `onAnswer` ONLY REQUESTS AN ANSWER; IT DOES NOT MAKE THE CALL ACTIVE. The connection
        // goes ACTIVE when MEDIA is up, one authenticated round trip and a LiveKit join later —
        // the mirror image of the outbound path's DIALING-not-ACTIVE rule, and audible in the same
        // way: the OS's own duration counter would otherwise start at the wrong moment.
        DistrictCallRegistry.arm(onSystemHangUp = {}, onSystemAnswer = {})
        val connection = service.onCreateIncomingConnection(null, request()) as DistrictConnection

        connection.onAnswer()
        assertEquals(Connection.STATE_RINGING, connection.state)

        connection.setAnswered()
        assertEquals(Connection.STATE_ACTIVE, connection.state)
    }

    @Test
    fun `the self-managed phone account claims tel and nothing else`() {
        // ⛔ `CAPABILITY_SELF_MANAGED`, NOT `CAPABILITY_CALL_PROVIDER`. A call-provider account asks
        // to be a candidate for the user's ordinary phone calls and must be ENABLED in Settings
        // before it works at all — this app is not a dialer replacement and would simply be inert.
        // ⚠️ The deprecation on the capability is expected at compileSdk and is the right call at
        // minSdk 26; see the note on AndroidTelecomBridge.
        val handle = AndroidTelecomBridge.phoneAccountHandle(
            ApplicationProvider.getApplicationContext(),
        )
        val account = AndroidTelecomBridge.selfManagedAccount(handle)

        @Suppress("DEPRECATION")
        assertTrue(account.capabilities and PhoneAccount.CAPABILITY_SELF_MANAGED != 0)
        assertTrue(account.capabilities and PhoneAccount.CAPABILITY_CALL_PROVIDER == 0)
        assertEquals(listOf(PhoneAccount.SCHEME_TEL), account.supportedUriSchemes)
        // ⚠️ THE ACCOUNT ID IS A STABLE CONSTANT and must stay one: it is half of the identity
        // Telecom stores registrations against, so changing it strands the old registration.
        assertEquals(AndroidTelecomBridge.ACCOUNT_ID, handle.id)
    }
}
