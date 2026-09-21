package com.distronode.districtai.push

import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.MessageThreadResponse
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestInboxExtrasApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Where a tapped message notification lands.
 *
 * ⛔ THE THREAD WHEN THE ID RESOLVES, THE INBOX WHEN IT DOES NOT, AND NEVER NOWHERE. The id-to-thread
 * exchange is a network call that can fail for reasons the operator cannot act on (offline, a message
 * deleted between the push and the tap, a membership revoked since), and every one of them must still
 * land on the workspace's inbox: the destination every tap had before the resolver existed.
 */
class MessageDeepLinkResolverTest {

    private val api = TestInboxExtrasApi()

    private suspend fun resolve(link: InboxDeepLink): String = resolveMessageDeepLinkRoute(
        repository = MessageSearchRepository(api),
        link = link,
        inboxRoute = { "inbox:$it" },
        threadRoute = { "thread:${it.threadKey}|${it.counterpart}|${it.channel}" },
    )

    private val link = InboxDeepLink("ws-1", "msg-1")

    @Test
    fun `a resolved message opens its thread with the resolver's reply target`() = runTest {
        api.threadResult = ApiResult.Success(
            MessageThreadResponse(
                success = true,
                thread = MessageThreadTarget(
                    threadKey = "contact:c1",
                    contactId = "c1",
                    counterpart = "+14165550142",
                    channel = "sms",
                ),
            ),
        )

        assertEquals("thread:contact:c1|+14165550142|sms", resolve(link))
        // ⛔ THE PUSH'S WORKSPACE, NOT THE ACTIVE ONE THE ROUTE WOULD FALL BACK TO.
        assertEquals(listOf("ws-1" to "msg-1"), api.threadRequests)
    }

    @Test
    fun `offline falls back to the inbox`() = runTest {
        api.threadResult = ApiResult.NetworkFailure(IOException("offline"))

        assertEquals("inbox:ws-1", resolve(link))
    }

    @Test
    fun `a message deleted since the push falls back to the inbox`() = runTest {
        api.threadResult = ApiResult.NotFound("Message not found")

        assertEquals("inbox:ws-1", resolve(link))
    }

    @Test
    fun `a member removed since the push falls back to the inbox`() = runTest {
        api.threadResult = ApiResult.Forbidden("Not a member of this workspace")

        assertEquals("inbox:ws-1", resolve(link))
    }

    @Test
    fun `a message with no addressable counterpart falls back to the inbox`() = runTest {
        api.threadResult = ApiResult.HttpFailure(409, "Message has no addressable counterpart")

        assertEquals("inbox:ws-1", resolve(link))
    }

    @Test
    fun `a 200 that does not affirm success falls back to the inbox`() = runTest {
        // ⚠️ `threadFor` turns an unaffirmed envelope and a blank threadKey into DecodeFailure, and a
        // thread route built on a blank key would be a route to no thread at all.
        api.threadResult = ApiResult.Success(MessageThreadResponse(success = false))
        assertEquals("inbox:ws-1", resolve(link))

        api.threadResult = ApiResult.Success(MessageThreadResponse(success = true))
        assertEquals("inbox:ws-1", resolve(link))
    }

    @Test
    fun `a link without a message id goes to the inbox without a request`() = runTest {
        assertEquals("inbox:ws-1", resolve(InboxDeepLink("ws-1")))
        assertEquals("inbox:ws-1", resolve(InboxDeepLink("ws-1", "  ")))

        assertTrue("no id means nothing to exchange", api.threadRequests.isEmpty())
    }
}
