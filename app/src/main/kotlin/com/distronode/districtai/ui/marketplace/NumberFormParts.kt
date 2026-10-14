package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors

// The parts every number-management card and form is built from, and their test handles.

/** A card with an eyebrow, the shape every number-management card takes. */
@Composable
internal fun SectionCard(eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    DistrictCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Eyebrow(eyebrow)
            content()
        }
    }
}

/** One labelled text field of a number-management form. */
@Composable
internal fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else MULTILINE_ROWS,
        colors = districtFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag(numberFieldTag(label)),
    )
}

private const val MULTILINE_ROWS = 3

/** A field's test handle, from its label resource. */
fun numberFieldTag(label: Int): String = "district-number-field-$label"

fun registrationStatusTag(id: String): String = "district-number-registration-status-$id"

fun registrationSubmitTag(id: String): String = "district-number-registration-submit-$id"

fun documentRemoveTag(id: String): String = "district-number-document-remove-$id"
