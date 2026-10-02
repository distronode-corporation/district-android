plugins {
    id("district.android.application")
    id("district.android.compose")
    // ⛔ THE VERSION LIVES IN THE ROOT build.gradle.kts (`apply false`), NOT HERE. See the
    // note there, and the ⚠️ on the `sentry-android` plugin alias in
    // gradle/libs.versions.toml for why this is applied per-module rather than by a
    // convention plugin like every other plugin in this build.
    id("io.sentry.android.gradle")
}

// ── versionCode ──────────────────────────────────────────────────────────────
//
// ⛔ PLAY REFUSES A versionCode IT HAS ALREADY SEEN, AND IT CAN NEVER GO DOWN. So the
// number cannot be a constant that someone remembers to bump: the second upload from a
// static `1` fails at upload time with a message about the artifact, which is the
// expensive place to learn it.
//
// THE SCHEME: `BUILD_NUMBER_OFFSET + git rev-list --count HEAD`. The commit count is
// monotonic on `main` because a count only ever grows along a linear history, which is why
// `main` must never be force-pushed. It needs no state stored anywhere, which is the
// property a "read the last code from Play and add one" scheme lacks: that one requires a
// successful read before it can build, so a Play outage becomes a build failure.
//
// ⛔ THE OFFSET EXISTS BECAUSE THIS REPOSITORY'S HISTORY STARTS FRESH. The app shipped
// from an earlier source tree whose commit count was already in the thousands, and Play
// keeps every versionCode it has accepted for this applicationId. A count that restarts
// near 1 would put every new build BELOW the last upload, and Play refuses those. The
// offset lifts the new range clear of every build number already uploaded to either store.
// It is the same default, 4101, as district-ios scripts/archive-imac.sh. It is read from
// the environment (`BUILD_NUMBER_OFFSET`, default 4101) rather than hardcoded so that a
// one-off correction does not need a commit, but the default is the value releases use.
//
// ⚠️ 4101, NOT 4000, BECAUSE THE REPOSITORY IS REPUBLISHED AS A SINGLE COMMIT AND 4101 IS
// ALREADY TAKEN. The republished history starts at a count of 1. With the earlier default the
// previous single-commit tree (squash 605b5ec) built 4101, and that bundle was uploaded to
// Google Play, which never accepts a versionCode twice. With 4101 the single-commit public squash
// builds 4102, and later public commits count up from there, all above the 3670 the earlier
// source tree left on Google Play.
//
// ⚠️ ITS WEAKNESS IS WORTH STATING. A commit count is not the same number as a version:
// two commits produce two versionCodes even if neither touched the app. That is harmless
// (Play only requires monotonicity, not density) but it means the number is not a
// meaningful build ordinal and should not be shown to a user. `versionName` is what users
// see, and it is set by hand.
//
// ⚠️ A SHALLOW CLONE ANSWERS A DEPTH, NOT A COUNT, AND NOTHING HERE CAN TELL. CI checks out
// one commit, so a CI build's versionCode is `offset + 1`. That is fine for a verify-only
// build and wrong for a release, which is why releases are built from a full clone. A
// checkout where git cannot answer at all (no `.git`, or a repository with no commits yet)
// counts as 0 and builds `offset` itself, below any real release. The result is never
// below 1, the lowest versionCode Play accepts.
//
// `-PdistrictVersionCode=<n>` overrides the whole calculation, offset included, for the
// rare build that has to carry one exact number.
val districtBuildNumberOffset = providers.environmentVariable("BUILD_NUMBER_OFFSET")
    .map { raw ->
        val offset = raw.trim().toIntOrNull()
        require(offset != null && offset >= 0) {
            "BUILD_NUMBER_OFFSET must be a non-negative integer, got '$raw'. Unset it to use " +
                "the default of 4101."
        }
        offset
    }
    .orElse(4101)

val districtCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 0 }

val districtVersionCode = providers.gradleProperty("districtVersionCode")
    .map(String::toInt)
    .orElse(
        districtBuildNumberOffset.zip(districtCommitCount) { offset, count ->
            (offset + count).coerceAtLeast(1)
        },
    )

