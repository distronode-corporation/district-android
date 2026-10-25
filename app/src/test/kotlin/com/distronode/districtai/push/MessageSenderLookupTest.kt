package com.distronode.districtai.push

import com.distronode.districtai.core.model.MessageSenderResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeInboxExtrasApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender's name for a message notification.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ARE THE NULLS. Every failure must fall back to the generic
 * title, and the lookup must give up at its bound: the notification is waiting for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSenderLookupTest {

    private fun named(name: String?, success: Boolean = true) =
        FakeInboxExtrasApi().apply { senderResult = ApiResult.Success(MessageSenderResponse(success, name)) }

    @Test
    fun `a named sender is read for the push's own workspace and message`() = runTest {
        val api = named("Avery Contract")

        assertEquals("Avery Contract", MessageSenderLookup(api).name("ws-1", "msg-1"))
        assertEquals(listOf("ws-1" to "msg-1"), api.senderRequests)
    }

    @Test
    fun `the name is trimmed, and a blank one is no name`() = runTest {
        assertEquals("Avery", MessageSenderLookup(named("  Avery ")).name("ws-1", "msg-1"))
        assertNull(MessageSenderLookup(named("   ")).name("ws-1", "msg-1"))
        assertNull(MessageSenderLookup(named("")).name("ws-1", "msg-1"))
    }

    @Test
    fun `an unnamed sender, an unsuccessful answer and a refusal are all no name`() = runTest {
        // ⛔ THE ROUTE ANSWERS null RATHER THAN AN ADDRESS for a thread with no named contact.
        assertNull(MessageSenderLookup(named(null)).name("ws-1", "msg-1"))
        assertNull(MessageSenderLookup(named("Avery", success = false)).name("ws-1", "msg-1"))
        val refused = FakeInboxExtrasApi().apply {
            senderResult = ApiResult.HttpFailure(status = 403, message = "Forbidden")
        }
        assertNull(MessageSenderLookup(refused).name("ws-1", "msg-1"))
    }

    @Test
    fun `a lookup that does not answer is abandoned at its bound`() = runTest {
        val api = named("Avery").apply { senderGate = CompletableDeferred() }
        val lookup = MessageSenderLookup(api, timeoutMillis = BOUND)

        val pending = async { lookup.name("ws-1", "msg-1") }
        advanceTimeBy(BOUND - 1)
        runCurrent()
        assertFalse("still waiting just inside the bound", pending.isCompleted)

        advanceTimeBy(2)
        runCurrent()

        assertTrue(pending.isCompleted)
        assertNull(pending.await())
    }

    @Test
    fun `the production bound is four seconds`() {
        // ⚠️ Inside the iOS extension's ~5 s and well inside a push's ~10 s on a backgrounded app.
        assertEquals(4_000L, MessageSenderLookup.TIMEOUT_MILLIS)
    }

    private companion object {
        const val BOUND = 4_000L
    }
}
