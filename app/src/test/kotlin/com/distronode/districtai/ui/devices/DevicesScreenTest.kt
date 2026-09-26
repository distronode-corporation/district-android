package com.distronode.districtai.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ WHAT THIS SCREEN GETS WRONG IS EXPENSIVE, WHICH IS WHY IT IS TESTED AT THE PIXEL AND NOT ONLY
 * AT THE STATE. Two failures live only in the rendering: marking the wrong row as "this device"
 * (device names are client-supplied free text, so two identical handsets produce two identical
 * rows, and the wrong marker means signing out the phone you are holding while the lost one stays
 * live), and firing a destructive action without a confirmation. Neither is visible from the
 * ViewModel's state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DevicesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val thisDevice = NativeDevice(
        deviceId = THIS_DEVICE_ID,
        deviceName = "Google Pixel 9",
        platform = "android",
        lastUsedAt = "2026-08-17T09:15:00.000Z",
        createdAt = "2026-08-01T09:15:00.000Z",
    )

    /** ⚠️ Both nullable fields absent, which is the shape a just-signed-in device really has. */
    private val otherDevice = NativeDevice(
        deviceId = OTHER_DEVICE_ID,
        platform = "ios",
        createdAt = "2026-07-20T11:00:00.000Z",
    )

    private fun render(
        state: DevicesUiState,
        onRetry: () -> Unit = {},
        onRevokeDevice: (String) -> Unit = {},
        onRevokeAll: () -> Unit = {},
        onDismissNotices: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                DevicesScreen(
                    state = state,
                    thisDeviceId = THIS_DEVICE_ID,
                    onBack = {},
                    onRetry = onRetry,
                    onRevokeDevice = onRevokeDevice,
                    onRevokeAll = onRevokeAll,
                    onDismissNotices = onDismissNotices,
                )
            }
        }
    }

    private fun ready(vararg devices: NativeDevice, busy: Boolean = false) = DevicesUiState(
        devices = DevicesListState.Ready(devices.toList()),
        busy = busy,
    )

    // ── The list ─────────────────────────────────────────────────────────────

    @Test
    fun `renders a row per device`() {
        render(ready(thisDevice, otherDevice))

        composeRule.onNodeWithContentDescription(DEVICES_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(deviceRowDescription(THIS_DEVICE_ID)).assertExists()
        composeRule.onNodeWithContentDescription(deviceRowDescription(OTHER_DEVICE_ID)).assertExists()
    }

    @Test
    fun `marks exactly one row as this device`() {
        // ⛔ THE SAFETY-CRITICAL ASSERTION ON THIS SCREEN. A second marker, or a marker on the
        // wrong row, invites the user to sign out the device in their hand while the lost one
        // stays signed in.
        render(ready(thisDevice, otherDevice))

        assertEquals(1, markedRowCount())
    }

    @Test
    fun `the marker follows the installation id, not the name`() {
        // ⛔ NAMES ARE UNTRUSTED AND NON-UNIQUE. Two handsets of the same model send the same
        // `Build.MANUFACTURER + Build.MODEL`, so a name-based marker would light up both rows.
        val twin = otherDevice.copy(deviceName = "Google Pixel 9")
        render(ready(thisDevice, twin))

        assertEquals("an identically-named twin must not be marked", 1, markedRowCount())
    }

    @Test
    fun `a device with no name gets a placeholder rather than its raw id`() {
        // ⚠️ The id is an opaque UUID that means nothing to a person, and showing it invites them
        // to treat it as an identifier they should recognise.
        render(ready(otherDevice))

        composeRule.onNodeWithText("Unnamed device").assertIsDisplayed()
        composeRule.onNodeWithText(OTHER_DEVICE_ID).assertDoesNotExist()
    }

    @Test
    fun `a device that has never refreshed says so rather than showing a blank`() {
        // ⚠️ EVERY SESSION IS IN THIS STATE FOR ITS FIRST TEN MINUTES, including the one the user
        // just created — `lastUsedAt` is stamped only on rotation. A blank line there would read
        // as missing data.
        render(ready(otherDevice))

        composeRule.onNodeWithText("Signed in recently").assertIsDisplayed()
    }

    @Test
    fun `an empty list is an explanatory empty state, not a failure`() {
        // ⛔ The server hides a chain that is mid-rotation, so a single-device account genuinely
        // sees zero rows while perfectly signed in.
        render(ready())

        composeRule.onNodeWithContentDescription(DEVICES_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DEVICES_LIST_FAILURE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the sign-out-everywhere control is offered even with no rows`() {
        // ⚠️ An empty list can mean a chain is mid-rotation rather than that nothing is signed in,
        // and this is the control someone reaches for when they believe a device is live that the
        // list is not showing.
        render(ready())

        composeRule.onNodeWithContentDescription(DEVICES_REVOKE_ALL_DESCRIPTION).assertExists()
    }

    @Test
    fun `a loading list shows placeholders rather than an empty state`() {
        render(DevicesUiState(devices = DevicesListState.Loading))

        composeRule.onNodeWithContentDescription(DEVICES_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DEVICES_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    // ── Confirmation ─────────────────────────────────────────────────────────

    @Test
    fun `revoking a device is confirmed, not immediate`() {
        var revoked: String? = null
        render(ready(thisDevice, otherDevice), onRevokeDevice = { revoked = it })

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performClick()

        assertEquals("the tap must only open a dialog", null, revoked)
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_DEVICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `confirming reports the device that was confirmed`() {
        var revoked: String? = null
        render(ready(thisDevice, otherDevice), onRevokeDevice = { revoked = it })

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performClick()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_DEVICE_DESCRIPTION).performClick()

        assertEquals(OTHER_DEVICE_ID, revoked)
    }

    @Test
    fun `cancelling a confirmation revokes nothing`() {
        var revoked: String? = null
        render(ready(thisDevice, otherDevice), onRevokeDevice = { revoked = it })

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(null, revoked)
    }

    @Test
    fun `confirming this device warns that it is the one in your hand`() {
        // ⚠️ ITS OWN WORDING, because the generic sentence would not warn that the user is about
        // to be returned to the sign-in screen mid-tap.
        render(ready(thisDevice, otherDevice))

        composeRule.onNodeWithContentDescription(revokeRowDescription(THIS_DEVICE_ID)).performClick()

        composeRule
            .onNodeWithText(
                "This is the device you are using. Signing it out will return you to the sign-in screen.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `revoke-all is confirmed and says it includes this device`() {
        // ⛔ The server's "all" genuinely includes the caller, so the confirmation has to say so.
        var revokedAll = 0
        render(ready(thisDevice, otherDevice), onRevokeAll = { revokedAll += 1 })

        composeRule.onNodeWithContentDescription(DEVICES_REVOKE_ALL_DESCRIPTION)
            .performScrollTo()
            .performClick()

        assertEquals(0, revokedAll)
        composeRule
            .onNodeWithText("Sign out of District AI on every device, including this one?")
            .assertExists()

        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_ALL_DESCRIPTION).performClick()
        assertEquals(1, revokedAll)
    }

    // ── Busy, notices and failures ───────────────────────────────────────────

    @Test
    fun `both controls are disabled while a write is in flight`() {
        // ⛔ ONE FLAG FOR EVERY WRITE. A second one started mid-flight could revoke a row the
        // refreshed list no longer shows, or race the local sign-out the first is about to fire.
        render(ready(thisDevice, otherDevice, busy = true))

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DEVICES_REVOKE_ALL_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `revoked-zero renders as a neutral notice beside the list, not as an error`() {
        // ⛔ THE NON-ORACLE CONTRACT AS THE USER SEES IT. Painting this red would report a fault
        // that did not happen.
        render(ready(thisDevice, otherDevice).copy(nothingRevoked = true))

        composeRule.onNodeWithContentDescription(DEVICES_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DEVICES_FAILURE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(deviceRowDescription(OTHER_DEVICE_ID)).assertExists()
    }

    @Test
    fun `a notice can be dismissed`() {
        var dismissals = 0
        render(
            ready(thisDevice).copy(nothingRevoked = true),
            onDismissNotices = { dismissals += 1 },
        )

        composeRule.onNodeWithText("Dismiss").performClick()

        assertEquals(1, dismissals)
    }

    @Test
    fun `a failed write is shown ALONGSIDE the rows, never instead of them`() {
        // ⚠️ Blanking the list would lose exactly what the user was about to act on.
        render(
            ready(thisDevice, otherDevice).copy(
                mutationFailure = FailureText(UiText.Literal("Could not sign out that device.")),
            ),
        )

        composeRule.onNodeWithContentDescription(DEVICES_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(deviceRowDescription(OTHER_DEVICE_ID)).assertExists()
    }

    @Test
    fun `a failed read offers a retry`() {
        var retries = 0
        render(
            DevicesUiState(
                devices = DevicesListState.Failed(FailureText(UiText.Literal("Server error"))),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(DEVICES_LIST_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a signed-out failure offers no retry, because retrying cannot help`() {
        // ⚠️ A button that produces the identical failure every time is worse than no button.
        render(
            DevicesUiState(
                devices = DevicesListState.Failed(
                    FailureText(
                        message = UiText.Resource(R.string.failure_signed_out),
                        signedOutCause = SignedOutCause.SESSION_INVALID,
                        retryable = false,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(DEVICES_LIST_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    // ── Rows the client cannot describe, and a screen that moves under a dialog ─

    @Test
    fun `a blank name and a blank platform get placeholders, not empty lines`() {
        render(ready(otherDevice.copy(deviceName = "  ", platform = "")))

        composeRule.onNodeWithText("Unnamed device").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown platform").assertIsDisplayed()
    }

    @Test
    fun `an open confirmation still names its device after the list is re-read under it`() {
        // ⚠️ A re-read lands while the dialog is up (a notice from the last write, the same rows in
        // a new list). The pending confirmation must still apply to the row it was opened for.
        var revoked: String? = null
        var state by mutableStateOf(ready(thisDevice, otherDevice))
        renderLive({ state }, onRevokeDevice = { revoked = it })

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performClick()
        state = ready(thisDevice, otherDevice).copy(nothingRevoked = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_DEVICE_DESCRIPTION).performClick()

        assertEquals(OTHER_DEVICE_ID, revoked)
    }

    @Test
    fun `an open sign-out-everywhere confirmation survives a re-read under it`() {
        var revokedAll = 0
        var state by mutableStateOf(ready(thisDevice, otherDevice))
        renderLive({ state }, onRevokeAll = { revokedAll += 1 })

        composeRule.onNodeWithContentDescription(DEVICES_REVOKE_ALL_DESCRIPTION).performScrollTo().performClick()
        state = ready(thisDevice, otherDevice).copy(nothingRevoked = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_ALL_DESCRIPTION).performClick()

        assertEquals(1, revokedAll)
    }

    @Test
    fun `a second failed write replaces the first failure notice`() {
        var state by mutableStateOf(
            ready(thisDevice).copy(mutationFailure = FailureText(message = UiText.Literal("First refusal"))),
        )
        renderLive({ state })
        composeRule.onNodeWithText("First refusal").assertIsDisplayed()

        state = ready(thisDevice).copy(mutationFailure = FailureText(message = UiText.Literal("Second refusal")))
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(DEVICES_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Second refusal").assertIsDisplayed()
        composeRule.onNodeWithText("First refusal").assertDoesNotExist()
    }

    @Test
    fun `the preview renders both of its devices`() {
        composeRule.setContent { DevicesScreenPreview() }

        composeRule.onNodeWithText("Google Pixel 9").assertIsDisplayed()
        assertEquals(1, markedRowCount())
    }

    @Test
    fun `an open confirmation reports to the handler the host holds when it is confirmed`() {
        // ⚠️ The host may hand the screen a new handler while the dialog is up (a recreated
        // ViewModel after a configuration change); the tap must reach the live one, not the first.
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var handler by mutableStateOf<(String) -> Unit>({ first += it })
        composeRule.setContent {
            DistrictTheme {
                DevicesScreen(
                    state = ready(thisDevice, otherDevice),
                    thisDeviceId = THIS_DEVICE_ID,
                    onBack = {},
                    onRetry = {},
                    onRevokeDevice = handler,
                    onRevokeAll = {},
                    onDismissNotices = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performClick()
        handler = { second += it }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_DEVICE_DESCRIPTION).performClick()

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf(OTHER_DEVICE_ID), second)
    }

    @Test
    fun `an open sign-out-everywhere confirmation reports to the handler the host holds`() {
        var firstCalls = 0
        var secondCalls = 0
        var handler by mutableStateOf<() -> Unit>({ firstCalls += 1 })
        composeRule.setContent {
            DistrictTheme {
                DevicesScreen(
                    state = ready(thisDevice),
                    thisDeviceId = THIS_DEVICE_ID,
                    onBack = {},
                    onRetry = {},
                    onRevokeDevice = {},
                    onRevokeAll = handler,
                    onDismissNotices = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(DEVICES_REVOKE_ALL_DESCRIPTION).performScrollTo().performClick()
        handler = { secondCalls += 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_ALL_DESCRIPTION).performClick()

        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `a theme change redraws the rows and notices with their handles and actions intact`() {
        var revoked: String? = null
        val theme = ThemeFlip(composeRule)
        theme.setContent {
            DevicesScreen(
                state = ready(thisDevice, otherDevice).copy(
                    nothingRevoked = true,
                    mutationFailure = FailureText(message = UiText.Literal("Refused")),
                ),
                thisDeviceId = THIS_DEVICE_ID,
                onBack = {},
                onRetry = {},
                onRevokeDevice = { revoked = it },
                onRevokeAll = {},
                onDismissNotices = {},
            )
        }

        theme.flip()

        composeRule.onNodeWithContentDescription(DEVICES_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DEVICES_FAILURE_DESCRIPTION).assertIsDisplayed()
        assertEquals(1, markedRowCount())
        composeRule.onNodeWithContentDescription(revokeRowDescription(OTHER_DEVICE_ID)).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(DEVICES_CONFIRM_DEVICE_DESCRIPTION).performClick()
        assertEquals(OTHER_DEVICE_ID, revoked)
    }

    /** Like [render], but the state is read on every composition so a test can move it. */
    private fun renderLive(
        state: () -> DevicesUiState,
        onRevokeDevice: (String) -> Unit = {},
        onRevokeAll: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                DevicesScreen(
                    state = state(),
                    thisDeviceId = THIS_DEVICE_ID,
                    onBack = {},
                    onRetry = {},
                    onRevokeDevice = onRevokeDevice,
                    onRevokeAll = onRevokeAll,
                    onDismissNotices = {},
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * ⚠️ Counting MATCHES rather than asserting on a single node: the whole point of the
     * this-device tests is that there must be exactly one, and `onNodeWith...` throws an
     * ambiguity error for two that reads as a test-infrastructure fault rather than as the
     * product bug it is.
     */
    private fun markedRowCount(): Int =
        composeRule.onAllNodesWithContentDescription(DEVICES_THIS_DEVICE_DESCRIPTION)
            .fetchSemanticsNodes()
            .size

    private companion object {
        const val THIS_DEVICE_ID = "device-this-installation"
        const val OTHER_DEVICE_ID = "device-other-phone"
    }
}
