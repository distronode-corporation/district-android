package com.distronode.districtai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Run [onSessionChanged] when the session epoch advances past the value this composable saw when
 * it first ran.
 *
 * ⛔ THE "PAST THE VALUE IT FIRST SAW" PART IS THE WHOLE DESIGN. A bare
 * `LaunchedEffect(epoch) { reload() }` also fires on first composition, which would reload every
 * screen immediately after its own `init { load() }` — reintroducing, per screen, exactly the
 * doubled cold-start traffic that hoisting the overview ViewModel removed. `overview` fans out
 * across up to four regional databases, so a spurious extra call is the most expensive mistake
 * available here. Comparing against the epoch observed at entry means only a genuine advance —
 * a login or a sign-out that happened while this screen existed — triggers anything.
 *
 * ⚠️ `remember`, NOT `rememberSaveable`, and that is correct. A configuration change is not a
 * session change: after a rotation the baseline is re-captured from the current epoch and nothing
 * fires, which is what should happen. Persisting the baseline across process death would compare
 * against an epoch from a previous process, where the counter started at zero again.
 *
 * ⚠️ Fires for sign-out as well as sign-in. Both mean "the identity behind this data changed,
 * re-read"; a screen that reloads into a `SignedOut` state is how the sign-in gate gets told.
 */
@Composable
fun OnSessionChanged(epoch: Int, onSessionChanged: () -> Unit) {
    val baseline = remember { epoch }
    LaunchedEffect(epoch) {
        if (epoch != baseline) onSessionChanged()
    }
}
