package com.distronode.districtai.ui.workflows

import androidx.compose.runtime.Composable
import com.distronode.districtai.l10n.WireCategory
import com.distronode.districtai.l10n.wireLabel

// ⚠️ Beside WorkflowCards.kt rather than in it only because that file is at detekt's 11-function
// file ceiling; [RunCard] there is the one caller.

/**
 * The code an action's `reason` starts with: the engine writes `<code>: <detail>`, where the detail
 * can name a number, a tag or a status, and an older row may hold the bare code. Everything before the
 * first colon, as the portal reads it (workflowsI18n.ts `reasonLabelLookup`).
 */
internal fun actionReasonCode(reason: String): String = reason.substringBefore(':').trim()

/**
 * What a run card shows for an action's `reason`: its code's label (« Texto non envoyé, … » in
 * French), never the engine's English sentence.
 *
 * ⚠️ A CODE THIS BUILD DOES NOT KNOW SHOWS THE WHOLE REASON through the category's unknown label, so
 * English reads exactly as it did before 3.3.0 and the operator still sees what the engine said. A
 * known code drops the detail, as the vocabulary says an app does.
 */
@Composable
internal fun actionReasonLabel(reason: String): String {
    val code = actionReasonCode(reason)
    return wireLabel(
        WireCategory.WORKFLOW_ACTION_REASON,
        if (code in WireCategory.WORKFLOW_ACTION_REASON.labels) code else reason.trim(),
    )
}
