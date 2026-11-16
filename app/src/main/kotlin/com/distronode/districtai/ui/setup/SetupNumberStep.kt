package com.distronode.districtai.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SETUP_STEP_DONE

/**
 * Step 2, "Your number".
 *
 * ⛔ BUYING A NUMBER CHARGES A CARD AND STAYS ON THE WEB. An owner who already holds one keeps it here;
 * one who holds none opens the setup page in a Custom Tab, where the web's own wizard opens on this
 * step, and the wizard reads its state again when the person comes back. A number bought there marks
 * the step done, so this one moves on by itself.
 *
 * ⚠️ "WHERE CALLS ARE ANSWERED" IS THE WORKSPACE'S REGION, the residency fact the web states on the
 * same step.
 */
@Composable
internal fun SetupNumberStep(wizard: SetupWizardViewModel, actions: SetupActions) {
    val held = wizard.data?.numbersHeld ?: 0
    if (held > 0) {
        SetupCard(heading = stringResource(R.string.setup_number_title)) {
            SetupNote(stringResource(R.string.setup_number_have_lead))
            NumberPlan(wizard, held)
            SetupPrimaryButton(
                title = stringResource(R.string.setup_number_keep),
                busy = wizard.isCompleting,
                tag = NumberTags.KEEP,
                onClick = @DontMemoize { wizard.completeStep(SetupStep.NUMBER, SETUP_STEP_DONE) },
            )
        }
    } else {
        SetupCard(heading = stringResource(R.string.setup_number_none_heading)) {
            SetupNote(stringResource(R.string.setup_number_none_body))
            NumberPlan(wizard, held)
            SetupHandOffButton(
                title = stringResource(R.string.setup_number_open_page),
                page = SetupHandOff.SETUP,
                actions = actions,
                tag = NumberTags.OPEN_PAGE,
            )
        }
    }
}

@Composable
private fun NumberPlan(wizard: SetupWizardViewModel, held: Int) {
    val included = wizard.data?.includedNumbers
    SetupNote(
        text = if (included != null) {
            stringResource(R.string.setup_number_included, held, included)
        } else {
            stringResource(R.string.setup_number_held, held)
        },
        small = true,
        modifier = Modifier.testTag(NumberTags.PLAN),
    )
    answeredIn(wizard.region)?.let {
        SetupNote(stringResource(it), small = true, modifier = Modifier.testTag(NumberTags.ANSWERED))
    }
}

internal fun answeredIn(region: String?): Int? = when (region) {
    "us" -> R.string.setup_number_answered_us
    "ca" -> R.string.setup_number_answered_ca
    "eu" -> R.string.setup_number_answered_eu
    "apac" -> R.string.setup_number_answered_apac
    else -> null
}

internal object NumberTags {
    const val KEEP = "district-setup-number-keep"
    const val OPEN_PAGE = "district-setup-number-open-page"
    const val PLAN = "district-setup-number-plan"
    const val ANSWERED = "district-setup-number-answered"
}
