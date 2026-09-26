plugins {
    id("district.android.library")
    // ⛔ COMPOSE HERE, NOT ONLY IN `:app`, BECAUSE ONE COMPOSABLE HAS TO LIVE BESIDE THE SDK.
    // `VideoTile` is the only place a `VideoTrackHandle` may be opened — rendering a frame means
    // holding the SDK's track and its renderer view, and that is the single capability the
    // CallEngine seam cannot express as data. Keeping the tile here is what stops that one
    // exception from putting `io.livekit` on `:app`'s compile classpath. Nothing else in this
    // module is UI, and nothing else should become UI.
    id("district.android.compose")
}

android {
    namespace = "com.distronode.districtai.core.media"
}

dependencies {
    // `implementation`, NOT `api`, and that is the module's whole reason to exist:
    // CallEngine's surface leaks no LiveKit type, so the SDK — its WebRTC natives,
    // its Dagger graph, its JitPack-hosted audioswitch fork — stays behind this one
    // module boundary, one ProGuard surface, one lockfile. A consumer that needs a
    // LiveKit type is a consumer that should be asking CallEngine for a capability
    // instead.
    implementation(libs.livekit.android)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric and the Compose test rule for `VideoTile`, as in `:app`. The manifest artifact is
    // debugImplementation for the same reason `:app` gives: it contributes the test activity's
    // manifest entry, which a testImplementation dependency never merges.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // MockK, for this module only: a real `Room` cannot be built off a device (`LiveKit.create`
    // loads libwebrtc's natives and fails with UnsatisfiedLinkError), and the SDK's classes are
    // final, so the engine's calls into the SDK are asserted against mocks.
    testImplementation(libs.mockk)
}
