package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.model.HqTurn

/** What the voice bar's controls do. */
data class HqVoiceActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onNoticeStart: () -> Unit = {},
    val onNoticeCancel: () -> Unit = {},
    val onHold: (Boolean) -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onConfirm: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
)

/**
 * The bar's controls, each one a call on [this] session.
 *
 * ⚠️ A PLAIN FUNCTION, so the nine lambdas are ordinary closures over the session; called from the
 * host on each recomposition, where building them is cheap and the bar only reads them on a tap.
 */
fun HqVoiceSession.actions(
    history: () -> List<HqTurn>,
    locale: String,
    onOpenSettings: () -> Unit,
): HqVoiceActions = HqVoiceActions(
    onStart = { start(history(), locale) },
    onStop = { stop() },
    onOpenSettings = onOpenSettings,
    onNoticeStart = { begin() },
    onNoticeCancel = { cancelNotice() },
    onHold = { holdToTalk(it) },
    onToggleMute = { toggleMute() },
    onConfirm = { confirm(it) },
    onCancel = { cancel(it) },
)
