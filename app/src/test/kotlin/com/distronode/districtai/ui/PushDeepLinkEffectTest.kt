package com.distronode.districtai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.MessageThreadResponse
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.push.InboxDeepLink
import com.distronode.districtai.push.PushDeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A tapped message notification, from the pending link to the destination it opens.
 *
 * WHAT THE PURE PIECES DO NOT PROVE. `inboxDeepLinkDecision` and `resolveMessageDeepLinkRoute` are
 * tested on their own; this drives the effect that joins them to a real [NavHostController]: the
 * link is consumed exactly once, it waits while the workspace is still unknown, a link for another
 * workspace is dropped rather than opened in the wrong one, and a resolved message lands on its
 * thread carrying the RESOLVER's reply target rather than anything derived from the thread key.
 *
 * The graph here declares the inbox and thread routes with the same templates and optional
 * arguments as `DistrictNavHost`, so navigating to a built route proves the two agree. The
 * repository is the real one over [TestInboxExtrasApi], which answers without a dispatcher hop,
 * so every step completes on the compose clock and nothing waits on wall time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PushDeepLinkEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val deepLinks = PushDeepLinks()
    private val api = TestInboxExtrasApi()
    private lateinit var navController: NavHostController
    private var workspaceId by mutableStateOf<String?>("ws-1")

    private fun render() {
        composeRule.setContent {
            val controller = rememberNavController()
            navController = controller
            NavHost(navController = controller, startDestination = HOME) {
                composable(HOME) {}
                composable(Routes.INBOX) {}
                composable(
                    Routes.THREAD,
                    arguments = listOf(ARG_REPLY_TO, ARG_REPLY_CHANNEL, ARG_THREAD_TITLE).map { name ->
                        navArgument(name) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    },
                ) {}
            }
            PushDeepLinkEffect(
                deepLinks = deepLinks,
                messageSearch = MessageSearchRepository(api),
                workspaceId = workspaceId,
                role = WorkspaceRole.AGENCY,
                navController = controller,
            )
        }
        composeRule.waitForIdle()
    }

    private fun offer(link: InboxDeepLink) {
        composeRule.runOnIdle { deepLinks.offer(link) }
        composeRule.waitForIdle()
    }

    private fun route(): String? = navController.currentBackStackEntry?.destination?.route

    private fun argument(name: String): String? = navController.currentBackStackEntry?.arguments?.getString(name)

    @Test
    fun `a resolved message opens its thread with the resolver's reply target, once`() {
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
        render()

        offer(InboxDeepLink("ws-1", "msg-1"))

        assertEquals(Routes.THREAD, route())
        assertEquals("ws-1", argument(ARG_WORKSPACE_ID))
        assertEquals("agency", argument(ARG_ROLE))
        assertEquals("contact:c1", argument(ARG_THREAD_KEY))
        assertEquals("+14165550142", argument(ARG_REPLY_TO))
        assertEquals("sms", argument(ARG_REPLY_CHANNEL))
        // No title: the route's counterpart is an address, not the list's display name.
        assertNull(argument(ARG_THREAD_TITLE))
        assertNull("the link is consumed, so it cannot fire again", deepLinks.pending.value)
        assertEquals(listOf("ws-1" to "msg-1"), api.threadRequests)
    }

    @Test
    fun `a link with no message id opens the inbox without asking the server`() {
        render()

        offer(InboxDeepLink("ws-1"))

        assertEquals(Routes.INBOX, route())
        assertEquals("ws-1", argument(ARG_WORKSPACE_ID))
        assertEquals(emptyList<Pair<String, String>>(), api.threadRequests)
    }

    @Test
    fun `a message the server cannot resolve still lands on its workspace's inbox`() {
        api.threadResult = ApiResult.NotFound("Message not found")
        render()

        offer(InboxDeepLink("ws-1", "msg-gone"))

        assertEquals(Routes.INBOX, route())
        assertNull(deepLinks.pending.value)
    }

    @Test
    fun `a link for another workspace is dropped rather than opened in this one`() {
        render()

        offer(InboxDeepLink("ws-other", "msg-1"))

        assertEquals(HOME, route())
        assertNull(deepLinks.pending.value)
        assertEquals(emptyList<Pair<String, String>>(), api.threadRequests)
    }

    @Test
    fun `a link that arrives before the workspace resolves waits for it`() {
        // The cold-start case the link exists for: the notification is tapped before the first
        // reads have named the active workspace.
        workspaceId = null
        render()
        offer(InboxDeepLink("ws-1"))

        assertEquals(HOME, route())
        assertEquals(InboxDeepLink("ws-1"), deepLinks.pending.value)

        composeRule.runOnIdle { workspaceId = "ws-1" }
        composeRule.waitForIdle()

        assertEquals(Routes.INBOX, route())
        assertNull(deepLinks.pending.value)
    }

    private companion object {
        const val HOME = "home"
    }
}
