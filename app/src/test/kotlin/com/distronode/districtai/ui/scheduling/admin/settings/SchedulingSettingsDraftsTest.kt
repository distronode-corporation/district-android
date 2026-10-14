package com.distronode.districtai.ui.scheduling.admin.settings

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingBrandingUpdate
import com.distronode.districtai.core.data.SchedulingMeUpdate
import com.distronode.districtai.ui.inbox.PickedAttachment
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The checks each settings form makes before it sends, in the reference's order. */
class SchedulingSettingsDraftsTest {

    private val profile = SchedulingProfileDraft.from(schedulingMe(timezone = "America/Toronto"))

    private val branding = SchedulingBrandingUpdate(
        businessName = "Acme",
        logoHeight = 48,
        logoOpacity = 100,
        bannerOpacity = 80,
        privacyUrl = "",
        termsUrl = "https://acme.example/terms",
        fallbackLocale = "en",
    )

    @Test
    fun `the profile is refused field by field, in order, and sends all five fields trimmed`() {
        assertNull(profile.rejection(storedZone = "America/Toronto"))
        fun rejected(draft: SchedulingProfileDraft) = draft.rejection(storedZone = "Mars/Olympus")
        assertEquals(R.string.scheduling_admin_settings_name_required, rejected(profile.copy(name = "  ")))
        assertEquals(R.string.scheduling_admin_settings_name_too_long, rejected(profile.copy(name = "x".repeat(201))))
        assertEquals(R.string.scheduling_admin_settings_timezone_required, rejected(profile.copy(timezone = " ")))
        assertEquals(
            R.string.scheduling_admin_settings_timezone_unknown,
            rejected(profile.copy(timezone = "Nowhere/Land")),
        )
        // The stored zone is accepted even when this device does not know it.
        assertNull(rejected(profile.copy(timezone = "Mars/Olympus")))
        assertEquals(R.string.scheduling_admin_settings_time_format_required, rejected(profile.copy(timeFormat = "")))
        assertEquals(R.string.scheduling_admin_settings_week_start_required, rejected(profile.copy(weekStart = 7)))
        assertEquals(R.string.scheduling_admin_settings_week_start_required, rejected(profile.copy(weekStart = -1)))
        assertEquals(R.string.scheduling_admin_settings_date_format_required, rejected(profile.copy(dateFormat = "x")))
        val update = profile.copy(name = " Ana ", timezone = " Europe/Paris ").toUpdate()
        assertEquals(
            SchedulingMeUpdate(
                name = "Ana",
                timezone = "Europe/Paris",
                timeFormat = "12h",
                weekStart = 1,
                dateFormat = "mdy",
            ),
            update,
        )
    }

    @Test
    fun `the switches send all seven, each to its own field`() {
        val values = SchedulingNotifySwitch.entries.associateWith { it == SchedulingNotifySwitch.HOST_CANCEL }
        val update = values.toUpdate()
        assertEquals(true, update.notifyHostCancel)
        assertEquals(false, update.notifyCancellation)
        assertEquals(false, update.notifyConfirmation)
        assertEquals(false, update.notifyReschedule)
        assertEquals(false, update.notifyReminder)
        assertEquals(false, update.notifyHostBooking)
        assertEquals(false, update.notifyHostReschedule)
        assertNull(update.name)
        assertEquals(
            listOf(SchedulingNotifyGroup.ATTENDEE, SchedulingNotifyGroup.HOST),
            SchedulingNotifySwitch.entries.map { it.group }.distinct(),
        )
    }

    @Test
    fun `the booking page is refused for a long name, out-of-range numbers, bad links or no language`() {
        assertNull(branding.rejection())
        val opacity = R.string.scheduling_admin_settings_opacity_range
        assertEquals(opacity, branding.copy(logoOpacity = 101).rejection())
        assertEquals(opacity, branding.copy(bannerOpacity = 19).rejection())
        assertEquals(
            R.string.scheduling_admin_settings_business_name_too_long,
            branding.copy(businessName = "x".repeat(201)).rejection(),
        )
        assertEquals(R.string.scheduling_admin_settings_logo_height_range, branding.copy(logoHeight = 0).rejection())
        assertEquals(R.string.scheduling_admin_settings_logo_height_range, branding.copy(logoHeight = 65).rejection())
        assertEquals(R.string.scheduling_admin_settings_opacity_range, branding.copy(logoOpacity = 19).rejection())
        assertEquals(R.string.scheduling_admin_settings_opacity_range, branding.copy(bannerOpacity = 101).rejection())
        assertEquals(
            R.string.scheduling_admin_settings_url_not_absolute,
            branding.copy(privacyUrl = "acme").rejection(),
        )
        assertEquals(
            R.string.scheduling_admin_settings_url_not_absolute,
            branding.copy(termsUrl = "ftp://a.b").rejection(),
        )
        assertEquals(
            R.string.scheduling_admin_settings_fallback_locale_required,
            branding.copy(fallbackLocale = " ").rejection(),
        )
    }

    @Test
    fun `a footer link may be empty, http or https, and nothing else`() {
        assertNull(legalUrlRejection("  "))
        assertNull(legalUrlRejection("http://acme.example/privacy"))
        assertNull(legalUrlRejection(" HTTPS://acme.example "))
        assertEquals(
            R.string.scheduling_admin_settings_url_too_long,
            legalUrlRejection("https://a.b/" + "x".repeat(500)),
        )
        assertEquals(R.string.scheduling_admin_settings_url_not_absolute, legalUrlRejection("example.com"))
        assertEquals(R.string.scheduling_admin_settings_url_not_absolute, legalUrlRejection("https:///x"))
        assertEquals(R.string.scheduling_admin_settings_url_not_absolute, legalUrlRejection("https://a b"))
    }

    @Test
    fun `a picked image is unreadable, too large, the wrong type, or sent`() {
        fun picked(size: Int, type: String = "image/png") = PickedAttachment("logo.png", type, ByteArray(size))
        fun reason(check: SchedulingImageCheck) = (check as SchedulingImageCheck.Refuse).reason
        assertEquals(R.string.scheduling_admin_settings_image_unreadable, reason(null.imageCheck()))
        assertEquals(R.string.scheduling_admin_settings_image_unreadable, reason(picked(0).imageCheck()))
        assertEquals(
            R.string.scheduling_admin_settings_image_too_large,
            reason(picked(5 * 1024 * 1024 + 1).imageCheck()),
        )
        assertEquals(
            R.string.scheduling_admin_settings_image_wrong_type,
            reason(picked(10, "image/svg+xml").imageCheck()),
        )
        val ok = picked(5 * 1024 * 1024, "image/webp")
        assertEquals(SchedulingImageCheck.Send(ok), ok.imageCheck())
    }
}
