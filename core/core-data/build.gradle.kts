plugins {
    id("district.android.library")
}

android {
    namespace = "com.distronode.districtai.core.data"
}

dependencies {
    // `api`: repository functions return ApiResult and the core-model DTOs, so both belong
    // on a consumer's compile classpath.
    api(projects.core.coreNetwork)
    api(projects.core.coreModel)
    api(libs.kotlinx.coroutines.core)
    // `api`: CallsRepository exposes a Flow<PagingData<…>>, so consumers need Paging's types
    // in order to collect it.
    api(libs.androidx.paging.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ⚠️ paging-common only, on the TEST classpath: CallsPagingSource.load() is exercised
    // directly rather than through a Pager, which needs no Android runtime and no
    // androidx.paging:paging-testing artifact.
    testImplementation(libs.androidx.paging.common)
    // The shared API fakes (FakeDistrictApi and siblings); see core-network's `testFixtures` note.
    testImplementation(testFixtures(projects.core.coreNetwork))
}

// ⚠️ STILL NO ROOM, DELIBERATELY, EVEN NOW THAT PAGING IS HERE. Paging arrived with the calls
// feed because that endpoint genuinely takes limit/offset. Room has not, and the reason is not
// only "no offline requirement yet": a Room-backed RemoteMediator needs a stable pagination key,
// and this API provides none. The feed is OFFSET-based over a live `createdAt desc` ordering
// with no cursor and no total, so a cached page cannot be reconciled against a later fetch
// without re-reading from the top — the offsets have already shifted. Adding Room before there
// is a real offline requirement would buy a schema, a migration story and an invalidation
// problem in exchange for nothing.

// ── Contract fixture (Voice Studio) ──────────────────────────────────────────
// VoiceStudioRepositoryTest decodes the committed Studio fixture through core-network's
// `VoiceStudioFixture`. Same wiring as core-model; ⛔ `inputs.dir` keeps a fixture change from
// leaving this task UP-TO-DATE.
tasks.withType<Test>().configureEach {
    val contractsDir = rootProject.file("contracts")
    systemProperty("district.contracts.dir", contractsDir.absolutePath)
    inputs.dir(contractsDir)
        .withPropertyName("androidContractFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
