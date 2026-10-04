package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.VoiceStudioRecipe

/** Stable or Latest, the tiles for that tier, and "Based on X, N changes" with Reset. */
@Composable
internal fun RecipeSection(state: VoiceStudioUiState.Ready, enabled: Boolean, actions: VoiceStudioActions) {
    val labels = state.studio.labels
    Eyebrow(labels.tierLabel)
    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        listOf(StudioRecipes.STABLE to labels.tierStable, StudioRecipes.LATEST to labels.tierLatest)
            .forEach { (tier, label) ->
                DistrictButton(
                    text = label,
                    onClick = @DontMemoize { actions.selectTier(tier) },
                    enabled = enabled,
                    size = ButtonSize.Sm,
                    variant = if (tier == state.tier) ButtonVariant.Primary else ButtonVariant.Secondary,
                    modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_TIER, tier) },
                )
            }
    }
    Caption(labels.tierDescription)
    Eyebrow(labels.recipesLabel)
    state.tiles.forEach { recipe ->
        RecipeTile(recipe, selected = recipe.id == state.baseRecipe, labels.defaultBadge, enabled) @DontMemoize {
            actions.applyRecipe(recipe.id)
        }
    }
    if (state.changes > 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.voice_studio_based_on,
                    state.changes,
                    state.baseName,
                    state.changes,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_BASED_ON_DESCRIPTION },
            )
            DistrictButton(
                text = labels.reset,
                onClick = @DontMemoize { actions.reset() },
                enabled = enabled,
                size = ButtonSize.Sm,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_RESET_DESCRIPTION },
            )
        }
    }
}

/**
 * One recipe: its name, the Default badge, what it is, its channel, its time to first word and
 * where the call is processed, all in the server's words.
 */
@Composable
private fun RecipeTile(
    recipe: VoiceStudioRecipe,
    selected: Boolean,
    defaultBadge: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DistrictCard(
        onClick = onClick.takeIf { enabled },
        modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_RECIPE, recipe.id, selected) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            Text(recipe.name, style = MaterialTheme.typography.titleSmall, color = DistrictTheme.colors.foreground)
            if (recipe.isDefault) DistrictBadge(defaultBadge)
            if (selected) DistrictBadge(stringResource(R.string.voice_studio_selected), tone = Tone.District)
        }
        Caption(recipe.description)
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictBadge(recipe.channelLabel, tone = channelTone(recipe.channel))
            Caption(recipe.timeToFirstWord.text)
        }
        Text(
            text = recipe.residency.text,
            style = MaterialTheme.typography.bodySmall,
            color = residencyColor(recipe.residency.inRegion),
        )
        recipe.note?.let { Caption(it) }
    }
}
