package com.distronode.districtai.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.designsystem.districtSwitchColors

/**
 * The pages the wizard hands to the web, in a Custom Tab, for what stays there.
 *
 * ⛔ EVERY DESTINATION IS UNDER `/dashboard/district`, A PATH THIS APP CLAIMS AS AN APP LINK, so the
 * launch goes through `CustomTabsLauncher.launchExternally`, pinned to a browser package, never an
 * implicit intent that would route straight back here. The wizard reads its state again when the
 * person comes back.
 */
enum class SetupHandOff(private val path: String) {
    /** The web wizard, which opens by itself on the dashboard home at its first open step: buying a number. */
    SETUP(""),

    /** Studio, Skills: the email sender name and reply-to, and the transfer number. */
    SKILLS("/studio/skills"),

    /** Studio, Integrations: a calendar's sign-in. */
    INTEGRATIONS("/studio/integrations"),
    ;

    /**
     * ⚠️ DERIVED FROM THE BUILD'S API BASE, never a literal, so a staging build opens staging; and null
     * off HTTPS (a loopback test server), where the page could not be what it claims to be.
     */
    fun destination(apiBaseUrl: String): String? =
        if (apiBaseUrl.startsWith(HTTPS)) com.distronode.districtai.ui.overview.setupWebUrl(apiBaseUrl) + path else null

    private companion object {
        const val HTTPS = "https://"
    }
}

/** A card of the wizard, with an optional heading. */
@Composable
internal fun SetupCard(heading: String? = null, content: @Composable ColumnScope.() -> Unit) {
    DistrictCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            heading?.let { Eyebrow(it) }
            content()
        }
    }
}

/** A quiet sentence: a lead, a hint, a status. */
@Composable
internal fun SetupNote(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = DistrictTheme.colors.mutedForeground,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A sentence in the destructive ink, for a field's or a step's refusal. */
@Composable
internal fun SetupErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A labelled text field with its hint and error under it.
 *
 * ⚠️ THE ERROR IS ALSO THE FIELD'S `isError`, so TalkBack says the field is in error rather than
 * leaving a red line alone to carry it.
 */
@Composable
internal fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    hint: String? = null,
    error: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            singleLine = !multiline,
            minLines = if (multiline) 2 else 1,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            colors = districtFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
        )
        hint?.let { SetupNote(it, small = true) }
        error?.let { SetupErrorLine(it) }
    }
}

/** One option of a menu. */
internal data class SetupOption(val value: String, val label: String)

/**
 * A labelled menu of choices, with a placeholder while nothing is chosen.
 *
 * ⚠️ A ROW THAT OPENS A MENU, the same shape as the Voice Studio's pickers, so an owner who has not
 * chosen a country yet sees the placeholder and knows there is a choice to make.
 */
@Composable
internal fun SetupPicker(
    label: String,
    placeholder: String,
    selected: String,
    options: List<SetupOption>,
    tag: String,
    onSelect: (String) -> Unit,
    error: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        Box {
            DistrictListRow(
                title = label,
                subtitle = options.firstOrNull { it.value == selected }?.label ?: placeholder,
                onClick = @DontMemoize { expanded = true }.takeIf { enabled },
                modifier = Modifier.testTag(tag),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = @DontMemoize { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = @DontMemoize {
                            expanded = false
                            onSelect(option.value)
                        },
                        modifier = Modifier.testTag("$tag-option-${option.value}"),
                    )
                }
            }
        }
        error?.let { SetupErrorLine(it) }
    }
}

/**
 * One option of a single choice, its words and a line under them, the whole row the target.
 *
 * ⚠️ THE SAME `selectable` RADIO ROW AS `DistrictRadioRow`, with the detail line that control has no
 * room for. The radio takes no click of its own, so one choice is one semantics node with the
 * radio-button role, and "selected" is said by the role rather than by a colour.
 */
@Composable
internal fun SetupChoiceRow(
    title: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = DistrictTheme.colors.district,
                unselectedColor = DistrictTheme.colors.mutedForeground,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.foreground)
            detail?.let { SetupNote(it, small = true) }
        }
    }
}

/** A labelled switch, the whole row the target. */
@Composable
internal fun SetupSwitchRow(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = districtSwitchColors(),
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * A button that opens one of the web's pages in a Custom Tab.
 *
 * ⚠️ DRAWN DISABLED, NOT HIDDEN, WHEN THE BUILD'S HOST IS NOT HTTPS: the step still says where its rest
 * is done, and nothing opens a page that could not be the real one.
 */
@Composable
internal fun SetupHandOffButton(title: String, page: SetupHandOff, actions: SetupActions, tag: String) {
    DistrictButton(
        text = title,
        onClick = @DontMemoize { actions.openHandOff(page) },
        variant = ButtonVariant.Secondary,
        enabled = actions.canOpen(page),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

/** A primary action that says it is working while it is. */
@Composable
internal fun SetupPrimaryButton(
    title: String,
    busy: Boolean,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    DistrictButton(
        text = if (busy) stringResource(R.string.setup_saving) else title,
        onClick = onClick,
        enabled = !busy && enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

/** What the screen asks of its host: the pages it hands to the web. */
interface SetupActions {
    /** Whether [page] can be opened in this build (an HTTPS host). */
    fun canOpen(page: SetupHandOff): Boolean

    fun openHandOff(page: SetupHandOff)
}
