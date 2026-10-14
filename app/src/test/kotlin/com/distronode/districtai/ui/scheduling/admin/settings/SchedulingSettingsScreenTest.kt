package com.distronode.districtai.ui.scheduling.admin.settings

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.inbox.AttachmentReader
import com.distronode.districtai.ui.inbox.PickedAttachment
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import androidx.compose.runtime.mutableIntStateOf
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_NOTICE_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The settings screen as a member sees it: the four tabs and every editor. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private val calls = mutableListOf<String>()
    private val writes = SchedulingSettingsWrites(scope, rig.adminRepository, "ws") { calls += "saved" }
    private var state by mutableStateOf(SchedulingSettingsUiState(canManage = true))
    private val me = schedulingMe()
    private val llm = SchedulingLlmSettings(enabled = true, extraInstructions = "Closed Sundays.")
    private val offline = FailureText(UiText.Resource(R.string.scheduling_admin_failure_offline), retryable = true)
    private val notSet get() = str(R.string.scheduling_admin_common_not_set)

    private fun show() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingSettingsScreen(
                    state = state,
                    writes = writes,
                    onBack = { calls += "back" },
                    onRefresh = { calls += "refresh" },
                    onSelectTab = { calls += "tab:${it.id}" },
                    onPickImage = { calls += "pick:${it.wire}" },
                )
            }
        }
    }

    private fun settle() {
        scope.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    @Test
    fun `a tab is loading until read, says when it failed, and switches on a tap`() {
        show()
        composeRule.onNodeWithTag(SCHEDULING_SETTINGS_ROOT_TAG).assertExists()
        composeRule.onNodeWithTag(SETTINGS_LOADING_TAG).assertExists()
        state = state.copy(branding = SectionState.Failed(offline))
        composeRule.onNodeWithTag(SETTINGS_FAILURE_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_SETTINGS_TAB_TAG-${SchedulingSettingsTab.PROFILE}").performClick()
        assertEquals(listOf("refresh", "tab:profile"), calls)
        state = state.copy(tab = SchedulingSettingsTab.NOTIFICATIONS, profile = SectionState.Loading)
        composeRule.onNodeWithTag(SETTINGS_LOADING_TAG).assertExists()
    }

    @Test
    fun `English and French are named in the app's language, not by code or by the server's endonym`() {
        state = state.copy(branding = SectionState.Ready(schedulingBranding().copy(fallbackLocale = "fr")))
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_language_fr)).assertIsDisplayed()
        state = state.copy(branding = SectionState.Ready(schedulingBranding().copy(fallbackLocale = "en")))
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_language_en)).assertIsDisplayed()
    }

    @Test
    fun `the booking page reads as published, and only a manager may edit it`() {
        // ⚠️ A language the app does not name itself and the server's list does not name either: its code.
        val unnamed = schedulingBranding().copy(businessName = "", fallbackLocale = "de")
        state = state.copy(branding = SectionState.Ready(unnamed))
        show()
        composeRule.onNodeWithText("https://www.contract.test/terms").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_set)).assertIsDisplayed()
        composeRule.onNodeWithText("de").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_DIALOG_TAG).assertIsDisplayed()
        writes.close()
        state = state.copy(
            canManage = false,
            branding = SectionState.Ready(schedulingBranding().copy(fallbackLocale = "en")),
        )
        composeRule.onNodeWithTag(SETTINGS_BRANDING_EDIT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        val bare = schedulingBranding().copy(fallbackLocale = "", supportedLocales = null)
        state = state.copy(branding = SectionState.Ready(bare))
        composeRule.onNodeWithText("Contract Agency").assertIsDisplayed()
        val linked = schedulingBranding().copy(privacyUrl = "https://p.example", termsUrl = "", fallbackLocale = "de")
        state = state.copy(branding = SectionState.Ready(linked))
        composeRule.onNodeWithText("https://p.example").assertIsDisplayed()
        composeRule.onNodeWithText("de").assertIsDisplayed()
        state = state.copy(branding = SectionState.Ready(linked.copy(supportedLocales = null)))
        composeRule.onNodeWithText("de").assertIsDisplayed()
    }

    @Test
    fun `the assistant reads on or off with its instructions, and only a manager may edit it`() {
        state = state.copy(tab = SchedulingSettingsTab.ASSISTANT, assistant = SectionState.Ready(llm))
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_on)).assertIsDisplayed()
        composeRule.onNodeWithText("Closed Sundays.").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_DIALOG_TAG).assertIsDisplayed()
        writes.close()
        state = state.copy(canManage = false, assistant = SectionState.Ready(SchedulingLlmSettings(false, "")))
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_off)).assertIsDisplayed()
        composeRule.onNodeWithText("Closed Sundays.").assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_EDIT_TAG).assertDoesNotExist()
    }

    @Test
    fun `the profile and the switches read for everyone and stay editable without manage`() {
        state = state.copy(tab = SchedulingSettingsTab.PROFILE, canManage = false, profile = SectionState.Ready(me))
        show()
        composeRule.onNodeWithText("Ana Lima").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_time_format_12h)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_weekday_mon)).assertIsDisplayed()
        composeRule.onNodeWithText("12/31/2026").assertIsDisplayed()
        composeRule.onNodeWithText(notSet).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_EDIT_TAG).assertIsDisplayed()
        state = state.copy(
            profile = SectionState.Ready(
                me.copy(timeFormat = "13h", dateFormat = "ydm", weekStart = 9, avatarUrl = "https://cdn/a.png"),
            ),
        )
        composeRule.onNodeWithText("13h").assertIsDisplayed()
        composeRule.onNodeWithText("ydm").assertIsDisplayed()
        composeRule.onNodeWithText("9").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_set)).assertIsDisplayed()
        state = state.copy(profile = SectionState.Ready(me.copy(weekStart = -1)))
        composeRule.onNodeWithText("-1").assertIsDisplayed()

        state = state.copy(
            tab = SchedulingSettingsTab.NOTIFICATIONS,
            profile = SectionState.Ready(me.copy(notifyReminder = false)),
        )
        val attendee = str(R.string.scheduling_admin_settings_notify_attendee_heading).uppercase()
        composeRule.onNodeWithText(attendee).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_off)).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_NOTIFICATIONS_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_NOTIFICATIONS_DIALOG_TAG).assertIsDisplayed()
    }

    @Test
    fun `the profile editor takes every field, refuses before sending, and saves`() {
        rig.admin.value(SchedulingAdminOp.ME_PATCH, me.copy(name = "Ana L"))
        state = state.copy(tab = SchedulingSettingsTab.PROFILE, profile = SectionState.Ready(me))
        show()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_NAME_TAG).performTextClearance()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_SAVE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_name_required)).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_NAME_TAG).performTextInput("Ana L")
        composeRule.onNodeWithTag(SETTINGS_PROFILE_TIMEZONE_TAG).performTextClearance()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_TIMEZONE_TAG).performTextInput("Europe/Paris")
        composeRule.onNodeWithTag("$SETTINGS_PROFILE_TIME_FORMAT_TAG-24h").performClick()
        composeRule.onNodeWithTag("$SETTINGS_PROFILE_WEEK_START_TAG-0").performClick()
        composeRule.onNodeWithTag("$SETTINGS_PROFILE_DATE_FORMAT_TAG-ymd").performClick()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_SAVE_TAG).performClick()
        settle()
        val sent = rig.admin.callsTo(SchedulingAdminOp.ME_PATCH).single().params
        assertEquals(
            "{\"name\":\"Ana L\",\"timezone\":\"Europe/Paris\",\"time_format\":\"24h\"," +
                "\"week_start\":0,\"date_format\":\"ymd\"}",
            sent.toString(),
        )
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_profile_done)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `the picture is chosen through the picker and removed only after confirming`() {
        rig.admin.value(SchedulingAdminOp.ME_AVATAR_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.ME_GET, me)
        writes.profile.open(me.copy(avatarUrl = "https://cdn/a.png"))
        show()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_CHOOSE_TAG-avatar").performClick()
        assertEquals(listOf("pick:avatar"), calls)
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_REMOVE_TAG-avatar").performClick()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(SCHEDULING_WRITE_CANCEL_TAG)).onLast().performClick()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_REMOVE_TAG-avatar").performClick()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_PROFILE_SAVE_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_avatar_removed)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_REMOVE_TAG-avatar").assertDoesNotExist()
    }

    @Test
    fun `the switches are toggled in their groups and saved together`() {
        rig.admin.value(SchedulingAdminOp.ME_PATCH, me)
        writes.notifications.open(me)
        show()
        val host = str(R.string.scheduling_admin_settings_notify_host_heading).uppercase()
        composeRule.onNodeWithText(host).assertIsDisplayed()
        composeRule.onNodeWithTag("$SETTINGS_NOTIFY_TAG-${SchedulingNotifySwitch.HOST_CANCEL.name}").performClick()
        composeRule.onNodeWithTag(SETTINGS_NOTIFICATIONS_SAVE_TAG).performClick()
        settle()
        val sent = rig.admin.callsTo(SchedulingAdminOp.ME_PATCH).single().params
        assertEquals("false", sent["notify_host_cancel"].toString())
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_notifications_done)).assertIsDisplayed()
    }

    @Test
    fun `the booking page editor moves every field, refuses an out-of-range height, and saves`() {
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_PATCH, schedulingBranding())
        writes.branding.open(schedulingBranding(logoHeight = 0))
        show()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_SAVE_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_logo_height_range)).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_LOGO_HEIGHT_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        composeRule.onNodeWithTag(SETTINGS_BRANDING_LOGO_OPACITY_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        composeRule.onNodeWithTag(SETTINGS_BRANDING_BANNER_OPACITY_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        composeRule.onNodeWithTag(SETTINGS_BRANDING_NAME_TAG).performTextClearance()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_NAME_TAG).performTextInput("Acme")
        composeRule.onNodeWithTag(SETTINGS_BRANDING_PRIVACY_TAG).performTextInput("https://a.example/p")
        composeRule.onNodeWithTag(SETTINGS_BRANDING_TERMS_TAG).performTextClearance()
        composeRule.onNodeWithTag("$SETTINGS_BRANDING_LOCALE_TAG-fr").performClick()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_CHOOSE_TAG-banner").performClick()
        composeRule.onNodeWithTag(SETTINGS_BRANDING_SAVE_TAG).performClick()
        settle()
        val sent = rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_PATCH).single().params
        assertEquals(
            "{\"business_name\":\"Acme\",\"logo_height\":40,\"logo_opacity\":50,\"banner_opacity\":60," +
                "\"privacy_url\":\"https://a.example/p\",\"terms_url\":\"\",\"fallback_locale\":\"fr\"}",
            sent.toString(),
        )
        assertEquals(listOf("pick:banner", "saved"), calls)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_branding_done)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_REMOVE_TAG-logo").performClick()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_CONFIRM_TAG).assertIsDisplayed()
        writes.images.cancelRemove()
        writes.close()
        writes.branding.open(schedulingBranding().copy(bannerUrl = "https://cdn/b.png"))
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_REMOVE_TAG-banner").performClick()
        composeRule.onNodeWithTag(SETTINGS_IMAGE_REMOVE_DIALOG_TAG).assertIsDisplayed()
    }

    @Test
    fun `a booking page with no language list shows only the hint`() {
        writes.branding.open(schedulingBranding().copy(supportedLocales = null))
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_fallback_locale_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SETTINGS_BRANDING_LOCALE_TAG-en").assertDoesNotExist()
    }

    @Test
    fun `the assistant editor saves only a change and refuses instructions over 4000 characters`() {
        rig.admin.value(SchedulingAdminOp.SETTINGS_LLM_PATCH, llm.copy(enabled = false))
        writes.assistant.open(llm)
        show()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_INSTRUCTIONS_TAG).performTextInput("x".repeat(4000))
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_SAVE_TAG).performClick()
        val tooLong = str(R.string.scheduling_admin_settings_assistant_instructions_too_long)
        composeRule.onNodeWithText(tooLong).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_INSTRUCTIONS_TAG).performTextClearance()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_TOGGLE_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_ASSISTANT_SAVE_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_settings_assistant_done)).assertIsDisplayed()
    }

    @Test
    fun `the route opens the system picker and uploads what the reader read, or nothing when closed`() {
        rig.admin.value(SchedulingAdminOp.ME_GET, me)
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(avatarUrl = "https://cdn/a.png"))
        val results = ArrayDeque<Uri?>(listOf(null, Uri.parse("content://picked/1")))
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, results.removeFirst())
            }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val read = mutableListOf<String>()
        val deps = SchedulingAdminDeps(
            admin = rig.adminRepository,
            scheduling = rig.scheduling,
            workspaceId = "ws-route",
            sessionEpoch = 0,
            onBack = {},
            navigate = {},
            openUrl = { true },
            members = MembersRepository(rig.district),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                DistrictTheme {
                    SchedulingSettingsHost(
                        deps,
                        AttachmentReader { uri ->
                            read += uri
                            PickedAttachment("me.png", "image/png", ByteArray(4))
                        },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("$SCHEDULING_SETTINGS_TAB_TAG-${SchedulingSettingsTab.PROFILE}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag(SETTINGS_PROFILE_EDIT_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(SETTINGS_PROFILE_EDIT_TAG).performClick()
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_CHOOSE_TAG-avatar").performClick()
        composeRule.waitForIdle()
        assertTrue(read.isEmpty())
        composeRule.onNodeWithTag("$SETTINGS_IMAGE_CHOOSE_TAG-avatar").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { rig.admin.uploads.isNotEmpty() }
        assertEquals(listOf("content://picked/1"), read)
        assertEquals(SchedulingAdminUploadTarget.AVATAR, rig.admin.uploads.single().target)
    }

    @Test
    fun `each editor disables its fields while saving, offers a retry when it failed, and closes on Done`() {
        val offlineAnswer = FakeSchedulingAnswer.Failed(ApiResult.NetworkFailure(IOException("x")))
        val meAnswer = FakeSchedulingAnswer.Value(me)
        // The profile and the switches share `me.patch`: each fails once, then saves.
        rig.admin.answer(SchedulingAdminOp.ME_PATCH, offlineAnswer, meAnswer, offlineAnswer, meAnswer)
        rig.admin.answer(
            SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
            offlineAnswer,
            FakeSchedulingAnswer.Value(schedulingBranding()),
        )
        rig.admin.answer(SchedulingAdminOp.SETTINGS_LLM_PATCH, offlineAnswer, FakeSchedulingAnswer.Value(llm))
        show()
        val editors = listOf<Triple<() -> Unit, String, String>>(
            Triple({ writes.profile.open(me) }, SETTINGS_PROFILE_SAVE_TAG, SETTINGS_PROFILE_NAME_TAG),
            Triple(
                { writes.notifications.open(me) },
                SETTINGS_NOTIFICATIONS_SAVE_TAG,
                "$SETTINGS_NOTIFY_TAG-${SchedulingNotifySwitch.REMINDER.name}",
            ),
            Triple(
                { writes.branding.open(schedulingBranding()) },
                SETTINGS_BRANDING_SAVE_TAG,
                SETTINGS_BRANDING_NAME_TAG,
            ),
            Triple(
                { writes.assistant.open(llm.copy(enabled = false)) },
                SETTINGS_ASSISTANT_SAVE_TAG,
                SETTINGS_ASSISTANT_TOGGLE_TAG,
            ),
        )
        editors.forEach { (open, save, field) ->
            open()
            composeRule.waitForIdle()
            if (field == SETTINGS_ASSISTANT_TOGGLE_TAG) composeRule.onNodeWithTag(field).performClick()
            val gate = CompletableDeferred<Unit>()
            rig.admin.gate = gate
            composeRule.onNodeWithTag(save).performClick()
            settle()
            composeRule.onNodeWithTag(field).assertIsNotEnabled()
            rig.admin.gate = null
            gate.complete(Unit)
            settle()
            composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
            settle()
            composeRule.onNodeWithTag(SCHEDULING_WRITE_NOTICE_TAG).assertExists()
            composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
            composeRule.waitForIdle()
            assertEquals(null, writes.dialog.value)
        }
    }

    @Test
    fun `the route re-reads on Refresh and when the session changes`() {
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_GET, schedulingBranding())
        var epoch by mutableIntStateOf(0)
        composeRule.setContent {
            val deps = SchedulingAdminDeps(
                admin = rig.adminRepository,
                scheduling = rig.scheduling,
                workspaceId = "ws-epoch",
                sessionEpoch = epoch,
                onBack = {},
                navigate = {},
                openUrl = { true },
                members = MembersRepository(rig.district),
            )
            DistrictTheme { SchedulingSettingsRoute(deps) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size == 1
        }
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size == 2
        }
        epoch = 1
        composeRule.waitForIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size)
    }

    @Test
    fun `the route builds the content resolver reader and draws`() {
        val deps = SchedulingAdminDeps(
            admin = rig.adminRepository,
            scheduling = rig.scheduling,
            workspaceId = "ws-plain",
            sessionEpoch = 0,
            onBack = {},
            navigate = {},
            openUrl = { true },
            members = MembersRepository(rig.district),
        )
        composeRule.setContent { DistrictTheme { SchedulingSettingsRoute(deps) } }
        composeRule.onNodeWithTag(SCHEDULING_SETTINGS_ROOT_TAG).assertExists()
    }
}
