package com.distronode.districtai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.SkeletonBlock

/**
 * A paged list's non-list states: loading, empty and the append spinner. A failed refresh is a
 * [FailureState] and a failed append a [PagedAppendFailure].
 *
 * ⚠️ ONE SET FOR EVERY PAGED LIST. The call log and the contacts list each carried a full copy of
 * these, differing only in their test handles and the empty state's words, which are parameters
 * here.
 */

/**
 * ⚠️ SKELETON ROWS, NOT A CENTRED SPINNER. The list that is arriving has a known shape (avatar, two
 * lines, trailing badge), so drawing that shape says what is loading and stops the layout jumping
 * when it lands. A spinner in the middle of a blank screen conveys only "wait".
 */
@Composable
fun PagedListLoading(description: String) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_ROW_HEIGHT) }
        }
    }
}

/**
 * ⚠️ ONLY REACHABLE ONCE REFRESH HAS SUCCEEDED, so this genuinely means "there is nothing" rather
 * than "we could not load it". That ordering, in the caller's `when`, is what makes an [EmptyState]
 * honest here.
 */
@Composable
fun PagedListEmpty(
    description: String,
    title: String,
    body: String,
    action: @Composable (() -> Unit)? = null,
) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description },
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(title = title, body = body, action = action)
        }
    }
}

/**
 * ⚠️ A SPINNER IS CORRECT HERE, unlike the full-screen case: the footer is a small strip below rows
 * the user is already reading, so there is no shape to stand in for and nothing to stop jumping.
 */
@Composable
fun PagedAppendLoading(description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = DistrictTheme.colors.district,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

private const val SKELETON_ROWS = 8
private val SKELETON_ROW_HEIGHT = 56.dp
