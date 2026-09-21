plugins {
    id("district.android.library")
    // Needed for ApiErrorEnvelope, which is @Serializable and lives HERE rather than in
    // core-model on purpose: it is not a domain type, it is the union of the three failure
    // envelopes this API produces, and it exists only to be normalised into ApiResult a few
    // lines away. Splitting it from that mapping would separate the shape from the only code
    // that understands it.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.distronode.districtai.core.network"
}

// ⛔ NO RETROFIT, AND THAT IS A DECISION RATHER THAN AN OMISSION.
//
// Retrofit would earn its place if it removed work here, and it does not:
//
//   1. THE ERROR HANDLING IS BESPOKE REGARDLESS. This API answers failures in three
//      different envelopes — `{success:false,error}`, a bare `{error}`, and
//      `{error,code}` — and which one you get depends on whether the route's own handler
//      or the shared auth guard produced the response, not on the route. Every district
//      route returns its guard's bare `{error}` on 401/403/404 while using
//      `{success:false,error}` for its own 400/500. Retrofit hands back a Response and
//      the normalisation still has to be written by hand.
//   2. THE TOKEN SEAM IS OKHTTP-LEVEL. Acquiring a token is a `suspend` call into
//      TokenRefreshCoordinator, and both of Retrofit's usual hooks are the wrong shape:
//      an OkHttp `Authenticator` runs synchronously on a dispatcher thread (so it would
//      need runBlocking) and can only answer "here is another request" or "give up",
//      which cannot express the coordinator's three-way Available / RetryLater /
//      ReauthRequired outcome.
//   3. core-auth ALREADY SPEAKS PLAIN OKHTTP. A second HTTP idiom in one app is a second
//      place for timeouts, interceptors and error mapping to disagree.
//
// Against that, it would add two dependencies (retrofit plus a serialization converter) to
// a repo whose dependency-scanning gate is already sensitive to manifest depth. So the
// typed surface is a small generic client over OkHttp instead. Revisit if the endpoint
// count grows enough that the boilerplate outweighs the above.
dependencies {
    // `api`, not `implementation`: ApiResult is returned from this module's public
    // functions and DTOs appear in its type parameters, so both must be on a consumer's
    // compile classpath.
    api(projects.core.coreModel)
    // AccessToken / ReauthReason surface in the failure mapping.
    api(projects.core.coreAuth)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    // The response DTOs live in core-model (which applies the plugin itself); the only
    // @Serializable class here is ApiErrorEnvelope. `implementation`, not `api`: Json is a
    // decoding detail of this module and no consumer should need it to call an endpoint.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ⛔ Real HTTP over a real socket, for the same reason core-auth does it: the
    // status-code and envelope mapping here decides whether a user is signed out, told to
    // wait, or shown an empty account. A stubbed client would only prove the stub agrees
    // with itself.
    testImplementation(libs.okhttp.mockwebserver)
}

// ── Contract fixture (error envelope) ────────────────────────────────────────
// ApiErrorEnvelopeContractTest pins the REGIONS_DEGRADED body against the fixture the
// server's test suite generates from the real handler. Same wiring as core-model and
// core-auth.
//
// ⛔ `inputs.dir` IS REQUIRED. The fixtures reach the test through a system property, which
// Gradle cannot see into, so without it the task stays UP-TO-DATE across a fixture change and
// the check silently never re-runs: editing a fixture produces "BUILD SUCCESSFUL in 1s" with
// the test never running.
tasks.withType<Test>().configureEach {
    val contractsDir = rootProject.file("contracts")
    systemProperty("district.contracts.dir", contractsDir.absolutePath)
    inputs.dir(contractsDir)
        .withPropertyName("androidContractFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// ── Two-sided endpoint parity (EndpointParityTest) ───────────────────────────
// EndpointParityTest diffs this client's endpoint surface against the iOS client's: this module's
// API interfaces, read from source, against the iOS client's `EndpointID.swift`.
//
// ⚠️ THE iOS SIDE IS A VENDORED SNAPSHOT, `parity/EndpointID.swift`, because the iOS client lives
// in its own repository and this build cannot assume a checkout of it sits anywhere nearby.
// parity/README.md records which upstream commit it came from and how to refresh it. A snapshot
// can go stale, so `DISTRICT_IOS_ENDPOINT_IDS=<path>` points the test at a live iOS checkout
// instead; set, it wins outright. A relative path resolves against the repository root.
//
// ⛔ NO FALLTHROUGH BETWEEN THE TWO. An override that names a missing file fails the test with THAT
// path in the message rather than quietly dropping back to the snapshot, because a silent
// fallback would report parity against a file nobody asked for, and a failure message naming a
// path that was never configured is its own debugging session.
//
// ⛔ `inputs.files`, NOT `inputs.file`. `file` HARD-FAILS the task when the path does not exist,
// which would turn a mistyped override into an unrunnable build instead of a clear assertion
// message; `files` tolerates absence and lets the test say what is missing and why. Declaring it
// at all is required for the same reason as the fixtures above: the path reaches the test
// through a system property, which Gradle cannot see into, so without it an edit to
// EndpointID.swift leaves this task UP-TO-DATE and the parity check silently never re-runs.
//
// ⛔ THE ANDROID SIDE IS THE WHOLE PACKAGE DIRECTORY, NOT `DistrictApi.kt`. Endpoints live on
// sibling interfaces in the same package too, and a single-file read would report every one of
// them as missing WITHOUT FAILING, because the recorded list would still match the one file it
// could see. Handing over the directory makes a new API interface part of the gate on the day it
// is written rather than on the day somebody remembers to add it here.
val iosEndpointIdOverride = providers.environmentVariable("DISTRICT_IOS_ENDPOINT_IDS")
    .map(String::trim)
    .filter(String::isNotEmpty)

tasks.withType<Test>().configureEach {
    val iosEndpointId = iosEndpointIdOverride
        .map { rootProject.file(it) }
        .orElse(rootProject.layout.projectDirectory.file("parity/EndpointID.swift").asFile)
        .get()
    val districtApiDir =
        file("src/main/kotlin/com/distronode/districtai/core/network")

    systemProperty("district.ios.endpointid", iosEndpointId.absolutePath)
    // Tells the test which of the two it was handed, so a failure names its source.
    val iosEndpointIdOrigin = if (iosEndpointIdOverride.isPresent) "DISTRICT_IOS_ENDPOINT_IDS" else "vendored"
    systemProperty("district.ios.endpointid.origin", iosEndpointIdOrigin)
    systemProperty("district.api.kotlin.dir", districtApiDir.absolutePath)

    inputs.files(iosEndpointId)
        .withPropertyName("iosEndpointIdSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // ⚠️ These sources are already compile inputs; they are declared again here because this test
    // reads them as TEXT, and Gradle fingerprints inputs per task, not per file.
    inputs.files(fileTree(districtApiDir) { include("**/*.kt") })
        .withPropertyName("districtApiSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
