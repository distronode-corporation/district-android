package com.distronode.districtai.ui.dialer

/** [DialerCountryMemory] in memory, keyed like the real one, so a test can read what was kept. */
internal class FakeDialerCountryMemory(
    val homes: MutableMap<String, String> = mutableMapOf(),
    val picks: MutableMap<String, String> = mutableMapOf(),
) : DialerCountryMemory {
    override fun home(workspaceId: String): String? = homes[workspaceId]

    override fun rememberHome(iso: String, workspaceId: String) {
        homes[workspaceId] = iso
    }

    override fun picked(workspaceId: String): String? = picks[workspaceId]

    override fun rememberPicked(iso: String, workspaceId: String) {
        picks[workspaceId] = iso
    }
}
