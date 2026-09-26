package com.distronode.districtai.telecom

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.os.BundleCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTelecomManager

/**
 * A Telecom that refuses a new call with whatever [refusal] holds, the way a real one does during
 * an emergency call (`IllegalStateException`) or for a dropped registration (`SecurityException`).
 * Robolectric's own shadow only models the CALL_PHONE refusal, and only on the outbound path.
 */
@Implements(TelecomManager::class)
class RefusingTelecomShadow : ShadowTelecomManager() {

    @Implementation
    override fun placeCall(address: Uri?, extras: Bundle?) {
        refusal?.let { throw it }
        super.placeCall(address, extras)
    }

    @Implementation
    override fun addNewIncomingCall(phoneAccount: PhoneAccountHandle?, extras: Bundle?) {
        refusal?.let { throw it }
        super.addNewIncomingCall(phoneAccount, extras)
    }

    companion object {
        var refusal: RuntimeException? = null
    }
}

/**
 * The real bridge against Robolectric's `TelecomManager`, with the framework's half played by the
 * real [DistrictConnectionService].
 *
 * ⛔ EVERY REFUSAL IS SWALLOWED, AND THE HANDLER IS ARMED BEFORE THE REQUEST. Losing the OS's
 * awareness of a call costs politeness; letting the throw escape would lose the call. And a
 * framework that builds the connection before the request returns must find the handler already
 * in place.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], shadows = [RefusingTelecomShadow::class])
class AndroidTelecomBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val telecom = shadowOf(context.getSystemService(TelecomManager::class.java))
    private val handle = AndroidTelecomBridge.phoneAccountHandle(context)

    private var hangUps = 0
    private var answers = 0

    @After
    fun tearDown() {
        RefusingTelecomShadow.refusal = null
        DistrictCallRegistry.disarm()
        DistrictCallRegistry.current()?.let { DistrictCallRegistry.release(it) }
    }

    private fun registeredSelfManaged(): Boolean =
        telecom.allPhoneAccounts.single { it.accountHandle == handle }.supportedUriSchemes ==
            listOf(PhoneAccount.SCHEME_TEL)

    @Test
    fun `an outbound call registers the account and places a tel call on it, never a cellular one`() {
        AndroidTelecomBridge(context).startOutgoing("+14165550100") { hangUps++ }

        assertTrue(registeredSelfManaged())
        val placed = telecom.onlyOutgoingCall
        assertEquals(Uri.fromParts(PhoneAccount.SCHEME_TEL, "+14165550100", null), placed.address)
        val account = BundleCompat.getParcelable(
            placed.extras,
            TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
            PhoneAccountHandle::class.java,
        )
        assertEquals(handle, account)
        assertFalse(placed.extras.getBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, true))

        DistrictCallRegistry.requestHangUp()
        assertEquals("the OS's hang-up reaches the dialler", 1, hangUps)
    }

    @Test
    fun `an inbound ring registers the account and adds a call with no address`() {
        AndroidTelecomBridge(context).startIncoming(onSystemAnswer = { answers++ }, onSystemHangUp = { hangUps++ })

        assertTrue(registeredSelfManaged())
        val ring = telecom.onlyIncomingCall
        assertEquals(handle, ring.phoneAccount)
        assertTrue("no caller is known, so none is claimed", ring.extras.isEmpty)

        DistrictCallRegistry.requestAnswer()
        DistrictCallRegistry.requestHangUp()
        assertEquals(1, answers)
        assertEquals(1, hangUps)
    }

    @Test
    fun `the bridge drives the connection the framework built, to ACTIVE and then to its end`() {
        val bridge = AndroidTelecomBridge(context)
        bridge.startIncoming(onSystemAnswer = { answers++ }, onSystemHangUp = { hangUps++ })
        val connection = checkNotNull(telecom.allowIncomingCall(telecom.onlyIncomingCall))
        assertSame(connection, DistrictCallRegistry.current())
        assertEquals(Connection.STATE_RINGING, connection.state)

        bridge.setActive()
        assertEquals(Connection.STATE_ACTIVE, connection.state)

        bridge.setDisconnected()
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertNull("a finished connection is released", DistrictCallRegistry.current())
        // ⛔ The app's own hang-up disarms first, so it cannot come back round as the OS's.
        DistrictCallRegistry.requestHangUp()
        assertEquals(0, hangUps)
    }

    @Test
    fun `with no connection built, active and disconnected are quiet no-ops`() {
        val bridge = AndroidTelecomBridge(context)
        bridge.startOutgoing("+14165550100") { hangUps++ }

        bridge.setActive()
        bridge.setDisconnected()

        assertNull(DistrictCallRegistry.current())
        DistrictCallRegistry.requestHangUp()
        assertEquals("disconnect disarmed the handler", 0, hangUps)
    }

    @Test
    fun `a refused outbound call is swallowed and the call proceeds without Telecom`() {
        val bridge = AndroidTelecomBridge(context)

        telecom.setCallPhonePermission(false)
        bridge.startOutgoing("+14165550100") { hangUps++ }
        telecom.setCallPhonePermission(true)
        RefusingTelecomShadow.refusal = IllegalStateException("emergency call in progress")
        bridge.startOutgoing("+14165550101") { hangUps++ }

        assertTrue(telecom.allOutgoingCalls.isEmpty())
        // The handler is still armed: it went in before the request.
        DistrictCallRegistry.requestHangUp()
        assertEquals(1, hangUps)
    }

    @Test
    fun `a refused inbound ring is swallowed, so the notification can still ring`() {
        val bridge = AndroidTelecomBridge(context)

        RefusingTelecomShadow.refusal = SecurityException("account not registered")
        bridge.startIncoming(onSystemAnswer = { answers++ }, onSystemHangUp = { hangUps++ })
        RefusingTelecomShadow.refusal = IllegalStateException("emergency call in progress")
        bridge.startIncoming(onSystemAnswer = { answers++ }, onSystemHangUp = { hangUps++ })

        assertTrue(telecom.allIncomingCalls.isEmpty())
        DistrictCallRegistry.requestAnswer()
        assertEquals(1, answers)
    }

    @Test
    fun `a device with no Telecom service arms nothing and asks nothing`() {
        val noTelecom = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? =
                if (name == Context.TELECOM_SERVICE) null else super.getSystemService(name)
        }
        val bridge = AndroidTelecomBridge(noTelecom)

        bridge.startOutgoing("+14165550100") { hangUps++ }
        bridge.startIncoming(onSystemAnswer = { answers++ }, onSystemHangUp = { hangUps++ })
        DistrictCallRegistry.requestHangUp()
        DistrictCallRegistry.requestAnswer()

        assertEquals(0, hangUps)
        assertEquals(0, answers)
        assertTrue(telecom.allOutgoingCalls.isEmpty())
        assertTrue(telecom.allIncomingCalls.isEmpty())
    }
}
