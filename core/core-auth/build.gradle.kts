plugins {
    id("district.android.library")
}

android {
    namespace = "com.distronode.districtai.core.auth"
}

dependencies {
    // ⛔ NO androidx.security:security-crypto. It is deprecated (classes, not just enums) and
    // its own package summary directs callers to javax.crypto.KeyGenerator with
    // AndroidKeyStore — which KeystoreTokenStore uses directly, from the platform, with no
    // dependency at all.
    //
    // `api`, not `implementation`: TokenRefreshCoordinator's public surface returns types
    // built on coroutines, so consumers need it on their compile classpath.
    api(libs.kotlinx.coroutines.core)

    // ⚠️ The RUNTIME library only — no serialization compiler plugin is applied to this
    // module. NativeAuthApi uses buildJsonObject / parseToJsonElement, which need no
    // @Serializable classes. Adding the plugin here would be dead weight.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // For reading the committed PKCE vectors. ⚠️ Only `parseToJsonElement` is used, which
    // needs no @Serializable classes and therefore no serialization COMPILER PLUGIN on this
    // module — just the runtime library on the test classpath.
    testImplementation(libs.kotlinx.serialization.json)
    // Robolectric so the fail-closed behaviour of the Keystore store can be asserted on the
    // JVM. ⚠️ Robolectric does NOT implement AndroidKeyStore, which is precisely what makes
    // it a useful harness here: it reproduces "the Keystore is unavailable", and the store
    // must degrade to "no session" rather than crash. Round-trip encryption is verified on a
    // real device instead.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // Real HTTP over a real socket. ⛔ The status-code mapping in NativeAuthApi is
    // security-relevant (a mis-mapped 429 signs users out; a mis-mapped 5xx can revoke a token
    // family), so it is verified against actual responses rather than a stubbed client that
    // would only prove the stub agrees with itself.
    testImplementation(libs.okhttp.mockwebserver)
}

// ── PKCE cross-language vectors ──────────────────────────────────────────────
// Generated from the SERVER's deriveCodeChallenge by the website's contract suite and
// committed under contracts/. See PkceVectorTest.
//
// ⛔ `inputs.dir` IS REQUIRED, for the same reason it is in core-model: the fixtures reach the
// test through a system property, which Gradle cannot see into, so without it the task stays
// UP-TO-DATE across a vector change and the check silently never re-runs.
tasks.withType<Test>().configureEach {
    val contractsDir = rootProject.file("contracts")
    systemProperty("district.contracts.dir", contractsDir.absolutePath)
    inputs.dir(contractsDir)
        .withPropertyName("androidContractFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
