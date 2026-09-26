// Settings shared by EVERY Android module in this project, application or library.
//
// Applied by district.android.application and district.android.library rather than
// directly by modules. If you find yourself repeating a block in both of those, it
// belongs here instead.
//
// ⚠️ AGP 9 NOTES, both of which broke this file once already:
//  1. `CommonExtension` LOST ITS TYPE PARAMETERS. The AGP 8 spelling
//     `CommonExtension<*, *, *, *, *, *>` does not compile against AGP 9.
//  2. The nested BLOCK METHODS moved off CommonExtension onto the concrete
//     extensions, so `defaultConfig { ... }` is unavailable here and it has to be
//     `defaultConfig.apply { ... }`. This is the documented AGP 8.13 -> 9.0 change.
//
// ⚠️ Catalog lookups go through the helpers in CatalogAccess.kt, not the generated
// `libs.` accessor — see that file for why.

import com.android.build.api.dsl.CommonExtension

plugins {
    // ── Coverage ─────────────────────────────────────────────────────────────
    // ⛔ APPLIED HERE, NOT PER MODULE, SO A NEW MODULE CANNOT BE BORN UNMEASURED.
    // Every Android module in this project routes through this convention, so adding
    // one gets coverage automatically. The previous state was worse than a low floor:
    // there was no coverage tooling at all, and the ONLY assertion in CI was that the
    // number of executed tests was not literally zero — so deleting 29 of the 30 test
    // files left the pipeline green.
    //
    // ⚠️ Kover measures nothing on its own. The floor lives in the ROOT build file,
    // which is the merging module that aggregates every module's counters into one
    // number; a per-module rule would let a well-tested module hide a bare one.
    id("org.jetbrains.kotlinx.kover")
}

extensions.configure<CommonExtension> {
    compileSdk = libs.intVersion("compileSdk")

    defaultConfig.apply {
        minSdk = libs.intVersion("minSdk")
    }

    compileOptions.apply {
        // Java 11 is AGP 9's own default; stated explicitly so a future AGP default
        // change is a visible diff here rather than a silent behaviour shift.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Required for java.time and friends on minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }

    // ⚠️ NO explicit `src/<variant>/kotlin` registration. AGP 9's built-in Kotlin
    // already registers that directory, and the old
    // `sourceSets.configureEach { java.srcDirs("src/$name/kotlin") }` line is both
    // redundant and now deprecated (`srcDirs` -> `directories`). Verified by
    // building :app, whose sources live only under src/main/kotlin.

    testOptions.unitTests.apply {
        // ⛔ LOAD-BEARING FOR ROBOLECTRIC. Without Android resources on the
        // unit-test classpath every Robolectric test fails while inflating a theme,
        // which reads as a broken test rather than a missing flag.
        isIncludeAndroidResources = true
        isReturnDefaultValues = true

        // ⛔ ALSO LOAD-BEARING FOR ROBOLECTRIC, SINCE 4.17. Its SDK 36 sandbox sets up
        // ApplicationSharedMemory through jdk.internal.access.SharedSecrets, which JDK 17+
        // does not open to the classpath. Without this flag EVERY Robolectric test fails
        // before its body runs, with "Failed to interact with raw FileDescriptor internals;
        // perhaps JRE has changed?" (cause: IllegalAccessException, "module java.base does
        // not export jdk.internal.access to unnamed module"). Measured on 4.17 under JDK 21.
        // Robolectric's getting-started page lists more --add-opens flags; only this one is
        // needed by the tests here, so it is the only one set. Add others when a test
        // actually needs them, not in advance.
        all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    // ── Android Lint ─────────────────────────────────────────────────────────
    // ⛔ DETEKT DOES NOT COVER ANY OF THIS. detekt (with ktlint's rules) reads Kotlin
    // source and knows nothing about the manifest, resources, or the SDK. With
    // minSdk 26 against compileSdk 37 there are eleven API levels of surface that
    // only Lint checks, and `NewApi` — calling an API newer than minSdk, which
    // crashes on a real phone and compiles perfectly — was checked by nothing at all.
    //
    // ⚠️ `lintVitalRelease` already ran as part of bundleRelease, which is why this
    // was easy to believe covered. It is not: lintVital runs only the subset of
    // checks marked fatal, on the release variant. `NewApi` findings in a debug-only
    // path, and every manifest/resource check, are outside it.
    //
    // ⚠️ `lint.apply { }`, NOT `lint { }` — the same AGP 9 change described in this
    // file's header. The block METHOD moved off CommonExtension onto the concrete
    // application/library extensions, so the function form does not resolve here and
    // fails with "None of the following candidates is applicable" pointing at the brace
    // rather than at the cause.
    lint.apply {
        // Errors fail the build. This is the default for `lintDebug`, stated
        // explicitly so a future AGP default change is a visible diff here.
        abortOnError = true

        // ⚠️ DELIBERATELY NOT `warningsAsErrors = true`. 23 warnings exist today,
        // most of them `UnusedResources` for strings whose screens are half-built and
        // `NewerVersionAvailable` for versions this repo pins ON PURPOSE (see the long
        // notes in libs.versions.toml — bumping Kotlin independently of AGP is a hard
        // error there). Promoting those to errors would make the gate impossible to
        // satisfy honestly and the first response would be to disable it.
        warningsAsErrors = false

        // ⛔ NO `baseline = file("lint-baseline.xml")`. A baseline file makes every
        // current finding invisible and, worse, silently absorbs NEW findings in any
        // file it already covers. If a finding is not worth fixing it should be
        // visible as a warning or disabled BY NAME with a reason, as below.
        //
        // ── No suppressions, and that is worth stating ───────────────────────
        // ⛔ NOTHING IS DEMOTED OR DISABLED HERE, DELIBERATELY. This block briefly
        // carried `warning += "LocalContextGetResourceValueCall"` — one occurrence, at
        // DistrictNavHost.kt:197, where a snackbar message was read through
        // `context.getString(...)` instead of `stringResource(...)`, so it did not
        // re-resolve across a Configuration change. It was demoted only because the
        // branch adding this gate did not own `app/src/**`.
        //
        // That finding is now FIXED at the source rather than suppressed: the message
        // became a `UiText`, which has no `Context` to read from, so the call site the
        // rule objected to no longer exists. `lintDebug` is therefore clean at zero
        // errors with an empty suppression list — which is the state that makes the
        // NEXT finding impossible to ignore.
        //
        // ⚠️ Keep this list empty. If a finding genuinely cannot be fixed, disable it
        // BY NAME with the reason written here, never with a baseline file.

        // Text report to stdout so a CI failure is readable in the job log without
        // downloading an artifact.
        textReport = true
        // HTML/XML are retained as CI artifacts; see .github/workflows/ci.yml.
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    add("coreLibraryDesugaring", libs.library("desugar-jdk-libs"))
}

// Coverage engine and the report variant the root gate reads. Both must match the root build
// file: see "WHY JACOCO" and "THE GATE IS THE `unit` VARIANT" there.
kover {
    useJacoco("0.8.15")
    currentProject {
        // Debug only. The release compilation has the same class names and different bytes, and
        // JaCoCo matches execution data by those bytes, so a report over both credits nothing
        // to the classes the tests actually loaded.
        createVariant("unit") {
            add("debug")
        }
    }
}
