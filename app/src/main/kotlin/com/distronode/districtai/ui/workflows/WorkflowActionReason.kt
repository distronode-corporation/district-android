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
    HTTP_STATUS_CODE.matchEntire(code)?.let { match ->
        return wireLabel(WireCategory.WORKFLOW_ACTION_REASON, HTTP_STATUS_KEY)
            .replace(STATUS_PLACEHOLDER, match.groupValues[1])
    }
    return wireLabel(
        WireCategory.WORKFLOW_ACTION_REASON,
        if (code in WireCategory.WORKFLOW_ACTION_REASON.labels) code else reason.trim(),
    )
}

/**
 * ⚠️ THE ONE CODE THAT CARRIES A VALUE. The vocabulary lists `http-{status}`, but the engine writes the
 * status itself (`http-502: ops webhook returned 502 Bad Gateway`, workflows.ts), so an exact lookup
 * never matched it and French read « Valeur inconnue : http-502: … ». The catalog's label keeps a
 * literal `{status}`, not a format argument, and the number is put in here (Seer on #116).
 */
private val HTTP_STATUS_CODE = Regex("""http-(\d{3})""")
private const val HTTP_STATUS_KEY = "http-{status}"
private const val STATUS_PLACEHOLDER = "{status}"
