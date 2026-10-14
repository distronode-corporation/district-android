package com.distronode.districtai.ui.scheduling.admin.settings

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingLocaleOption
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.ui.inbox.PickedAttachment
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branding fixtures shared by the settings tests, shaped like `district-scheduling-branding.json`. */
fun schedulingBranding(logoUrl: String = "https://book.example.com/logo.png", logoHeight: Int = 48) =
    SchedulingBranding(
        businessName = "Contract Agency",
        logoUrl = logoUrl,
        logoHeight = logoHeight,
        logoOpacity = 100,
        bannerUrl = "",
        bannerOpacity = 80,
        privacyUrl = "",
        termsUrl = "https://www.contract.test/terms",
        fallbackLocale = "en",
        supportedLocales = listOf(SchedulingLocaleOption("en", "English"), SchedulingLocaleOption("fr", "")),
    )

/** The profile, switches, booking page, assistant and image writes: what each sends and shows. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingSettingsWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private val saved = mutableListOf<SettingsSaved>()
    private val writes = SchedulingSettingsWrites(scope, rig.adminRepository, "ws") { saved += it }
    private val me = schedulingMe(timezone = "America/Toronto")
    private val offline = ApiResult.NetworkFailure(IOException("x"))
    private val png = PickedAttachment("logo.png", "image/png", ByteArray(8))

    private val profile get() = writes.dialog.value as SettingsDialog.Profile
    private val brandingDialog get() = writes.dialog.value as SettingsDialog.Branding
    private val assistant get() = writes.dialog.value as SettingsDialog.Assistant

    @Test
    fun `the profile refuses before sending, saves all five fields once and adopts the answer`() {
        val fresh = me.copy(name = "Ana L")
        rig.admin.value(SchedulingAdminOp.ME_PATCH, fresh)
        writes.profile.edit { it.copy(name = "x") }
        writes.profile.save()
        assertNull(writes.dialog.value)

        writes.profile.open(me)
        writes.profile.edit { it.copy(name = "") }
        writes.profile.save()
        assertEquals(R.string.scheduling_admin_settings_name_required, profile.rejected)
        writes.profile.edit { it.copy(name = " Ana L ") }
        assertNull(profile.rejected)
        writes.profile.save()
        writes.profile.save()
        writes.profile.edit { it.copy(name = "ignored") }
        assertEquals(WriteState.Working, profile.state)
        writes.close()
        assertTrue(writes.dialog.value is SettingsDialog.Profile)
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.ME_PATCH).single().params
        assertEquals(JsonPrimitive("Ana L"), sent["name"])
        assertEquals(JsonPrimitive("America/Toronto"), sent["timezone"])
        assertEquals(JsonPrimitive("12h"), sent["time_format"])
        assertEquals(JsonPrimitive(1), sent["week_start"])
        assertEquals(JsonPrimitive("mdy"), sent["date_format"])
        assertNull(sent["notify_confirmation"])
        assertEquals(fresh, profile.me)
        assertEquals(
            R.string.scheduling_admin_settings_profile_done,
            (profile.state as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(listOf<SettingsSaved>(SettingsSaved.Profile(fresh)), saved)
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `a failed profile save says why, and editing clears it`() {
        rig.admin.fail(SchedulingAdminOp.ME_PATCH, ApiResult.Forbidden("Forbidden"))
        writes.profile.open(me)
        writes.profile.save()
        scope.advanceUntilIdle()
        val failed = profile.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_forbidden, failed.failure.message.resourceIdOrNull)
        writes.profile.edit { it.copy(weekStart = 0) }
        assertEquals(WriteState.Idle, profile.state)
        writes.profile.edit { it.copy(weekStart = 2) }
        assertEquals(WriteState.Idle, profile.state)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `the switches send all seven and nothing else, once`() {
        rig.admin.answer(
            SchedulingAdminOp.ME_PATCH,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(me.copy(notifyHostCancel = false)),
        )
        writes.notifications.toggle(SchedulingNotifySwitch.HOST_CANCEL, on = false)
        writes.notifications.save()
        assertNull(writes.dialog.value)
        writes.notifications.open(me)
        writes.notifications.toggle(SchedulingNotifySwitch.HOST_CANCEL, on = false)
        writes.notifications.save()
        writes.notifications.save()
        writes.notifications.toggle(SchedulingNotifySwitch.REMINDER, on = false)
        scope.advanceUntilIdle()
        var dialog = writes.dialog.value as SettingsDialog.Notifications
        assertTrue(dialog.state is WriteState.Failed)
        writes.notifications.toggle(SchedulingNotifySwitch.REMINDER, on = true)
        dialog = writes.dialog.value as SettingsDialog.Notifications
        assertEquals(WriteState.Idle, dialog.state)
        writes.notifications.toggle(SchedulingNotifySwitch.REMINDER, on = true)
        writes.notifications.save()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.ME_PATCH)
        assertEquals(2, sent.size)
        assertEquals(JsonPrimitive(false), sent.last().params["notify_host_cancel"])
        assertEquals(JsonPrimitive(true), sent.last().params["notify_cancellation"])
        assertNull(sent.last().params["name"])
        dialog = writes.dialog.value as SettingsDialog.Notifications
        assertEquals(
            R.string.scheduling_admin_settings_notifications_done,
            (dialog.state as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(1, saved.size)
    }

    @Test
    fun `the booking page sends every field back, trimmed, after its checks`() {
        val stored = schedulingBranding()
        rig.admin.answer(
            SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(stored.copy(businessName = "Acme")),
        )
        writes.branding.edit { it.copy(businessName = "x") }
        writes.branding.save()
        assertNull(writes.dialog.value)

        writes.branding.open(schedulingBranding(logoHeight = 0))
        writes.branding.save()
        assertEquals(R.string.scheduling_admin_settings_logo_height_range, brandingDialog.rejected)
        writes.branding.edit {
            it.copy(logoHeight = 32, businessName = " Acme ", privacyUrl = " https://a.example/p ")
        }
        assertNull(brandingDialog.rejected)
        writes.branding.save()
        writes.branding.save()
        writes.branding.edit { it.copy(businessName = "ignored") }
        scope.advanceUntilIdle()
        assertTrue(brandingDialog.state is WriteState.Failed)
        writes.branding.edit { it.copy(termsUrl = "") }
        assertEquals(WriteState.Idle, brandingDialog.state)
        writes.branding.edit { it.copy(termsUrl = " ") }
        writes.branding.save()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_PATCH).last().params
        assertEquals(JsonPrimitive("Acme"), sent["business_name"])
        assertEquals(JsonPrimitive(32), sent["logo_height"])
        assertEquals(JsonPrimitive("https://a.example/p"), sent["privacy_url"])
        assertEquals(JsonPrimitive(""), sent["terms_url"])
        assertEquals(JsonPrimitive("en"), sent["fallback_locale"])
        assertNull(sent["logo_url"])
        assertEquals("Acme", brandingDialog.branding.businessName)
        assertEquals(
            R.string.scheduling_admin_settings_branding_done,
            (brandingDialog.state as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(1, saved.size)
    }

    @Test
    fun `the assistant sends nothing unchanged, refuses over 4000 and adopts the answer`() {
        val llm = SchedulingLlmSettings(enabled = false, extraInstructions = "")
        rig.admin.answer(
            SchedulingAdminOp.SETTINGS_LLM_PATCH,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingLlmSettings(enabled = true, extraInstructions = "Closed Sundays.")),
        )
        writes.assistant.editEnabled(true)
        writes.assistant.save()
        assertNull(writes.dialog.value)

        writes.assistant.open(llm)
        writes.assistant.save()
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.SETTINGS_LLM_PATCH).isEmpty())
        writes.assistant.editInstructions("x".repeat(ASSISTANT_INSTRUCTIONS_LIMIT + 1))
        writes.assistant.save()
        assertTrue(assistant.tooLong)
        writes.assistant.editInstructions(" Closed Sundays. ")
        assertFalse(assistant.tooLong)
        writes.assistant.editEnabled(true)
        writes.assistant.save()
        writes.assistant.save()
        writes.assistant.editEnabled(false)
        scope.advanceUntilIdle()
        assertTrue(assistant.state is WriteState.Failed)
        writes.assistant.editEnabled(true)
        assertEquals(WriteState.Idle, assistant.state)
        writes.assistant.save()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.SETTINGS_LLM_PATCH).last().params
        assertEquals(JsonPrimitive(true), sent["enabled"])
        assertEquals(JsonPrimitive(" Closed Sundays. "), sent["extra_instructions"])
        assertEquals("Closed Sundays.", assistant.instructions)
        assertFalse(assistant.isDirty)
        assertEquals(
            R.string.scheduling_admin_settings_assistant_done,
            (assistant.state as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(1, saved.size)
    }

    @Test
    fun `an avatar upload is checked first, shows the address it answered, or re-reads when it answered none`() {
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(avatarUrl = "https://cdn/a.png"))
        // Nothing chosen, or nothing open: ignored.
        writes.close()
        writes.images.upload(png)
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        writes.profile.open(me)
        writes.images.upload(png)
        assertTrue(rig.admin.uploads.isEmpty())

        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(null)
        assertEquals(
            R.string.scheduling_admin_settings_image_unreadable,
            (profile.avatar as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        assertFalse((profile.avatar as WriteState.Failed).failure.retryable)

        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        assertEquals(WriteState.Working, profile.avatar)
        assertTrue(profile.busy)
        writes.close()
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        assertEquals(1, rig.admin.uploads.size)
        assertEquals(SchedulingAdminUploadTarget.AVATAR, rig.admin.uploads.single().target)
        assertEquals("https://cdn/a.png", profile.me.avatarUrl)
        assertEquals(
            R.string.scheduling_admin_settings_avatar_uploaded,
            (profile.avatar as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(SettingsSaved.Profile(me.copy(avatarUrl = "https://cdn/a.png")), saved.single())

        // An upload that answered no address re-reads the profile.
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(avatarUrl = ""))
        rig.admin.value(SchedulingAdminOp.ME_GET, me.copy(avatarUrl = "https://cdn/b.png"))
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        assertEquals("https://cdn/b.png", profile.me.avatarUrl)
        assertEquals(2, saved.size)

        // A failed re-read leaves the row alone; a failed upload says why.
        rig.admin.clear(SchedulingAdminOp.ME_GET)
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        assertEquals("https://cdn/b.png", profile.me.avatarUrl)
        assertTrue(profile.avatar is WriteState.Done)
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Refused("unavailable")
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        assertTrue(profile.avatar is WriteState.Failed)
        // A target this dialog does not hold is ignored, refused or not.
        writes.images.choose(SchedulingAdminUploadTarget.LOGO)
        writes.images.upload(png)
        writes.images.choose(SchedulingAdminUploadTarget.LOGO)
        writes.images.upload(null)
        assertEquals(4, rig.admin.uploads.size)
    }

    @Test
    fun `logo and banner uploads re-read the booking page without touching the drafts`() {
        val fresh = schedulingBranding(logoUrl = "https://cdn/new.png")
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(logoUrl = "https://cdn/new.png"))
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_GET, fresh)
        writes.branding.open(schedulingBranding())
        writes.branding.edit { it.copy(businessName = "Typing") }
        writes.images.choose(SchedulingAdminUploadTarget.LOGO)
        writes.images.upload(png)
        assertEquals(WriteState.Working, brandingDialog.logo)
        assertTrue(brandingDialog.busy)
        scope.advanceUntilIdle()
        assertEquals(fresh, brandingDialog.branding)
        assertEquals("Typing", brandingDialog.draft.businessName)
        assertEquals(
            R.string.scheduling_admin_settings_logo_uploaded,
            (brandingDialog.logo as WriteState.Done).message.resourceIdOrNull,
        )
        assertEquals(SettingsSaved.Branding(fresh), saved.single())

        writes.images.choose(SchedulingAdminUploadTarget.BANNER)
        writes.images.upload(png.copy(mimeType = "image/svg+xml"))
        assertEquals(
            R.string.scheduling_admin_settings_image_wrong_type,
            (brandingDialog.banner as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        writes.images.choose(SchedulingAdminUploadTarget.BANNER)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_settings_banner_uploaded,
            (brandingDialog.banner as WriteState.Done).message.resourceIdOrNull,
        )
        // The avatar is not this dialog's.
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        assertEquals(2, rig.admin.uploads.size)
    }

    @Test
    fun `an answer for a dialog closed or swapped while it was out is left alone`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.gate = gate
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(logoUrl = "https://cdn/x.png"))
        writes.branding.open(schedulingBranding())
        writes.images.choose(SchedulingAdminUploadTarget.LOGO)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        // Swapped to the profile: the avatar flow ignores the logo's late answer.
        writes.profile.open(me)
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        writes.close()
        assertTrue(writes.dialog.value is SettingsDialog.Profile)
        rig.admin.gate = null
        gate.complete(Unit)
        scope.advanceUntilIdle()
        // The avatar upload answered no avatar URL: a re-read, which fails offline and leaves it.
        assertTrue(profile.avatar is WriteState.Done)
        writes.close()
        assertNull(writes.dialog.value)

        val second = CompletableDeferred<Unit>()
        rig.admin.gate = second
        writes.branding.open(schedulingBranding())
        writes.images.choose(SchedulingAdminUploadTarget.LOGO)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        writes.notifications.open(me)
        writes.close()
        rig.admin.gate = null
        second.complete(Unit)
        scope.advanceUntilIdle()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `an avatar address that lands after the dialog was swapped re-reads instead`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.gate = gate
        rig.admin.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(avatarUrl = "https://cdn/a.png"))
        writes.profile.open(me)
        writes.images.choose(SchedulingAdminUploadTarget.AVATAR)
        writes.images.upload(png)
        scope.advanceUntilIdle()
        writes.branding.open(schedulingBranding())
        rig.admin.gate = null
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
        assertEquals(WriteState.Idle, brandingDialog.logo)
    }

    @Test
    fun `removing an image asks first, clears the question before sending, and re-reads`() {
        rig.admin.value(SchedulingAdminOp.ME_AVATAR_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE, SchedulingNoContent(ok = true))
        rig.admin.fail(SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE, offline)
        rig.admin.value(SchedulingAdminOp.ME_GET, me)
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_GET, schedulingBranding(logoUrl = ""))
        // Nothing open: ignored.
        writes.images.askRemove(SchedulingAdminUploadTarget.AVATAR)
        writes.images.cancelRemove()
        writes.images.confirmRemove()

        writes.profile.open(me.copy(avatarUrl = "https://cdn/a.png"))
        writes.images.confirmRemove()
        writes.images.askRemove(SchedulingAdminUploadTarget.LOGO)
        writes.images.askRemove(SchedulingAdminUploadTarget.BANNER)
        assertFalse(profile.confirmingRemove)
        writes.images.askRemove(SchedulingAdminUploadTarget.AVATAR)
        assertTrue(profile.confirmingRemove)
        writes.images.cancelRemove()
        assertFalse(profile.confirmingRemove)
        writes.images.askRemove(SchedulingAdminUploadTarget.AVATAR)
        writes.images.confirmRemove()
        assertFalse(profile.confirmingRemove)
        assertEquals(WriteState.Working, profile.avatar)
        writes.images.askRemove(SchedulingAdminUploadTarget.AVATAR)
        writes.images.confirmRemove()
        scope.advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.ME_AVATAR_DELETE).size)
        assertNull(profile.me.avatarUrl)
        assertEquals(
            R.string.scheduling_admin_settings_avatar_removed,
            (profile.avatar as WriteState.Done).message.resourceIdOrNull,
        )

        writes.branding.open(schedulingBranding())
        writes.images.askRemove(SchedulingAdminUploadTarget.AVATAR)
        assertNull(brandingDialog.confirmingRemove)
        writes.images.confirmRemove()
        writes.images.askRemove(SchedulingAdminUploadTarget.LOGO)
        writes.images.cancelRemove()
        assertNull(brandingDialog.confirmingRemove)
        writes.images.askRemove(SchedulingAdminUploadTarget.LOGO)
        assertEquals(SchedulingAdminUploadTarget.LOGO, brandingDialog.confirmingRemove)
        writes.images.confirmRemove()
        scope.advanceUntilIdle()
        assertEquals("", brandingDialog.branding.logoUrl)
        assertEquals(
            R.string.scheduling_admin_settings_logo_removed,
            (brandingDialog.logo as WriteState.Done).message.resourceIdOrNull,
        )
        writes.images.askRemove(SchedulingAdminUploadTarget.BANNER)
        writes.images.confirmRemove()
        scope.advanceUntilIdle()
        assertTrue(brandingDialog.banner is WriteState.Failed)

        rig.admin.clear(SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE)
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE, SchedulingNoContent(ok = true))
        writes.images.askRemove(SchedulingAdminUploadTarget.BANNER)
        writes.images.confirmRemove()
        // While the banner is on its way, the booking page cannot be saved or closed.
        writes.branding.save()
        writes.close()
        assertTrue(brandingDialog.busy)
        scope.advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_settings_banner_removed,
            (brandingDialog.banner as WriteState.Done).message.resourceIdOrNull,
        )
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_PATCH).isEmpty())

        // A dialog without images neither asks nor removes.
        writes.notifications.open(me)
        writes.images.askRemove(SchedulingAdminUploadTarget.LOGO)
        writes.images.cancelRemove()
        writes.images.confirmRemove()
        assertTrue(writes.dialog.value is SettingsDialog.Notifications)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE).size)
    }
}
