package com.distronode.districtai.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import java.time.Instant
import java.time.ZoneId

/**
 * "Offline. Showing what was saved at 3:04 p.m." above every screen, while a screen shows a saved
 * copy (`SavedReads.showingSince`, the oldest copy on screen). The iOS app's banner. Nothing at all
 * while every read answers live.
 *
 * ⚠️ ONE PARAMETER, ON PURPOSE: the wording is decided by [offlineBannerText], a plain function, so
 * the composable carries none of the default-argument and change-mask branches no test can reach.
 *
 * @param savedAtMillis when the oldest saved copy on screen was saved, or null.
 */
@Composable
internal fun OfflineBanner(savedAtMillis: Long?) {
    if (savedAtMillis == null) return
    val text = offlineBannerText(savedAtMillis, System.currentTimeMillis(), ZoneId.systemDefault(), appLanguage())
    Text(
        text = stringResource(text.resource, text.stamp),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(DistrictTheme.colors.muted)
            .padding(horizontal = DistrictTheme.spacing.gutter, vertical = DistrictTheme.spacing.tight)
            .testTag(OFFLINE_BANNER_TAG),
    )
}

/** The banner's sentence and the time (or date and time) it names. */
internal data class OfflineBannerText(@StringRes val resource: Int, val stamp: String)

/**
 * ⚠️ THE DATE TOO WHEN THE COPY IS FROM AN EARLIER DAY in [zone], so a week-old copy never reads as
 * today's.
 */
internal fun offlineBannerText(
    savedAtMillis: Long,
    nowMillis: Long,
    zone: ZoneId,
    language: AppLanguage,
): OfflineBannerText {
    val saved = Instant.ofEpochMilli(savedAtMillis)
    val today = saved.atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return if (today) {
        OfflineBannerText(R.string.session_offline_saved_today, DistrictFormat.time(saved, zone, language))
    } else {
        OfflineBannerText(R.string.session_offline_saved_earlier, DistrictFormat.dateTime(saved, zone, language))
    }
}

/** The banner's test handle. */
internal const val OFFLINE_BANNER_TAG: String = "district-offline-banner"
