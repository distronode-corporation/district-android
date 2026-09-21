// Convention for library modules (core-*, feature-*, telecom, push).
//
// It exists because the build has many library modules, and the alternative is one
// copy of the same block per module, drifting apart.
//
// ⛔ NO `org.jetbrains.kotlin.android` HERE either. See libs.versions.toml.

plugins {
    id("com.android.library")
    id("district.android.base")
}

// ⚠️ Library modules must NOT set targetSdk — AGP removed it from the library DSL
// because it never did anything there; the application module's value ships.
//
// ⚠️ AGP 9 flipped android.uniquePackageNames to true, so every library module needs its
// own distinct `namespace`. Set it in the module, not here.
//
// ⛔ NO `consumerProguardFiles("consumer-rules.pro")` HERE, DELIBERATELY. AGP 9 flipped
// `android.proguard.failOnMissingFiles` to true: a keep file named in the DSL that does
// not exist on disk now FAILS THE BUILD (previously it was silently ignored). Declaring
// it in the convention therefore breaks every new library module until someone creates an
// empty placeholder — measured here, `:core:core-model:mergeDebugConsumerProguardFiles`
// failed on exactly this the first time a library module existed.
//
// A module that genuinely ships consumer keep rules should create its own
// consumer-rules.pro and declare it itself. Most will not need one.
//
// ⚠️ Related AGP 9 change, relevant when rules ARE added:
// `android.r8.globalOptionsInConsumerRules.disallowed` is now true, so a consumer rules
// file containing a global option like -dontoptimize fails library publishing. Those
// belong in the app module's proguardFiles instead.
