package com.distronode.districtai.applinks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A verified App Link the OS handed us, waiting for a graph that can act on it.
 *
 * ⛔ THE SAME HOLDER SHAPE AS `PushDeepLinks`, AND FOR THE SAME REASON: THE INTENT ARRIVES BEFORE
 * ANYTHING CAN NAVIGATE. `MainActivity` receives the `ACTION_VIEW` in `onCreate`/`onNewIntent`, at
 * which point the navigation graph may not be composed and — the binding constraint — the active
 * workspace is unknown, because it comes from `workspace/list` + `overview` which are still in
 * flight. So the activity records the section and the graph consumes it when it can.
 *
 * ⛔ AND PROCESS-SCOPED, NOT ACTIVITY-SCOPED. A cold start from a link is exactly the case where a
 * rotation mid-load would destroy an activity field, losing the link on the only path it exists for.
 *
 * ⚠️ A SEPARATE HOLDER FROM `PushDeepLinks` RATHER THAN A SHARED ONE, DELIBERATELY. The two carry
 * different things — a push names a WORKSPACE (and is dropped when it is not the active one), a link
 * names a SECTION of whatever workspace is active and has no tenant of its own. Merging them would
 * mean one nullable field per source and a decision function that has to ask which arrived; keeping
 * them apart means each decision stays a two-line `when`. They cannot race meaningfully: both are
 * "last one wins", and the user can only have tapped one thing.
 *
 * ⚠️ ONE PENDING LINK AT A TIME, LAST ONE WINS — a second link is the one the user is looking at,
 * and honouring the first afterwards would navigate away from what they just asked for.
 */
class AppLinkDeepLinks {

    private val _pending = MutableStateFlow<DistrictSection?>(null)

    /** ⚠️ Null when there is nothing outstanding, which is almost always. */
    val pending: StateFlow<DistrictSection?> = _pending.asStateFlow()

    fun offer(section: DistrictSection) {
        _pending.value = section
    }

    /**
     * ⛔ CALLED ON EVERY RESOLUTION, INCLUDING ONES THAT NAVIGATE NOWHERE. A pending link that
     * survived its resolution would re-fire the moment the workspace finished loading for the user's
     * own reasons and yank them somewhere they did not ask to go.
     */
    fun clear() {
        _pending.value = null
    }
}
