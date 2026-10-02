package com.distronode.districtai.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Apply a write's completion to the state as it is NOW, and only while it is still a [type].
 *
 * ⛔ NEVER TO THE SNAPSHOT TAKEN WHEN THE WRITE WAS TAPPED. Two writes on one screen can overlap (a
 * reply in flight while a status chip is tapped, a logo upload during a save), and a completion
 * that restored its own tap-time snapshot would put back the other write's in-flight flag and drop
 * whatever that write had already landed: a spinner that never stops, or a delivered reply missing
 * from the thread. [block] should therefore change only the fields its own write owns.
 *
 * ⚠️ A LATEST VALUE OF ANOTHER TYPE DROPS THE UPDATE. That means a re-read has put the screen back
 * to Loading, and the re-read's answer is the one that should land.
 *
 * ⚠️ A [Class] RATHER THAN A REIFIED INLINE, so this is one function with one branch rather than a
 * copy of that branch at every call site for the coverage gate to count.
 */
internal fun <S : Any, C : S> MutableStateFlow<S>.updateLatest(type: Class<C>, block: (C) -> S) {
    val latest = value
    if (type.isInstance(latest)) value = block(type.cast(latest)!!)
}
