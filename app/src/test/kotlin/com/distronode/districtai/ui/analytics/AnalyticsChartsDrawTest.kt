package com.distronode.districtai.ui.analytics

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictColors
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.EngagementPoint
import com.distronode.districtai.core.model.FunnelStage
import com.distronode.districtai.core.model.SentimentSlice
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What each chart DECIDES to fill, read off the drawn card.
 *
 * ⚠️ NOT A PIXEL COMPARISON. Every assertion here is a yes/no about one colour anywhere in the
 * card: whether the brand accent was filled at all, or whether a server-sent shade made it onto the
 * bar. That is the decision the chart makes (a zero is drawn as an empty track, a real count is
 * not, a readable colour is used as sent), and it survives any change to sizes, spacing or
 * anti-aliasing. The other card tests read labels and semantics; these exist because the bars
 * themselves carry no text a semantics query could see.
 *
 * ⛔ NATIVE GRAPHICS, because the legacy mode never runs a draw pass, so a `Canvas` block would be
 * composed and measured and never asked to paint anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w600dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsChartsDrawTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accent = DistrictColors.Light.district

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { DistrictTheme(darkTheme = false) { content() } }
    }

    private fun colorsIn(description: String): Set<Color> {
        val pixels = composeRule.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        val seen = mutableSetOf<Color>()
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) seen += pixels[x, y]
        }
        return seen
    }

    private fun point(calls: Int) = EngagementPoint(date = "d$calls", isoDate = "", calls = calls, avgDuration = 0)

    @Test
    fun `a trend with calls fills its bars`() {
        show { TrendCard(listOf(point(9), point(0), point(11))) }
        assertTrue(accent in colorsIn(ANALYTICS_TREND_DESCRIPTION))
    }

    @Test
    fun `an all-zero trend is drawn as bare track with no filled bar`() {
        show { TrendCard(listOf(point(0), point(0), point(0))) }
        assertFalse(accent in colorsIn(ANALYTICS_TREND_DESCRIPTION))
    }

    @Test
    fun `a funnel with dials fills its bars`() {
        show { FunnelCard(listOf(FunnelStage("Total Dials", 48), FunnelStage("Connected Calls", 0))) }
        assertTrue(accent in colorsIn(ANALYTICS_FUNNEL_DESCRIPTION))
    }

    @Test
    fun `an empty funnel is drawn as bare track`() {
        show { FunnelCard(listOf(FunnelStage("Total Dials", 0), FunnelStage("Connected Calls", 0))) }
        assertFalse(accent in colorsIn(ANALYTICS_FUNNEL_DESCRIPTION))
    }

    @Test
    fun `sentiment bands are painted in the shades the server sent, and a bad shade falls back`() {
        show {
            SentimentCard(
                listOf(
                    SentimentSlice("Positive Sentiment", 21, "#10b981"),
                    SentimentSlice("Neutral Sentiment", 16, "not a colour"),
                ),
            )
        }
        val seen = colorsIn(ANALYTICS_SENTIMENT_DESCRIPTION)
        assertTrue(Color(0xFF10B981) in seen)
        // The unreadable shade is drawn in the brand accent rather than dropping the band.
        assertTrue(accent in seen)
    }

    @Test
    fun `a sentiment total of zero paints no band at all`() {
        show { SentimentCard(listOf(SentimentSlice("Positive Sentiment", 0, "#10b981"))) }
        assertFalse(Color(0xFF10B981) in colorsIn(ANALYTICS_SENTIMENT_DESCRIPTION))
    }

    @Test
    fun `a history month with metered minutes fills its bar`() {
        show { UsageHistoryCard(listOf(UsageData(month = "2026-08", callMinutesInbound = 30.0))) }
        assertTrue(accent in colorsIn(ANALYTICS_HISTORY_DESCRIPTION))
    }

    @Test
    fun `history months with no minutes metered draw no filled bar`() {
        show { UsageHistoryCard(listOf(UsageData(month = "2026-08", smsOutbound = 4.0))) }
        assertFalse(accent in colorsIn(ANALYTICS_HISTORY_DESCRIPTION))
    }
}
