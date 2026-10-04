package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.settings.workspace.ConfigLoadFailure
import com.distronode.districtai.ui.settings.workspace.ConfigSkeleton
import com.distronode.districtai.ui.settings.workspace.rememberUnsavedChangesGuard

/**
 * The native Voice Studio: recipes on top, the signal chain Ear → Turn-taking → Brain → Voice (a
 * realtime engine is one block), the time-to-first-word meter, where the call is processed, and
 * an editor per leg with its tuning behind Advanced.
 *
 * ⛔ EVERY LABEL FROM THE SERVER IS RENDERED VERBATIM, in the reader's PORTAL locale. Only this
 * app's own chrome (the top bar before the read lands, the change count, a meter the server has
 * not described yet) comes from `strings.xml`.
 *
 * ⛔ A FAILED READ OFFERS A RETRY AND NOTHING ELSE. Without the catalogue there is nothing an edit
 * could be checked against, and the PATCH refuses a chain it does not accept.
 */
@Composable
fun VoiceStudioScreen(state: VoiceStudioUiState, actions: VoiceStudioActions, onBack: () -> Unit) {
    val ready = state as? VoiceStudioUiState.Ready
    val guardedBack = rememberUnsavedChangesGuard(ready?.dirty == true, onBack)
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = ready?.studio?.labels?.heading ?: stringResource(R.string.voice_studio_title),
                onBack = guardedBack,
            )
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
        ) {
            // ⚠️ An `if` chain rather than an exhaustive `when`, whose synthetic last arm no test reaches.
            if (state is VoiceStudioUiState.Ready) {
                StudioBody(state, actions)
            } else if (state is VoiceStudioUiState.LoadFailed) {
                ContentContainer {
                    ConfigLoadFailure(failure = state.failure, onRetry = @DontMemoize { actions.load() })
                }
            } else {
                ContentContainer { ConfigSkeleton() }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

@Composable
private fun StudioBody(state: VoiceStudioUiState.Ready, actions: VoiceStudioActions) {
    val enabled = state.save != StudioSaveState.Saving
    Section { SaveBar(state, @DontMemoize { actions.save() }) }
    Section { RecipeSection(state, enabled, actions) }
    Section { ChainSection(state, actions) }
    Section { MeterSection(state) }
    Section { ResidencySection(state) }
    Section { StudioLegEditor(state, enabled, actions) }
}

/** One band of the screen: the content width, the gutter, and tight spacing between its rows. */
@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    ContentContainer(
        contentPadding = PaddingValues(horizontal = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        content = content,
    )
}

/** The description, saved or unsaved, the save button, and what the last save did. */
@Composable
private fun SaveBar(state: VoiceStudioUiState.Ready, onSave: () -> Unit) {
    val labels = state.studio.labels
    Text(labels.description, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.foreground)
    Text(
        text = if (state.dirty) labels.unsaved else labels.allSaved,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_DIRTY_DESCRIPTION },
    )
    StudioSaveNotice(state.save, labels.saved, labels.saveFailed)
    DistrictButton(
        text = if (state.save == StudioSaveState.Saving) {
            stringResource(R.string.workspace_settings_saving)
        } else {
            labels.save
        },
        onClick = onSave,
        enabled = state.canSave,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = VOICE_STUDIO_SAVE_DESCRIPTION },
    )
}

@Composable
private fun StudioSaveNotice(save: StudioSaveState, saved: String, saveFailed: String) {
    // ⚠️ An `if` chain rather than an exhaustive `when`, whose synthetic last arm no test reaches.
    val (text, tone) = if (save == StudioSaveState.Saved) {
        saved to DistrictTheme.colors.foreground
    } else if (save == StudioSaveState.Mismatch) {
        saveFailed to DistrictTheme.colors.destructive
    } else if (save is StudioSaveState.SavedButStale) {
        stringResource(R.string.workspace_settings_saved_stale) to DistrictTheme.colors.mutedForeground
    } else if (save is StudioSaveState.Failed) {
        "$saveFailed ${save.failure.message.resolve()}" to DistrictTheme.colors.destructive
    } else {
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tone,
        modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_NOTICE_DESCRIPTION },
    )
}
