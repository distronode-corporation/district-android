package com.distronode.districtai.ui.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The two-step screen: what each step draws, what each control asks of the model, and the window
 * kept secure while a secret is on screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w411dp-h2400dp")
class TwoStepScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingActions : TwoStepActions {
        val calls = mutableListOf<String>()
        override fun onField(field: TwoStepField, value: String) {
            calls += "field:$field=$value"
        }
        override fun load() {
            calls += "load"
        }
        override fun begin(intent: TwoStepIntent) {
            calls += "begin:$intent"
        }
        override fun usePassword() {
            calls += "usePassword"
        }
        override fun back() {
            calls += "back"
        }
        override fun reauthenticate(provider: ReauthProvider) {
            calls += "sso:$provider"
        }
        override fun submitPassword() {
            calls += "submit"
        }
        override fun confirm() {
            calls += "confirm"
        }
        override fun finishRecoveryCodes() {
            calls += "done"
        }
    }

    private val actions = RecordingActions()

    /** The clipboard wipes [SensitiveClipboard] asked for, run by hand rather than left on the looper. */
    private val scheduled = mutableListOf<Pair<Runnable, Long>>()
    private val realSchedule = SensitiveClipboard.schedule

    @Before
    fun recordWipes() {
        SensitiveClipboard.schedule = { task, delay -> scheduled += task to delay }
    }

    @After
    fun restore() {
        SensitiveClipboard.schedule = realSchedule
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).checkActivities(false)
    }
    private var backs = 0
    private var activity: Activity? = null
    private var state by mutableStateOf(TwoStepUiState())

    private fun render(initial: TwoStepUiState) {
        state = initial
        composeRule.setContent {
            activity = LocalActivity.current
            DistrictTheme {
                TwoStepScreen(state = state, actions = actions, onBack = { backs += 1 })
            }
        }
    }

    private val on = MfaStatusResponse(true, "2026-10-01T12:00:00.000Z", 7, true)
    private val off = MfaStatusResponse(false, null, 0, true)
    private val enrollment = TwoStepEnrollment("AAAABBBBCCCCDDDD", "otpauth://totp/x?secret=AAAABBBBCCCCDDDD", null)

    private fun secure(): Boolean =
        activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun pngBase64(): String {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    @Test
    fun `loading draws a placeholder, and a failed first read offers retry`() {
        render(TwoStepUiState())
        composeRule.onNodeWithTag(TWO_STEP_ROOT_TAG).assertIsDisplayed()

        state = TwoStepUiState(step = TwoStepStep.LoadFailed, failure = UiText.Resource(R.string.two_step_error_load))
        composeRule.onNodeWithText(str(R.string.two_step_error_load)).assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_RETRY_TAG).performClick()

        assertEquals(listOf("load"), actions.calls)
    }

    @Test
    fun `off offers turning it on, with the notice of the last change`() {
        render(
            TwoStepUiState(
                step = TwoStepStep.Overview,
                status = off,
                notice = UiText.Resource(R.string.two_step_turned_off),
            ),
        )

        composeRule.onNodeWithText(str(R.string.two_step_status_off)).assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.two_step_off_explain)).assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_TURN_ON_TAG).performClick()

        assertEquals(listOf("begin:TURN_ON"), actions.calls)
        assertFalse("nothing secret on the overview", secure())
    }

    @Test
    fun `on says since when and how many codes are left, and offers turning it off`() {
        render(TwoStepUiState(step = TwoStepStep.Overview, status = on))

        composeRule.onNodeWithText(str(R.string.two_step_status_on)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.two_step_on_since, "October 1, 2026")).assertIsDisplayed()
        composeRule.onNodeWithText("7 recovery codes left.").assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_TURN_OFF_TAG).performClick()

        assertEquals(listOf("begin:TURN_OFF"), actions.calls)
    }

    @Test
    fun `no codes left, a passwordless account, and a date the service wrote oddly`() {
        render(
            TwoStepUiState(
                step = TwoStepStep.Overview,
                status = MfaStatusResponse(true, "not-a-date", 0, canDisable = false),
            ),
        )

        composeRule.onNodeWithText(str(R.string.two_step_codes_none)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.two_step_no_password_note)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.two_step_on_since, "not-a-date")).assertIsDisplayed()
    }

    @Test
    fun `the ways to confirm it is you offer each provider, the password and back`() {
        render(TwoStepUiState(step = TwoStepStep.Reauth(TwoStepIntent.TURN_OFF), status = on))

        composeRule.onNodeWithText(str(R.string.two_step_reauth_intro_off)).assertIsDisplayed()
        ReauthProvider.entries.forEach { composeRule.onNodeWithTag(reauthProviderTag(it)).performClick() }
        composeRule.onNodeWithTag(REAUTH_USE_PASSWORD_TAG).performClick()
        composeRule.onNodeWithTag(REAUTH_BACK_TAG).performClick()

        assertEquals(listOf("sso:GOOGLE", "sso:MICROSOFT", "sso:APPLE", "usePassword", "back"), actions.calls)
    }

    @Test
    fun `the password step types into the model and asks for the code once needed`() {
        render(
            TwoStepUiState(
                step = TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = true),
                status = off,
                password = "pw",
                code = "1",
                failure = UiText.Resource(R.string.reauth_error_wrong_code),
            ),
        )

        composeRule.onNodeWithTag(TWO_STEP_MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.reauth_code_prompt)).assertIsDisplayed()
        // ⚠️ SET, NOT TYPED: typing focuses the password field, and the focused secure field left
        // behind keeps the next test classes' lists from drawing. Setting the text needs no focus.
        composeRule.onNodeWithTag(REAUTH_PASSWORD_TAG).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("xpw"))
        }
        composeRule.onNodeWithTag(REAUTH_CODE_TAG).performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("21"))
        }
        composeRule.onNodeWithTag(REAUTH_PASSWORD_CONTINUE_TAG).assertIsEnabled().performClick()

        assertEquals("field:PASSWORD=xpw", actions.calls.first())
        assertTrue("field:CODE=21" in actions.calls)
        assertEquals("submit", actions.calls.last())
    }

    @Test
    fun `system back on a step of a change goes one step back`() {
        render(TwoStepUiState(step = TwoStepStep.Reauth(TwoStepIntent.TURN_ON), status = off))

        pressBack()

        assertEquals(listOf("back"), actions.calls)
        assertEquals(0, backs)
    }

    @Test
    fun `the top bar leaves the screen from the overview`() {
        render(TwoStepUiState(step = TwoStepStep.Overview, status = off))

        composeRule.onNodeWithContentDescription(str(R.string.top_bar_back)).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `the scan step shows the QR code and the key, covered from capture`() {
        render(
            TwoStepUiState(
                step = TwoStepStep.Scan,
                status = off,
                enrollment = TwoStepEnrollment(enrollment.secret, enrollment.otpauthUri, pngBase64()),
                totp = "123456",
            ),
        )

        assertTrue("FLAG_SECURE while the secret is on screen", secure())
        composeRule.onNodeWithTag(TWO_STEP_QR_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("AAAA BBBB CCCC DDDD").assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_TOTP_TAG).performTextInput("9")
        composeRule.onNodeWithTag(TWO_STEP_CONFIRM_TAG).performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithTag(REAUTH_BACK_TAG).performScrollTo().performClick()

        assertEquals(listOf("field:TOTP=9123456", "confirm", "back"), actions.calls)

        state = state.copy(step = TwoStepStep.Overview, enrollment = null)
        composeRule.waitForIdle()
        assertFalse("cleared again once the secret is gone", secure())
    }

    @Test
    fun `copy puts the key on the clipboard, marked sensitive`() {
        render(TwoStepUiState(step = TwoStepStep.Scan, status = off, enrollment = enrollment))

        composeRule.onNodeWithTag(TWO_STEP_COPY_KEY_TAG).performScrollTo().performClick()

        val clipboard = clipboard()
        val clip = clipboard.primaryClip!!
        assertEquals(enrollment.secret, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        composeRule.onNodeWithText(str(R.string.two_step_copied)).assertIsDisplayed()
    }

    @Test
    fun `an unreadable QR code says to type the key, and no authenticator app says so`() {
        // ⚠️ So an intent nothing resolves throws, as it does on a device.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).checkActivities(true)
        render(
            TwoStepUiState(
                step = TwoStepStep.Scan,
                status = off,
                enrollment = TwoStepEnrollment(enrollment.secret, enrollment.otpauthUri, "not base64 !"),
                failure = UiText.Resource(R.string.two_step_error_try_again),
            ),
        )

        composeRule.onNodeWithText(str(R.string.two_step_qr_unreadable)).assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(TWO_STEP_CONFIRM_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(TWO_STEP_OPEN_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText(str(R.string.two_step_open_failed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an authenticator app that takes the link is handed it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.example.authenticator"
                name = "Main"
            }
        }
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(enrollment.otpauthUri)
        )
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, app)

        assertTrue(openAuthenticator(context, enrollment.otpauthUri))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(enrollment.otpauthUri, started.dataString)
    }

    @Test
    fun `the codes show once, with copy all and done, and there is no way out but done`() {
        render(
            TwoStepUiState(
                step = TwoStepStep.RecoveryCodes,
                status = on,
                recoveryCodes = listOf("aaaa-bbbb", "cccc-dddd")
            )
        )

        assertTrue(secure())
        composeRule.onNodeWithText(str(R.string.two_step_codes_warning)).assertIsDisplayed()
        composeRule.onNodeWithText("aaaa-bbbb\ncccc-dddd").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(str(R.string.top_bar_back)).assertDoesNotExistCompat()
        pressBack()
        assertTrue("back does nothing past the codes", actions.calls.isEmpty())
        assertEquals(0, backs)

        composeRule.onNodeWithTag(TWO_STEP_COPY_CODES_TAG).performClick()
        assertEquals("aaaa-bbbb\ncccc-dddd", clipboard().primaryClip!!.getItemAt(0).text.toString())
        composeRule.onNodeWithTag(TWO_STEP_CODES_DONE_TAG).performClick()

        assertEquals(listOf("done"), actions.calls)
    }

    @Test
    fun `a busy screen shows progress and keeps the controls still`() {
        render(TwoStepUiState(step = TwoStepStep.Overview, status = off, busy = true))

        composeRule.onNodeWithTag(TWO_STEP_TURN_ON_TAG).assertIsNotEnabled()
        pressBack()
        assertTrue(actions.calls.isEmpty())
        assertEquals(0, backs)
    }

    @Test
    fun `the clipboard is wiped after two minutes only if it still holds the secret`() {
        val clipboard = clipboard()

        SensitiveClipboard.copy(ApplicationProvider.getApplicationContext(), "secret-1")
        assertEquals(SensitiveClipboard.EXPIRY_MILLIS, scheduled.single().second)
        scheduled.removeAt(0).first.run()
        assertFalse("wiped", clipboard.hasPrimaryClip() && clipboard.primaryClip!!.getItemAt(0).text == "secret-1")

        SensitiveClipboard.copy(ApplicationProvider.getApplicationContext(), "secret-2")
        clipboard.setPrimaryClip(ClipData.newPlainText("", "something else"))
        scheduled.removeAt(0).first.run()
        assertEquals("something else", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun `the real wipe is posted to the main looper`() {
        var ran = false

        realSchedule(Runnable { ran = true }, 0L)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(ran)
    }

    @Test
    fun `every step keeps its controls still while a call is in flight`() {
        render(TwoStepUiState(step = TwoStepStep.LoadFailed, busy = true))
        composeRule.onNodeWithTag(TWO_STEP_RETRY_TAG).assertIsNotEnabled()

        state = TwoStepUiState(step = TwoStepStep.Overview, status = on, busy = true)
        composeRule.onNodeWithTag(TWO_STEP_TURN_OFF_TAG).assertIsNotEnabled()

        state = TwoStepUiState(step = TwoStepStep.Reauth(TwoStepIntent.TURN_ON), status = off, busy = true)
        composeRule.onNodeWithTag(REAUTH_USE_PASSWORD_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(reauthProviderTag(ReauthProvider.APPLE)).assertIsNotEnabled()

        state = TwoStepUiState(step = TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = true), busy = true)
        composeRule.onNodeWithTag(REAUTH_BACK_TAG).assertIsNotEnabled()

        state = TwoStepUiState(step = TwoStepStep.Scan, enrollment = enrollment, busy = true)
        composeRule.onNodeWithTag(TWO_STEP_TOTP_TAG).assertIsNotEnabled()

        state = TwoStepUiState(step = TwoStepStep.RecoveryCodes, recoveryCodes = listOf("a"), busy = true)
        composeRule.onNodeWithTag(TWO_STEP_CODES_DONE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a step missing what it draws draws nothing rather than failing`() {
        render(TwoStepUiState(step = TwoStepStep.Overview, status = null))
        composeRule.onNodeWithTag(TWO_STEP_STATUS_TAG).assertDoesNotExist()

        state = TwoStepUiState(step = TwoStepStep.Scan, enrollment = null)
        composeRule.onNodeWithTag(TWO_STEP_SECRET_TAG).assertDoesNotExist()

        state = TwoStepUiState(step = TwoStepStep.Overview, status = on.copy(enrolledAt = null))
        composeRule.onNodeWithTag(TWO_STEP_TURN_OFF_TAG).assertIsDisplayed()

        state = TwoStepUiState(step = TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = false))
        composeRule.onNodeWithTag(REAUTH_CODE_TAG).assertDoesNotExist()
    }

    @Test
    fun `open hands the link over and says nothing more when an app takes it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.example.authenticator"
                name = "Main"
            }
        }
        val uri = android.net.Uri.parse(enrollment.otpauthUri)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, app)
        render(TwoStepUiState(step = TwoStepStep.Scan, enrollment = enrollment))

        composeRule.onNodeWithTag(TWO_STEP_OPEN_TAG).performScrollTo().performClick()

        composeRule.onNodeWithText(str(R.string.two_step_open_failed)).assertDoesNotExist()
    }

    @Test
    fun `with no activity to secure, the secret steps still draw`() {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivity provides null) {
                DistrictTheme { SecureWindow(secure = true) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a device with no clipboard copies nothing, and an empty clipboard is left alone`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val noClipboard = object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) null else super.getSystemService(name)
        }

        SensitiveClipboard.copy(noClipboard, "secret")
        assertTrue(scheduled.isEmpty())

        val clipboard = clipboard()
        clipboard.clearPrimaryClip()
        SensitiveClipboard.clearIfStill(clipboard, "secret")
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `a PNG decodes and anything else does not`() {
        assertTrue(decodePng(pngBase64()) != null)
        assertNull(decodePng("not base64 !"))
    }

    /** System back, through the activity's dispatcher, as the platform delivers it. */
    private fun pressBack() {
        composeRule.runOnUiThread {
            (activity as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun clipboard(): ClipboardManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
