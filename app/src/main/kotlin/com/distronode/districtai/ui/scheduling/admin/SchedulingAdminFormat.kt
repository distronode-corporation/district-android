package com.distronode.districtai.ui.scheduling.admin

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

// The scheduling admin's readings of times, lengths and links. Dates and times go through
// DistrictFormat, which is pinned to the format vectors, so a booking reads the way every other
// time in this app reads, in English and in French.

/** An ISO instant from the scheduler, or null when it is not one. */
fun schedulingInstantOrNull(iso: String?): Instant? = try {
    iso?.let(Instant::parse)
} catch (_: DateTimeParseException) {
    null
}

/** The zone [timezone] names, or UTC when it names none this device knows. */
fun schedulingZone(timezone: String): ZoneId = try {
    ZoneId.of(timezone)
} catch (_: DateTimeException) {
    ZoneId.of(UTC)
}

/** Today's date in [timezone], `YYYY-MM-DD`: what "from today" means for the member, not the device. */
fun schedulingToday(timezone: String, now: Instant): String =
    now.atZone(schedulingZone(timezone)).toLocalDate().toString()

/**
 * A public booking link: `https://<host>/book/<slug>`.
 *
 * ⚠️ THE SLUG IS ENCODED AS A COMPONENT. `Uri.encode` leaves exactly `encodeURIComponent`'s
 * unreserved set alone, which is what the web's link builder uses.
 */
fun schedulingBookingUrl(publicHost: String, slug: String): String = "https://$publicHost/book/${Uri.encode(slug)}"

/** A length in minutes ("30 min"), or "Not set". */
@Composable
@ReadOnlyComposable
fun schedulingMinutes(minutes: Int?): String = if (minutes == null) {
    stringResource(R.string.scheduling_admin_common_not_set)
} else {
    stringResource(R.string.scheduling_admin_common_minutes, minutes)
}

/** A date and time in the member's zone ("Oct 2, 2026, 1:00 p.m."), or "Unknown". */
@Composable
@ReadOnlyComposable
fun schedulingDateTime(iso: String?, timezone: String): String {
    val instant = schedulingInstantOrNull(iso) ?: return stringResource(R.string.scheduling_admin_common_unknown_time)
    return DistrictFormat.dateTime(instant, schedulingZone(timezone), appLanguage())
}

/** A time of day in the member's zone ("1:00 p.m."), or "Unknown". */
@Composable
@ReadOnlyComposable
fun schedulingTime(iso: String?, timezone: String): String {
    val instant = schedulingInstantOrNull(iso) ?: return stringResource(R.string.scheduling_admin_common_unknown_time)
    return DistrictFormat.time(instant, schedulingZone(timezone), appLanguage())
}
