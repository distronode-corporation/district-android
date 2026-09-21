plugins {
    id("district.android.library")
    id("district.android.compose")
}

android {
    // ⚠️ Its own namespace — AGP 9's android.uniquePackageNames is on, so sharing the
    // app's would fail the build. See core-model.
    namespace = "com.distronode.districtai.core.designsystem"
}

// ⛔ THIS MODULE DEPENDS ON NOTHING IN THE PROJECT, DELIBERATELY. It is the bottom of the
// UI stack: colours, type, shape, motion and the primitive composables built from them.
// Depending on core-model would let a DTO leak into a component's signature, and the
// moment a primitive takes a `Call` it stops being reusable and starts being a screen.
// Every primitive here takes Strings, enums and lambdas.
dependencies {
    // Layout/foundation is not pulled in by district.android.compose (which brings ui,
    // ui-graphics, tooling-preview and material3), and every primitive here needs
    // Column/Row/padding/clickable.
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // See the long note in app/build.gradle.kts: createComposeRule() needs this
    // artifact's MANIFEST merged into the variant under test, which only
    // debugImplementation does.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