// ── Sentry DSN ───────────────────────────────────────────────────────────────
//
// ⚠️ A DSN IS NOT A SECRET, but it IS a deployment's own address, so it is not committed. It is
// the public ingest endpoint every installed copy of the app carries; it grants permission to
// SEND an event and nothing else (no read, no project metadata, no organisation access). What
// it identifies is one particular Sentry project, which is a property of whoever builds and
// ships the app, not of this source tree: a fork's builds must not report into the upstream
// project, and a contributor's debug build should report nowhere.
//
// So it comes from `-PdistrictSentryDsn=<dsn>` or the `DISTRICT_SENTRY_DSN` environment
// variable, in that order, and defaults to EMPTY. Empty is a supported state: DistrictSentry
// returns without initialising the SDK, so the app runs with crash reporting off rather than
// failing to start. The build says so in one line, so an unintended empty DSN on a release
// build is visible in its log rather than discovered when the first crash never arrives.
//
// ⚠️ The DSN's host carries the organisation's data region (an EU-region organisation's DSN
// names `ingest.de.sentry.io`), so copy it whole from the project's settings rather than
// assembling it; the Gradle plugin's `url` below must name the same region.
val districtSentryDsn: String = providers.gradleProperty("districtSentryDsn")
    .orElse(providers.environmentVariable("DISTRICT_SENTRY_DSN"))
    .map(String::trim)
    .getOrElse("")

require(districtSentryDsn.none { it == '"' || it == '\\' }) {
    "districtSentryDsn / DISTRICT_SENTRY_DSN contains a quote or a backslash, which no Sentry " +
        "DSN does and which would break the generated BuildConfig field."
}

// DistrictSentryTest compares the generated BuildConfig field with this value, so the plumbing
// between the two is tested rather than assumed.
tasks.withType<Test>().configureEach {
    systemProperty("district.sentry.dsn", districtSentryDsn)
}

// ⚠️ Printed at configuration time, so with the configuration cache on it appears on the run that
// computes the configuration and not on a run that reuses it.
if (districtSentryDsn.isEmpty()) {
    logger.lifecycle(
        "Sentry disabled: no districtSentryDsn property or DISTRICT_SENTRY_DSN set, so this " +
            "build carries no DSN and reports no crashes.",
    )
}

android {
    namespace = "com.distronode.districtai"

    defaultConfig {
        // ⛔ THIS IS THE PLAY STORE PACKAGE NAME AND IT IS PERMANENT. It cannot be
        // changed after the first upload without publishing a different app, and
        // it is the identity that the server's /.well-known/assetlinks.json binds to.
        applicationId = "com.distronode.districtai"

        versionCode = districtVersionCode.get()
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        all {
            // ⛔ ONE BASE URL, EVERY BUILD TYPE. The four regional origins sit
            // behind the Cloudflare geo-router, which picks the origin, and the
            // website already fans out across region shards. Adding per-region
            // base URLs here would duplicate edge routing logic in the client and
            // get it wrong differently. It would not help either: a workspace's
            // region is a property of its DATA, not of which origin answers, and
            // the website already reaches a row wherever it lives. Bearer tokens
            // sidestep the session cookie's host-only limitation, which is why one
            // host works at all. ⚠️ THIS FIELD IS THE ONLY COPY of the host
            // (read by ApiEnvironment.baseUrl); do not restate it as a constant
            // in another module, where it would go stale with nothing comparing.
            buildConfigField("String", "API_BASE_URL", "\"https://www.distronode.com\"")

            // ⚠️ SET ON `all`, so debug builds report too when a DSN is supplied. A crash in a
            // debug build is still a crash worth seeing, and `environment` (see DistrictSentry)
            // separates them at the other end, which is the correct place to separate them,
            // because filtering by environment is a query and dropping the DSN is a rebuild.
            // Where the value comes from, and why it defaults to empty, is the note above.
            buildConfigField("String", "SENTRY_DSN", "\"$districtSentryDsn\"")
        }
    }
}

// ── Sentry: the ProGuard mapping, and nothing else ───────────────────────────
//
// ⛔ THE UPLOAD IS GATED ON `SENTRY_AUTH_TOKEN` BEING PRESENT, AND THAT GATE IS WHAT KEEPS
// EVERY LOCAL AND CI BUILD WORKING. CI runs `assembleRelease` (through
// scripts/verify-release-minification.sh) with no Sentry credential, and a
// developer runs it on a laptop that has none either. Left ungated, the plugin's upload task
// would fail those builds on a missing token — turning a crash-reporting nicety into a hard
// dependency of the release gate. Present => upload; absent => generate the UUID, package it,
// upload nothing, exit 0.
//
// ⚠️ THE TOKEN IS DELIBERATELY NOT NAMED IN THIS FILE BEYOND THIS CHECK. sentry-cli reads
// `SENTRY_AUTH_TOKEN` from the environment itself, so there is no `authToken.set(...)` line
// here and therefore no path by which the value could reach a build scan, a configuration
// cache entry on disk, or a `--info` log.
val sentryAuthTokenPresent = providers.environmentVariable("SENTRY_AUTH_TOKEN")
    .map { it.isNotBlank() }
    .orElse(false)

