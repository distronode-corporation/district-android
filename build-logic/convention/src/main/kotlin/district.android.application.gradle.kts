// Convention for the single application module.
//
// ⛔ NO `org.jetbrains.kotlin.android` HERE. AGP 9 compiles Kotlin itself; applying
// that plugin is a hard error. See the [plugins] note in gradle/libs.versions.toml.

plugins {
    id("com.android.application")
    id("district.android.base")
}

android {
    defaultConfig {
        // Explicit even though AGP 9 now defaults targetSdk to compileSdk
        // (android.sdk.defaultTargetSdkToCompileSdkIfUnset flipped to true). The
        // Play submission requirement is on targetSdk specifically, so it is not
        // something to leave implied.
        targetSdk = libs.intVersion("targetSdk")
    }

    // ── Release signing (upload key) ─────────────────────────────────────────
    // Credentials come from the environment of whoever builds the release. Releases
    // are built and signed on a maintainer's machine, never in CI, so no workflow in
    // this repository holds them. Nothing key-shaped is ever committed: the
    // repository's .gitignore excludes *.p12, *.jks, *.keystore and *.pepk.
    //
    // Under Play App Signing, Google holds the real app signing key and this is only
    // the UPLOAD key, recoverable through Play support if it is ever lost.
    //
    // ⚠️ ABSENCE IS A SUPPORTED STATE, NOT AN ERROR. CI runs
    // assembleRelease through verify-release-minification.sh with no credentials at
    // all, and it must keep working — that gate only needs R8 output, not a signature.
    //
    // ⛔ BUT A PARTIAL SET IS AN ERROR. Left to itself, one missing variable produces
    // a silently unsigned AAB that Play rejects much later with a message about the
    // artifact rather than about this configuration. Same principle as the rest of
    // this repo: never let a misconfiguration read as an absence of data.
    val uploadStore = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_PATH")
    val uploadPass = providers.environmentVariable("ANDROID_UPLOAD_KEYSTORE_PASSWORD")
    val uploadAlias = providers.environmentVariable("ANDROID_UPLOAD_KEY_ALIAS")
    val signingVars = mapOf(
        "ANDROID_UPLOAD_KEYSTORE_PATH" to uploadStore,
        "ANDROID_UPLOAD_KEYSTORE_PASSWORD" to uploadPass,
        "ANDROID_UPLOAD_KEY_ALIAS" to uploadAlias,
    )
    val supplied = signingVars.filterValues { it.isPresent }
    require(supplied.isEmpty() || supplied.size == signingVars.size) {
        "Release signing is half-configured. Present: ${supplied.keys.sorted()}. " +
            "Missing: ${(signingVars.keys - supplied.keys).sorted()}. " +
            "Set all three or none — none yields an unsigned release build, which is " +
            "what the minification gate wants, but a partial set would silently ship " +
            "an unsigned AAB that Play rejects."
    }
    val signRelease = supplied.size == signingVars.size
    if (signRelease) {
        signingConfigs.create("upload") {
            storeFile = file(uploadStore.get())
            // ⚠️ PKCS12 keeps ONE password for the store and the key; keytool cannot
            // set them separately for this format, so both read the same secret.
            storeType = "PKCS12"
            storePassword = uploadPass.get()
            keyAlias = uploadAlias.get()
            keyPassword = uploadPass.get()
        }
    }

    buildTypes {
        release {
            // Null when unconfigured, which leaves the release build unsigned on
            // purpose — see the block above.
            signingConfig = if (signRelease) signingConfigs.getByName("upload") else null
            // R8 on, verified against a real release artifact.
            isMinifyEnabled = true
            isShrinkResources = true
            // ⚠️ AGP 9 only accepts proguard-android-optimize.txt from
            // getDefaultProguardFile(); the plain proguard-android.txt is rejected
            // because it silently carries -dontoptimize.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // Distinct package so a debug build can coexist with a Play install.
            // ⚠️ This changes the applicationId, so App Link verification against
            // /.well-known/assetlinks.json covers the RELEASE package only.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // Off by default since AGP 8. Enabled because the API base URL is surfaced
        // through BuildConfig.
        buildConfig = true
    }

    packaging {
        resources {
            // Duplicate license metadata from transitive artifacts is the most
            // common cause of a packaging failure that reads like a code bug.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}
