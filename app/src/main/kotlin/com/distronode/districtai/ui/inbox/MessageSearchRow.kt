package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.displayName

/**
 * One full-content search hit.
 *
 * ⛔ A ROW PER MESSAGE, NOT PER THREAD, AND THE LIST KEY IS `messageId`. Two matches in one
 * conversation are two rows the server deliberately sent separately, so keying on `threadKey` here
 * would be a DUPLICATE key — a rendering fault in a `LazyColumn` rather than a cosmetic repeat.
 *
 * ⚠️ THE SUBJECT IS SHOWN WHEN THERE IS ONE. It is null on every SMS hit, which is most of them,
 * and an email whose match is in the subject would otherwise show a body with no visible reason for
 * being in the list.
 */
@Composable
internal fun MessageSearchRow(hit: MessageSearchHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(
                horizontal = DistrictTheme.spacing.gutter,
                vertical = DistrictTheme.spacing.row,
            ),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Text(
            // ⚠️ THE COUNTERPART IS THE FALLBACK, NOT A BLANK. A hit with no `Contact` row is an
            // ordinary result, and the address is the only thing that identifies it.
            text = hit.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        hit.subject?.takeIf { it.isNotBlank() }?.let { subject ->
            Text(
                text = subject,
                style = MaterialTheme.typography.labelMedium,
                color = DistrictTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = hit.body,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            maxLines = BODY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ROW_MIN_HEIGHT = 56.dp
private const val BODY_MAX_LINES = 2
