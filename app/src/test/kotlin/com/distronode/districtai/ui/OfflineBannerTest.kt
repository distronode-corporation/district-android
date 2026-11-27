// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.str
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Offline. Showing what was saved at...": the time today, the date and time before today. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class OfflineBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("America/Toronto")

    /** 2026-11-17 15:04 in Toronto. */
    private val saved = Instant.parse("2026-11-17T20:04:00Z")

    @Test
    fun `a copy saved today names the time`() {
        val later = Instant.parse("2026-11-17T23:00:00Z").toEpochMilli()

        assertEquals(
            OfflineBannerText(R.string.session_offline_saved_today, DistrictFormat.time(saved, zone, AppLanguage.EN)),
            offlineBannerText(saved.toEpochMilli(), later, zone, AppLanguage.EN),
        )
    }

    @Test
    fun `a copy saved on an earlier day names the date and the time`() {
        val nextMorning = Instant.parse("2026-11-18T06:00:00Z").toEpochMilli()

        assertEquals(
            OfflineBannerText(
                R.string.session_offline_saved_earlier,
                DistrictFormat.dateTime(saved, zone, AppLanguage.EN),
            ),
            offlineBannerText(saved.toEpochMilli(), nextMorning, zone, AppLanguage.EN),
        )
    }

    @Test
    fun `a copy on screen shows the banner with the time it was saved`() {
        val now = System.currentTimeMillis()
        composeRule.setContent { DistrictTheme { OfflineBanner(savedAtMillis = now) } }

        val expected = offlineBannerText(now, now, ZoneId.systemDefault(), AppLanguage.EN)
        composeRule.onNodeWithTag(OFFLINE_BANNER_TAG).assertTextEquals(str(expected.resource, expected.stamp))
    }

    @Test
    fun `every read answered live shows nothing`() {
        composeRule.setContent { DistrictTheme { OfflineBanner(savedAtMillis = null) } }

        composeRule.onNodeWithTag(OFFLINE_BANNER_TAG).assertDoesNotExist()
    }
}
