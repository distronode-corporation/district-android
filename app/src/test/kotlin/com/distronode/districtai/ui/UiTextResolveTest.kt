package com.distronode.districtai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The composable [resolve], the one place a [UiText] becomes a `String` on screen.
 *
 * ⚠️ A resource with its one substitution and one without are two different `stringResource`
 * calls; each is rendered here so neither form can silently render the other's text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class UiTextResolveTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a literal, a plain resource and a resource with its argument each render their own text`() {
        composeRule.setContent {
            Column {
                Text(UiText.Literal("Straight from the server").resolve())
                Text(UiText.Resource(R.string.overview_retry).resolve())
                Text(UiText.Resource(R.string.billing_meter_month, "2026-08").resolve())
            }
        }

        composeRule.onNodeWithText("Straight from the server").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onNodeWithText("Billing month 2026-08").assertIsDisplayed()
    }
}
