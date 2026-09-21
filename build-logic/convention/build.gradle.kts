plugins {
    `kotlin-dsl`
}

group = "com.distronode.districtai.buildlogic"

kotlin {
    // ⚠️ Must match the JVM target the Gradle daemon runs on, not the app's
    // Android target. The daemon here is JDK 21; precompiled script plugins are
    // compiled by that daemon and loaded into it.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // compileOnly, not implementation: these plugins are on the main build's
    // classpath at execution time. Bundling them here would put two copies of
    // AGP on one classloader.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    // Coverage. district.android.base applies Kover so every Android module is measured;
    // a precompiled script plugin needs the plugin's implementation on its compile
    // classpath to do that, hence this entry rather than a `plugins {}` version.
    compileOnly(libs.kover.gradlePlugin)
}

// ── bouncycastle, forced here too ────────────────────────────────────────────
//
// ⛔ THIS IS A SEPARATE BUILD, SO THE FORCES IN THE MAIN build.gradle.kts DO NOT REACH IT.
// compileOnly(AGP) above keeps AGP out of this build's runtime, but kotlin-dsl resolves AGP's
// runtime dependencies into `precompiledScriptPluginAccessorsGenerationClasspath` to generate
// the plugin accessors, and AGP 9.4.1 brings bouncycastle 1.80.2 (bcprov, bcpkix, bcutil)
// with it, below the 1.85 that clears GHSA-9pwp-9qqc-pr26 (critical), GHSA-qp49-qgx5-5m26
// (high), GHSA-c3fc-8qff-9hwx and GHSA-wg6q-6289-32hp.
//
// It matters because it is REPORTED: .github/workflows/dependency-submission.yml sends every
// configuration this included build resolves to GitHub's dependency graph as well, so an
// unforced 1.80.2 here is a Dependabot alert on the public repository.
//
// ⚠️ Every configuration, not just that one, so a configuration kotlin-dsl adds later is
// covered without an edit. Same set and same number as the main build: `bouncyCastle` in
// gradle/libs.versions.toml, which this build reads too (see settings.gradle.kts).
val bouncyCastleVersion: String = libs.versions.bouncyCastle.get()

configurations.configureEach {
    resolutionStrategy {
        listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach {
            force("org.bouncycastle:$it:$bouncyCastleVersion")
        }
    }
}
