package com.distronode.districtai.core.data

/**
 * In-memory [WorkspaceSelectionStore]. The production one is SharedPreferences-backed.
 *
 * ⚠️ THE API FAKES ARE NOT HERE. `FakeDistrictApi` and its siblings come from core-network's test
 * fixtures (`com.distronode.districtai.core.network.testing`), shared with the app's tests; this
 * store stays because its interface lives in this module.
 */
internal class FakeSelectionStore(private var selected: String? = null) : WorkspaceSelectionStore {
    override fun selectedWorkspaceId(): String? = selected

    override fun setSelectedWorkspaceId(workspaceId: String?) {
        selected = workspaceId
    }
}
