// Compose wiring, applied on top of district.android.application or
// district.android.library by any module with UI.

import com.android.build.api.dsl.CommonExtension

plugins {
    // Since Kotlin 2.0 the Compose compiler is a Kotlin compiler plugin rather than
    // a separate androidx artifact. It must match the Kotlin compiler actually in
    // use — which, under AGP 9's built-in Kotlin, is the KGP that AGP pins. This is
    // why libs.versions.composeCompiler must equal libs.versions.kotlin, and why
    // both are tied to the AGP version.
    id("org.jetbrains.kotlin.plugin.compose")
}

// See the AGP 9 notes in district.android.base: no type parameters, and nested
// blocks are reached with `.apply`.
extensions.configure<CommonExtension> {
    buildFeatures.compose = true
}

dependencies {
    // The BOM is applied to the test and debug configurations too, not just
    // implementation. A test artifact resolving a different Compose version than the
    // code under test produces link errors that read as API misuse.
    val bom = libs.library("androidx-compose-bom")
    add("implementation", platform(bom))
    add("androidTestImplementation", platform(bom))
    add("testImplementation", platform(bom))

    add("implementation", libs.library("androidx-compose-ui"))
    add("implementation", libs.library("androidx-compose-ui-graphics"))
    add("implementation", libs.library("androidx-compose-ui-tooling-preview"))
    add("implementation", libs.library("androidx-compose-material3"))

    add("debugImplementation", libs.library("androidx-compose-ui-tooling"))
}