// ⚠️ WHERE A MAPPING UPLOAD GOES IS A PROPERTY OF THE DEPLOYMENT, NOT OF THIS SOURCE TREE, for
// the same reason as the DSN above. Each of the three comes from a Gradle property or an
// environment variable and defaults to EMPTY, and an empty value is left UNSET on the plugin
// rather than passed through: the plugin hands any value that is SET to sentry-cli as `--url` /
// `--org` / `--project` (it checks for null, not for empty), so an empty string would travel as
// an empty argument instead of being left out. Unset, sentry-cli falls back to its own
// configuration (SENTRY_URL / SENTRY_ORG / SENTRY_PROJECT or a sentry.properties file), and
// without a token nothing is uploaded at all.
//
// ⛔ THE URL MUST NAME THE ORGANISATION'S REGION, AND GETTING IT WRONG FAILS BY UPLOADING NOTHING
// WHILE THE BUILD STAYS GREEN. The plugin defaults to the US host (`https://sentry.io`); an
// organisation that lives in the EU region does not exist there, so the mapping upload targets
// an org that cannot be found and the release ships with unreadable stack traces, discovered
// only when the first obfuscated crash arrives. An EU organisation needs `https://de.sentry.io`.
fun districtSentrySetting(property: String, environment: String): Provider<String> =
    providers.gradleProperty(property)
        .orElse(providers.environmentVariable(environment))
        .map(String::trim)
        .filter(String::isNotEmpty)

sentry {
    url.set(districtSentrySetting("districtSentryUrl", "DISTRICT_SENTRY_URL"))
    org.set(districtSentrySetting("districtSentryOrg", "DISTRICT_SENTRY_ORG"))
    projectName.set(districtSentrySetting("districtSentryProject", "DISTRICT_SENTRY_PROJECT"))

    // Generate the debug-meta UUID and package it with the app, always. This is the half that
    // makes a mapping ASSOCIATABLE; the upload below is the half that makes it AVAILABLE. Doing
    // the first unconditionally means a build produced without credentials can still have its
    // mapping uploaded later by hand, keyed to the UUID it already shipped.
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthTokenPresent)

    // ⛔ AUTO-INSTALLATION OFF. It would add `io.sentry:sentry-android` at a version the PLUGIN
    // picks, behind the version catalog's back — and this build LOCKS every configuration, so
    // the injected coordinate would either red the build as "not part of the dependency lock
    // state" or, worse, get locked and become a dependency with no entry in libs.versions.toml.
    // The dependency is declared explicitly below instead.
    autoInstallation { enabled.set(false) }

    // ⛔ NO BYTECODE INSTRUMENTATION. This is a spend decision, not a taste one: tracing
    // instrumentation rewrites classes to emit performance spans (database, file I/O, OkHttp),
    // and spans are billed ingest. The brief for this integration is crash + ANR only. ANRs
    // arrive from `sentry-android`'s own watchdog and need none of this.
    tracingInstrumentation { enabled.set(false) }

    // No native code in this app, so there are no native symbols to look for. Left explicit
    // because the default is to go looking.
    uploadNativeSymbols.set(false)

    // ⛔ THE DEPENDENCY REPORT IS OFF, AND IT IS ON BY DEFAULT. Enabled, the plugin resolves the
    // runtime classpath and PACKAGES the resulting inventory of every dependency and version
    // into the app as an asset, so Sentry can display it on an issue. That ships a complete
    // component-and-version list to every device, which is a disclosure this app gets nothing
    // back for: the mapping is what makes a crash readable, and the dependency inventory already
    // lives in the committed lockfiles. Off also removes a classpath resolution from the release
    // build.
    includeDependenciesReport.set(false)

    // ⛔ SOURCE CONTEXT OFF. It uploads the actual SOURCE of every frame to Sentry so the UI can
    // show it inline. That is a source-code egress decision nobody has made, and it is not
    // needed to read a stack trace — the mapping plus the kept line numbers (see
    // app/proguard-rules.pro) already give file and line.
    includeSourceContext.set(false)

    // ⛔ PLUGIN TELEMETRY OFF, AND THIS IS THE PREFLIGHT THE TOKEN CANNOT PASS. Enabled, the
    // plugin opens its own Sentry transaction against the configured org before doing work,
    // which requires reading project metadata. An ORG token scoped for artifact upload, which is
    // the right kind of token for this job, has no `project:read`, so that preflight answers
    // 403: a failure that reads like a broken credential and is not one. Off, the plugin goes straight to the
    // upload it is actually authorised for.
    telemetry.set(false)
}


