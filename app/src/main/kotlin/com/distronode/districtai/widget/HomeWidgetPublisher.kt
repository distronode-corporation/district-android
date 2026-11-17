package com.distronode.districtai.widget

/**
 * The one writer of the home-screen widget's snapshot (the iOS app's `WidgetSnapshotPublisher`).
 *
 * ⛔ IT WRITES ONLY WHAT THE APP HAS ALREADY READ. The widget makes no request and holds no session:
 * the workspace and its 7-day call count come from the Overview's reads, the unread count from the
 * Inbox list's. Nothing here calls the network.
 *
 * ⛔ COUNTS BELONG TO A WORKSPACE ID. [WorkspaceRead.show] for a different id (even one with the same
 * name) drops the counts, and a count for any workspace but the one on the widget is dropped, so a
 * count that answers after a switch or a sign-out can never be drawn under the wrong name.
 *
 * ⛔ A WORKSPACE THAT ANSWERS AFTER A SIGN-OUT WRITES NOTHING. A caller takes a [workspaceRead]
 * BEFORE its request; [clear] advances the sign-out count, and a read taken before it writes
 * nothing. A sign-out while the workspace list was in flight would otherwise put the previous
 * account's workspace back on the home screen.
 *
 * ⚠️ MAIN-THREAD CONFINED, like every caller (the ViewModels and the container's app scope), so the
 * read-compare-write below is not raced.
 *
 * ⛔ NO COUNT FROM A SAVED COPY. Offline, the Overview and the Inbox are answered from their last
 * saved copy (works offline, read-only) as if live; recording that count would stamp an old number
 * with now and keep it on the home screen as current. While [offline] says a saved copy is on
 * screen, counts are dropped and the widget keeps what it last read live.
 *
 * @param redraw ask the widget to draw the current snapshot. Called only when the snapshot changed.
 * @param clock now, in epoch milliseconds, for when a call count was read.
 * @param offline whether the app is showing saved copies (`SavedReads.showingSince` is set).
 */
class HomeWidgetPublisher(
    private val store: HomeWidgetStore,
    private val redraw: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val offline: () -> Boolean = { false },
) {

    /** The sign-out count of this process; see the class note. */
    private var generation: Int = 0

    /** Where a workspace list that is about to be asked for writes its selected workspace. */
    fun interface WorkspaceRead {
        /** The selected workspace, from a complete or degraded list that still selected one. */
        fun show(workspaceId: String, workspaceName: String)
    }

    /** Take this BEFORE the request; see the class note. */
    fun workspaceRead(): WorkspaceRead {
        val askedAt = generation
        return WorkspaceRead { id, name -> showWorkspace(id, name, askedAt) }
    }

    private fun showWorkspace(workspaceId: String, workspaceName: String, askedAt: Int) {
        if (askedAt != generation) return
        val current = store.read()
        val next = if (current?.workspaceId == workspaceId) {
            // A rename keeps the counts; they are still this workspace's.
            current.copy(workspaceName = workspaceName)
        } else {
            HomeWidgetSnapshot(workspaceId = workspaceId, workspaceName = workspaceName)
        }
        save(current, next)
    }

    /** The Overview's 7-day call count for [workspaceId]. Dropped unless that workspace is shown. */
    fun recordCalls(workspaceId: String, callsLast7Days: Int) {
        if (offline()) return
        val current = store.read()?.takeIf { it.workspaceId == workspaceId } ?: return
        save(
            current,
            current.copy(callsLast7Days = callsLast7Days.coerceAtLeast(0), callsReadAtMillis = clock()),
        )
    }

    /** The Inbox list's unread total for [workspaceId]. Dropped unless that workspace is shown. */
    fun recordUnread(workspaceId: String, unread: Int) {
        if (offline()) return
        val current = store.read()?.takeIf { it.workspaceId == workspaceId } ?: return
        save(current, current.copy(unread = unread.coerceAtLeast(0)))
    }

    /**
     * Every sign-out path (the button, a deleted account, a session the server ended) and a complete
     * list with nothing to select: the widget falls back to its signed-out face.
     */
    fun clear() {
        generation += 1
        if (store.read() == null) return
        store.clear()
        redraw()
    }

    private fun save(current: HomeWidgetSnapshot?, next: HomeWidgetSnapshot) {
        // ⚠️ The read time alone moving is still a change: it restarts the count's day.
        if (current == next) return
        store.write(next)
        redraw()
    }
}
