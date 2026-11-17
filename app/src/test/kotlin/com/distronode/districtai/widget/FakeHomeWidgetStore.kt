package com.distronode.districtai.widget

/** An in-memory [HomeWidgetStore] that counts its writes. */
class FakeHomeWidgetStore(var snapshot: HomeWidgetSnapshot? = null) : HomeWidgetStore {
    var writes = 0
        private set

    override fun read(): HomeWidgetSnapshot? = snapshot

    override fun write(snapshot: HomeWidgetSnapshot) {
        writes += 1
        this.snapshot = snapshot
    }

    override fun clear() {
        writes += 1
        snapshot = null
    }
}

/** A publisher over [store] whose redraws are counted in [redraws] and whose clock is [now]. */
class PublisherHarness(var now: Long = 1_000L, var offline: Boolean = false) {
    val store = FakeHomeWidgetStore()
    var redraws = 0
        private set
    val publisher = HomeWidgetPublisher(
        store = store,
        redraw = { redraws += 1 },
        clock = { now },
        offline = { offline },
    )
}