dependencies {
    implementation(projects.core.coreAuth)
    implementation(projects.core.coreData)
    implementation(projects.core.coreDesignsystem)
    // ⛔ THE LIVEKIT SDK DOES NOT ARRIVE WITH THIS. core-media declares `livekit-android` as
    // `implementation`, not `api`, so nothing here can reference an `io.livekit` type even by
    // accident — which is the whole reason that module exists. What crosses is CallEngine, its
    // data types, and the one composable (`VideoTile`) that has to live beside the SDK.
    implementation(projects.core.coreMedia)
    implementation(projects.core.coreModel)
    implementation(projects.core.coreNetwork)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    // ⛔ FIREBASE IS INITIALISED BY HAND AND THE `google-services` PLUGIN IS NOT
    // APPLIED. This artifact brings the FCM SDK and, transitively, firebase-common —
    // which is all `FirebaseApp.initializeApp(context, FirebaseOptions)` needs. See
    // DistrictApplication for why the file-driven path was rejected, and
    // libs.versions.toml for why there is no BoM.
    //
    // ⚠️ `implementation`, so no other module can name a `com.google.firebase` type.
    // Everything push-shaped that the rest of the app touches crosses through
    // core-data's PushTokenRepository and this module's own PushMessage/PushEvent
    // types, none of which is an SDK type — the same containment core-media applies
    // to LiveKit, for the same reason.
    implementation(libs.firebase.messaging)

    // ⛔ A FRAGMENT FLOOR, BECAUSE FIREBASE BRINGS FRAGMENT 1.1.0 AND NOTHING ELSE ASKS FOR A
    // NEWER ONE. play-services-basement 18.9.0, which arrives through firebase-messaging above,
    // declares androidx.fragment:fragment 1.1.0 as a compile dependency in its POM, and
    // play-services-base 18.9.0 (same route) asks for the same 1.1.0. Nothing else in the graph
    // asks for a newer fragment, so conflict resolution had nothing to raise it to and 1.1.0
    // resolved into releaseRuntimeClasspath, the classpath the release bundle is built from.
    // Google Play's SDK Index then flagged the uploaded bundle: fragment 1.1.0 "reported as
    // outdated", with the advice to consider 1.2.1 or later.
    //
    // ⚠️ A CONSTRAINT, NOT A DEPENDENCY, AND THE DIFFERENCE IS THE POINT. A constraint only acts on
    // a module that something else already brings in: it raises the floor for fragment without
    // adding fragment to this module's own API or declared dependencies, and if Firebase ever
    // drops fragment the constraint simply stops applying instead of keeping an artifact nothing
    // asks for on the classpath.
    //
    // ⚠️ The version is `androidxFragment` in gradle/libs.versions.toml, and like every version
    // there it does nothing until the lockfiles are regenerated (see "Regenerating the lockfiles"
    // in the root build.gradle.kts).
    constraints {
        implementation(libs.androidx.fragment) {
            because(
                "play-services-basement 18.9.0 and play-services-base 18.9.0 (via " +
                    "firebase-messaging) declare fragment 1.1.0 and nothing else asks for newer, " +
                    "so 1.1.0 resolved into the release classpath; Google Play's SDK Index flags " +
                    "it as outdated (consider 1.2.1+)",
            )
        }
    }

    // ⛔ CRASH + ANR REPORTING, AND `implementation` FOR THE SAME CONTAINMENT REASON AS FIREBASE
    // AND LIVEKIT. No other module can name an `io.sentry` type, so the SDK cannot leak into
    // core-* code and the whole integration stays reviewable in two files (this one and
    // DistrictSentry). ⚠️ It brings `io.sentry:sentry` (the JVM core) transitively, which is
    // what makes `DistrictSentryTest` able to construct a real `SentryOptions` and assert the
    // configuration without a device or a network.
    //
    // ⚠️ NO KEEP RULES ARE NEEDED IN app/proguard-rules.pro FOR THIS. The AAR ships CONSUMER
    // rules (`proguard.txt` inside sentry-android-core-8.53.0.aar, read to confirm rather than
    // assumed) which AGP merges into the R8 configuration automatically — including
    // `-keepattributes LineNumberTable,SourceFile`, `-keep class * extends io.sentry.SentryOptions`
    // and `-keepnames class * implements io.sentry.Integration`. See the note in
    // proguard-rules.pro for why the repo's own line-number keep stays anyway.
    implementation(libs.sentry.android.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The shared API fakes and MainDispatcherRule; see core-network's `testFixtures` note.
    testImplementation(testFixtures(projects.core.coreNetwork))
    // ⛔ debugImplementation, NOT testImplementation, and the distinction is the
    // whole bug. `createComposeRule()` launches a stub `androidx.activity.
    // ComponentActivity`, which has to be present in the MERGED MANIFEST of the
    // variant under test — this artifact exists to contribute exactly that manifest
    // entry. On the test classpath its classes resolve but its manifest is never
    // merged, so the test fails with
    // "Unable to resolve activity for Intent { ... cmp=.../androidx.activity.ComponentActivity }",
    // which reads as a Robolectric or Compose problem rather than a wrong
    // configuration name.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
