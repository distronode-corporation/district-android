package com.distronode.districtai.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactDetailResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.contacts.CONTACT_DETAIL_ROOT_DESCRIPTION
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The dossier poll follows the contact screen's own lifecycle, through the real graph.
 *
 * ⛔ THE POLL USED TO RUN FOR AS LONG AS THE ViewModel LIVED. That is right for a pop (the entry is
 * destroyed and the scope with it) and wrong for everything else: with another destination pushed
 * on top, or the app in the background, the entry is only STOPPED, and the loop kept re-reading a
 * contact nobody could see every 2.5 seconds, on a route that fans out to a regional database.
 * Pushing a destination stops the entry exactly as backgrounding the app does, so it stands in for
 * both here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class NavHostDossierPollTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    private fun answerWith(dgiStatus: String) {
        harness.api.contactResult = ApiResult.Success(
            ContactDetailResponse(
                success = true,
                contact = Contact(
                    id = "ct-4",
                    workspaceId = "ws-1",
                    name = "Ada",
                    dgiStatus = dgiStatus,
                    createdAt = "2026-08-15T14:30:00.000Z",
                ),
            ),
        )
    }

    /** Cross [cycles] poll intervals on the main looper's clock, then let the UI settle. */
    private fun pollFor(cycles: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(POLL_INTERVAL_MS * cycles + 1),
        )
        composeRule.waitForIdle()
    }

    @Test
    fun `the poll stops reading while another screen covers the contact, and resumes on return`() {
        answerWith("crawling")
        harness.render()
        harness.navigate(Routes.contactDetail("ws-1", "ct-4", WorkspaceRole.AGENCY))
        harness.awaitDescription(CONTACT_DETAIL_ROOT_DESCRIPTION)

        val beforeVisible = harness.api.contactRequestCount
        pollFor(2)
        assertTrue("the poll reads while the contact is on screen", harness.api.contactRequestCount > beforeVisible)

        harness.navigate(Routes.callDetail("ws-1", "c1"))
        val covered = harness.api.contactRequestCount
        pollFor(4)
        assertEquals("no read while the contact is covered", covered, harness.api.contactRequestCount)

        composeRule.runOnIdle { harness.navController.popBackStack() }
        composeRule.waitForIdle()
        harness.awaitDescription(CONTACT_DETAIL_ROOT_DESCRIPTION)
        val back = harness.api.contactRequestCount
        pollFor(2)
        assertTrue("the poll resumes on return", harness.api.contactRequestCount > back)

        // End with the poll stopped, so no delayed read outlives the test.
        answerWith("complete")
        pollFor(1)
    }

    private companion object {
        /** Mirrors ContactsRepository.DOSSIER_POLL_INTERVAL_MS (private there), the web console's own. */
        const val POLL_INTERVAL_MS = 2_500L
    }
}
