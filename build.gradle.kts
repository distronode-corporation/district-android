// Root build file. Deliberately almost empty: plugins are applied per-module
// through the convention plugins in build-logic/, not accumulated here.

// ── bouncycastle on the root build's own plugin classpath ────────────────────
//
// ⛔ THE FORCED VERSIONS FURTHER DOWN DO NOT REACH THIS CLASSPATH. They live in
// `subprojects { }` and so apply to the modules' configurations only, while the `plugins { }`
// block below resolves AGP, Sentry, detekt and Kover into the ROOT project's buildscript
// `classpath` configuration, which nothing locks and nothing forced. AGP 9.4.1 brings
// bouncycastle 1.80.2 (bcprov, bcpkix, bcutil) onto it, through sdk-common, builder and
// apkzlib, below the 1.85 that clears GHSA-9pwp-9qqc-pr26 (critical), GHSA-qp49-qgx5-5m26
// (high), GHSA-c3fc-8qff-9hwx and GHSA-wg6q-6289-32hp. `./gradlew buildEnvironment` shows
// the chain.
//
// It matters because this classpath is REPORTED. .github/workflows/dependency-submission.yml
// sends every configuration Gradle resolves to GitHub's dependency graph, the plugin
// classpath included, so an unforced 1.80.2 here is a Dependabot alert on the public
// repository even though none of it ships to a device. It is also code that runs: AGP runs
// on this classpath, and sdk-common's KeystoreHelper generates the debug keystore with
// bouncycastle, so a build on a machine with no debug keystore exercises this force.
//
// ⚠️ Same set, same version as the module force below: bcprov, bcpkix and bcutil are the
// only org.bouncycastle modules that resolve here (checked with buildEnvironment), and the
// number is `bouncyCastle` in gradle/libs.versions.toml, shared with that block and with
// build-logic, which is a separate build and forces its own configurations.
//
// ⚠️ AND FOUR MORE, FOR THE SAME REASON. Kover brings freemarker 2.3.32 (through its IntelliJ
// coverage reporter), and AGP brings jdom2 2.0.6 (Jetifier), jose4j 0.9.5 (bundletool) and
// commons-lang3 3.16.0 (sdk-common, via commons-compress), each below the patch for a Dependabot
// advisory on this repository. None is reachable here: the reporter only renders Kover's own
// templates and this build reports through JaCoCo, Jetifier is off, and bundletool and the SDK
// tools only read this build's own files. Forced anyway, because the alert cannot tell that, and
// the patch-level bump is cheaper than explaining it. The numbers are in gradle/libs.versions.toml
// beside `bouncyCastle`, and build-logic forces the same four.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach {
                force("org.bouncycastle:$it:${libs.versions.bouncyCastle.get()}")
            }
            force("org.freemarker:freemarker:${libs.versions.freemarker.get()}")
            force("org.jdom:jdom2:${libs.versions.jdom2.get()}")
            force("org.bitbucket.b_c:jose4j:${libs.versions.jose4j.get()}")
            force("org.apache.commons:commons-lang3:${libs.versions.commonsLang3.get()}")
        }
    }
}

plugins {
    // `apply false` registers the plugin versions on the classpath without applying
    // them to the root project, which has no sources of its own.
    //
    // ⛔ No kotlin-android alias: AGP 9 has built-in Kotlin and applying that plugin
    // is a hard error. See libs.versions.toml.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // ⚠️ REGISTERED HERE, APPLIED IN :app. `apply false` puts the plugin on the root's
    // buildscript classpath so `id("io.sentry.android.gradle")` in app/build.gradle.kts
    // needs no version of its own — the same shape the AGP aliases above use. It is not
    // applied to the root, which has no sources and produces no release artifact.
    alias(libs.plugins.sentry.android) apply false
    // Applied to the root so `./gradlew detekt` covers every module in one command,
    // which is what the CI verify stage invokes.
    alias(libs.plugins.detekt)
    // ⛔ THE ROOT IS KOVER'S "MERGING MODULE" AND THAT IS THE WHOLE POINT. Coverage is
    // aggregated across every module and gated on ONE number, because a per-module
    // floor lets a well-covered module average out a bare one. The modules themselves
    // get Kover from district.android.base, not from here.
    alias(libs.plugins.kover)
}

// ── Static analysis (detekt, with ktlint rules via detekt-formatting) ────────
//
// One tool, not two. detekt-formatting embeds the ktlint rule engine, so there is no
// separate ktlint CLI to pin and no chance of two formatters disagreeing.
//
// ⚠️ Applied to `allprojects`, not `subprojects`: the root build file is itself
// Kotlin and worth linting.
// ⚠️ Captured OUTSIDE the allprojects block. `libs` is the generated version-catalog
// accessor, which exists for THIS build script; it is not resolvable from inside the
// per-project closure below. (It is also unrelated to the `libs` helper in
// build-logic/CatalogAccess.kt, which exists because precompiled script plugins get no
// such accessor at all.)
val detektFormatting = libs.detekt.formatting

allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // ⚠️ `extensions.configure`, not a `detekt { }` block. The typed accessor is only
    // generated for a script that declares the plugin in its own `plugins { }` block,
    // so inside allprojects it has to be reached by extension type.
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        // Start from detekt's shipped defaults and layer this repo's config on top, so
        // a detekt upgrade brings new rules with it instead of silently keeping an old
        // frozen rule set.
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // ⚠️ Deliberately NOT `parallel = true`. detekt's parallel mode competes with the
        // Kotlin daemon for the same cores and buys nothing at this project size.
        parallel = false
        // Analyse test sources too. A broken test is still broken code, and the
        // formatting rules apply equally.
        source.setFrom(files("src"))
    }

    dependencies {
        add("detektPlugins", detektFormatting)
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        // JVM target must match the modules' Java 11 target or detekt warns on every
        // run about a mismatch it cannot resolve.
        jvmTarget = "11"

        // ⚠️ OPT-IN AUTOCORRECT, off by default: `./gradlew detekt -PdetektAutoCorrect`.
        //
        // Only the formatting rules (ktlint, via detekt-formatting) can rewrite code, and they are
        // exactly the ones not worth fixing by hand — indentation, argument wrapping, line breaks. A
        // mechanical refactor across a large test file can produce dozens of those in one go.
        //
        // ⛔ DEFAULTED OFF SO CI CANNOT SILENTLY REWRITE SOURCE. A verify job that edited the tree
        // would report success on code nobody reviewed, and the diff would only exist on the runner.
        // Correctness rules (the ones that actually find bugs) are never auto-correctable, so this
        // cannot paper over a real finding either way.
        autoCorrect = providers.gradleProperty("detektAutoCorrect").isPresent
        reports {
            // SARIF is the format code-scanning tools ingest; XML is for any tool that
            // wants Checkstyle shape. CI keeps both as artifacts.
            sarif.required.set(true)
            xml.required.set(true)
            html.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
}

// ── Coverage floors ──────────────────────────────────────────────────────────
//
// ⛔ SET BY DECISION, AND ONLY AFTER THE MEASUREMENT CLEARED THEM. Current measurement, on the
// root `unit` report that `koverVerifyUnit` checks (the debug variant of app plus the six core
// modules, with the generated-code exclusions below applied), under JaCoCo 0.8.15:
//
//     line   19800/19809 = 99.95%  ->  floor 99
//     branch  6960/7055  = 98.65%  ->  floor 98
//
// Measured on the Voice Studio (2.0) tree. Two full runs (the second with --rerun-tasks) gave
// the same missed counts, 9 lines and 95 branches, class for class; the branch TOTAL differed by
// 12 inside DistrictNavHostKt between an incremental and a clean compile, and the clean compile's
// figure is the one above.
// Kover takes a floor as a whole percentage, so each is the measurement rounded down.
//
// The floors were 86 and 49, then 90 and 60, then 95 and 65 under Kover's default IntelliJ
// engine, then 95 and 80 on JaCoCo before the coverage programme brought the tree to these values.
//
// ⚠️ WHAT THE REMAINING MISSES ARE, so that a new one stands out. Lines include the real
// `SentryAndroid.init` call in DistrictSentry (it installs process-wide handlers). The last line
// of TokenRefreshCoordinator.forget() used to come and go between runs (which way `withContext`
// returned was a thread race on the real IO pool); core-auth's tests now pin both ways with
// injected dispatchers.
// 95 branches, almost all generated by the Compose compiler rather than written (the Voice Studio
// added none: its composables draw models built outside composition, and its few event lambdas
// are `@DontMemoize`, so no memoization check is left for a test to reach): the last arm of
// an exhaustive `when` inside a composable (the compiler wraps its NoWhenBranchMatchedException
// in a group JaCoCo does not recognise), and `remember`/`changed()` checks on arguments that are
// constant or never marked unstable at any call site. Two are guards kept on purpose (the
// `meet_`-only rejoin in DistrictNavHost and the E2EE key check in PersonaPreviewViewModel). None
// of them is closed by an exclusion or an annotation: an exhaustive `when` is not traded for an
// `else` to reach a number.
//
// ⛔ WHY JACOCO. Kover's IntelliJ engine counts the bytecode the compiler plugins emit as if
// someone had written it: kotlinx.serialization's `write$Self` encoders (1507 missed branches on
// the same tree) and the Compose compiler's `$changed`/`$default` bitmask branches (1431 more,
// many of which no test can reach). JaCoCo 0.8.15 filters both, and suspend-function state
// machines, as compiler output. That is a measuring decision, not an exclusion: every line and
// branch a person wrote is still counted. Measured on the same tree, missed branches went from
// 3829 to 1331 and missed lines from 716 to 882 (JaCoCo attributes more lines, mostly in
// Compose files), both with the Sentry class below already excluded.
//
// ⛔ THE GATE IS THE `unit` VARIANT, NEVER `total`. JaCoCo matches execution data to classes by a
// checksum of the class bytes, where the IntelliJ engine matched by name. `total` analyses the
// debug AND release compilations, which share class names but not bytes; JaCoCo keeps one copy
// per name, and for app it kept the release copy that no test loads, so `total` read 41.94% on
// a tree whose tests had all run. `unit` holds the debug variant only (see the convention
// plugin), which is the variant `testDebugUnitTest` executes.
//
// ⚠️ A HIGH LINE FLOOR IS UNREACHABLE WITHOUT COMPOSE UI TESTS, AND THAT IS ARITHMETIC RATHER
// THAN A PREFERENCE: most missed lines live in Compose files, so screen-level tests are where
// coverage moves. Rendering a screen in more than one state is also what moves branch coverage,
// because a screen rendered in exactly one state exercises none of its `when` arms.
//
// Measured with `./gradlew testDebugUnitTest :koverXmlReportUnit :koverLogUnit`. Reproduce it the
// same way; the report-level counters are the last ones in build/reports/kover/reportUnit.xml.
//
// ⚠️ BOTH FLOORS SIT BELOW THE MEASURED VALUE, BY LESS THAN ONE POINT. That is Kover's whole
// percentage, not chosen slack, and it still reds on any real loss of a point.
//
// ⚠️ THE COUNT MUST NOT DEPEND ON THREAD TIMING, or a tight floor becomes a flaky one. Two full
// runs of the same tree produce identical counts, class for class. `DistrictNavHostTest` holds its
// credential reads for exactly that reason (see its class doc): before it did, two runs differed
// by tens of branches. A test that lets a background result race the UI it asserts on brings that
// back.
//
// ⚠️ A FLOOR, NOT A TARGET. They exist so coverage becomes a ratchet: the point is that it can no
// longer silently fall. A test-count-is-not-zero assertion alone would let almost every test file
// be deleted with the pipeline still green.
//
// ⛔ RAISE THESE AS COVERAGE IMPROVES. NEVER LOWER ONE TO MAKE A BUILD PASS.
//
// ⚠️ Hardcoded on purpose, deliberately NOT `findProperty(...) ?: 0`. A floor that falls back to
// zero when a property is unset is a gate that passes when it is misconfigured, which is the
// exact failure a floor exists to prevent.
val LINE_COVERAGE_FLOOR = 99
val BRANCH_COVERAGE_FLOOR = 98

// ── Coverage aggregation and the floor ───────────────────────────────────────
//
// ⛔ WHY A REAL FLOOR. A guard asserting only that the executed-test count is not zero is a
// floor of ONE: deleting all but one test file keeps the pipeline green.
//
// ⚠️ CI also keeps a zero-test guard alongside this floor. It catches a different failure: a test
// task that discovers nothing succeeds, and a coverage report over zero executed tests
// is 0% rather than an error, so the two guards fail on different things.
//
// Aggregation: every module is listed as a `kover` dependency so its classes AND its
// counters land in the one report. ⚠️ A module absent from this list is silently
// EXCLUDED from the measurement — it is not a build error — so adding a module to
// settings.gradle.kts means adding it here too. That is the same "adding a lane means
// adding it here" trap the dependency-locking note below describes.
dependencies {
    kover(projects.app)
    kover(projects.core.coreAuth)
    kover(projects.core.coreData)
    kover(projects.core.coreDesignsystem)
    kover(projects.core.coreMedia)
    kover(projects.core.coreModel)
    kover(projects.core.coreNetwork)
}

kover {
    // JaCoCo, not Kover's default IntelliJ engine: see "WHY JACOCO" above. The modules set the
    // same engine and version in the `district.android.base` convention plugin; a module
    // measured by the other engine writes execution data this report cannot read ("Invalid
    // execution data file").
    useJacoco("0.8.15")
    currentProject {
        // The merged gate. Empty here: a custom variant in the merging project collects the
        // same-named variant from every `kover(...)` dependency above.
        createVariant("unit") {}
    }
    reports {
        variant("unit") {
            log {
                // ⚠️ A DISTINCTIVE PREFIX, ON PURPOSE. Anything that scrapes the number
                // out of a build log (a coverage badge, a CI summary) needs a string no
                // other log line contains; a generic "line coverage" would also match
                // text in an unrelated line and report a wrong number.
                format = "ANDROID AGGREGATE line coverage: <value>%"
                // ⚠️ FALSE: the number appears only when koverLogUnit is named explicitly,
                // which is exactly what CI does (`./gradlew :koverLogUnit :koverVerifyUnit`).
                // Left false deliberately: naming it in CI is deterministic, whereas relying
                // on a `check` hook would put the number in the log only for whichever
                // invocations happen to include one.
                onCheck = false
            }
        }

        filters {
            excludes {
                // ⛔ GENERATED CODE MUST BE EXCLUDED OR THE NUMBER MEANS NOTHING. These
                // classes are emitted by AGP, the Compose compiler and the
                // serialization plugin. They are not written by anyone, cannot be
                // meaningfully tested, and their volume swamps the hand-written code —
                // measured here, including them moved the aggregate by whole
                // percentage points, so the metric would track compiler output rather
                // than test quality.
                classes(
                    // AGP: BuildConfig, the R class and its nested resource holders.
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    // Compose compiler: lambda-caching singletons, one per file with
                    // composable literals.
                    "*.ComposableSingletons*",
                    // kotlinx.serialization: the generated serializer for every
                    // @Serializable DTO. These ARE exercised, transitively, by the
                    // contract tests — but they are generated, so counting them
                    // inflates the number without anyone having written a test.
                    "*$\$serializer",
                    // Sentry Gradle plugin: build-time options it writes as Java source.
                    "io.sentry.android.core.SentryGeneratedBuildTimeOptions",
                )
                // Compose tooling: @Preview functions, which exist only for the IDE.
                //
                // ⛔ BY ANNOTATION, NEVER BY A NAME GLOB. The glob this replaced
                // (`*.*Preview*Kt`) matched every file facade with "Preview" in its name,
                // so it also hid PersonaPreviewDialog.kt, PersonaPreviewHost.kt and
                // PersonaPreviewViewModel.kt, a real feature, from the floor. The
                // annotation names only what the IDE renders. Kover's JaCoCo path reads
                // class-file annotations of BINARY retention, which @Preview has.
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }

        verify {
            rule("aggregate line coverage") {
                bound {
                    // ⚠️ A FLOOR BELOW THE MEASURED VALUE, NOT AN ASPIRATION. It sits about
                    // one point under the measurement recorded at LINE_COVERAGE_FLOOR so
                    // a trivial refactor does not red the pipeline. It is deliberately not
                    // a target: it exists to make coverage a ratchet that cannot slip
                    // backwards.
                    //
                    // ⛔ RAISE IT WHEN COVERAGE RISES; NEVER LOWER IT TO MAKE A BUILD
                    // PASS. Lowering it is how a floor becomes decoration. If a change
                    // legitimately cannot hold the line, that is a conversation, not a
                    // one-line edit.
                    minValue = LINE_COVERAGE_FLOOR
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("aggregate branch coverage") {
                bound {
                    // ⚠️ Branch coverage is the weaker of the two here and that is
                    // expected: Kotlin emits branches for null checks and default
                    // arguments that no test targets directly. Gated anyway, because a
                    // change that adds untested conditionals shows up here first.
                    minValue = BRANCH_COVERAGE_FLOOR
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// ── Dependency locking ───────────────────────────────────────────────────────
// ⛔ THIS IS WHAT MAKES A DEPENDENCY SCAN MEAN ANYTHING. A dependency scanner reads a
// resolved manifest; without lockfiles a Gradle build resolves transitively at build
// time and any SBOM describes whatever happened to resolve on that machine, not what
// this commit pins.
//
// ⚠️ Gradle writes ONE lockfile PER PROJECT (`<project>/gradle.lockfile`), and
// there is no supported single-root-lockfile mode. So `:app` produces
// `app/gradle.lockfile` one level below the build root, and any dependency scanner
// or SBOM-coverage check must search deep enough to find every module's lockfile
// (a scanner's max scan depth and any coverage gate's depth have to agree, or the
// coverage hole is hidden). Do not treat the lockfile location as free to move.
//
// ⛔ REGENERATE WITH THE PER-MODULE `dependencies` COMMAND UNDER "Regenerating the lockfiles"
// BELOW, NOT WITH A `resolveAndLockAll` TASK. NO SUCH TASK EXISTS (the invocation dies with
// "Task 'resolveAndLockAll' not found", which reads as a broken checkout), and it is the very
// task the note at the bottom of this file forbids reintroducing.
//
// ⚠️ Read OUTSIDE the subprojects block, for the reason given at `detektFormatting` above:
// `libs` is this script's accessor, not the per-project closure's. The number is shared with
// the root plugin-classpath force at the top of this file and with build-logic.
val bouncyCastleVersion: String = libs.versions.bouncyCastle.get()
val commonsLang3Version: String = libs.versions.commonsLang3.get()

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    // ── Forced patch versions for build-only CVEs ────────────────────────────
    //
    // ⚠️ EVERY COORDINATE FORCED BELOW IS BUILD-ONLY. Verified by reading the
    // configuration list on the right-hand side of each lockfile entry: all of them
    // sit in `androidLintTool` (AGP's own Lint classpath) and bcprov additionally in
    // `debugUnitTest*` (transitively through Robolectric). NO release or debug
    // runtime/compile classpath contains any of them, and the built APK has zero
    // entries for all five artifacts. None of this ships to a device.
    //
    // They are forced anyway, for one specific reason: `lockAllConfigurations()`
    // above puts test and lint-tool coordinates into the lockfiles, and a dependency
    // scanner reads a lockfile without any notion of which configuration an entry came
    // from. So a build-only CVE is indistinguishable from a shipped one in a scan
    // report. Forcing the patch is far cheaper than either unlocking test
    // configurations (which would make the SBOM stop describing what actually
    // resolves) or teaching every scanner about configurations, which none of them
    // offer.
    //
    // ⛔ Applied to ALL configurations deliberately, including AGP's internal ones. A
    // `dependencies { constraints { } }` block reaches neither `androidLintTool` nor
    // the Robolectric classpath, so it fixes 0 of these findings. This is not a style
    // preference: a constraints block was measured to fix nothing.
    //
    // ⛔ THESE FORCES WERE CHOSEN AS THE LOWEST VERSION THAT CLEARS EACH ADVISORY, not the
    // newest release, because they land on a Lint classpath nobody tests, so the smallest
    // delta that removes the finding was the right trade. For bouncycastle that was 1.85,
    // the lowest version that clears all 22 advisories below. ⚠️ BOUNCYCASTLE HAS SINCE
    // MOVED PAST THAT FLOOR, DELIBERATELY: it is 1.86, the newest release for all three
    // modules when every dependency was brought up to its latest stable release (resolved
    // from Maven Central's metadata on 2026-09-26). 1.86 is above every fix version the
    // advisories name, so it clears the same 22; the floor that must never be crossed
    // downwards is still 1.85. Latest at the time the floor was set was commons-lang3
    // 3.20.0 and httpclient 4.5.14.
    //
    // ── bouncycastle: 1.86, as a SET (floor 1.85) ────────────────────────────
    // 22 advisories, reported by dependency scanning against 1.84, and every one of
    // them says "Upgrade to version 1.85.0 or above". They
    // arrive as 154 findings because the scanner reads each lockfile separately and
    // there are seven: 3 critical x 7 = 21, 14 high x 7 = 98, 5 medium x 7 = 35. ⚠️ So
    // the count is a multiple of the lockfile count rather than 154 separate defects,
    // and one version bump is the whole remediation.
    //
    // By module: bcprov 15 (3 critical, 10 high, 2 medium), bcpkix 6 (3 high, 3
    // medium), bcutil 1 (high). The three criticals are ALL bcprov:
    //   CVE-2026-58062         bcprov  a stapled OCSP response is accepted without
    //                                  being bound to the certificate being checked
    //   CVE-2026-8763          bcprov  Name Constraints bypass via a trailing dot in
    //   (GHSA-9pwp-9qqc-pr26)          an rfc822Name or URI, so an excluded subtree
    //                                  stops matching
    //   CVE-2026-59650         bcprov  the MTI/A0 DH agreement exponentiates the
    //                                  peer's value without validating it
    // The other 19 are high or medium: CVE-2026-12185, CVE-2026-12802,
    // CVE-2026-12803, CVE-2026-12816, CVE-2026-12860, CVE-2026-13506
    // (GHSA-qp49-qgx5-5m26), CVE-2026-13586, CVE-2026-14682, CVE-2026-15055,
    // CVE-2026-58059, CVE-2026-58060, CVE-2026-58061, CVE-2026-58063,
    // CVE-2026-59639, CVE-2026-59642, CVE-2026-59645 (bcutil), CVE-2026-59647 and
    // CVE-2026-59651. ⚠️ That is 18 ids for 19 advisories and the list is complete:
    // CVE-2026-13586 (PKCS#12 MAC and bag-decryption KDF iteration count) is reported
    // against bcprov AND bcpkix, so 22 package-advisory pairs are 21 distinct CVE ids.
    // Transcribed for grep value; the register is the Dependency Scanning report.
    //
    // ⚠️ The advisories this force was originally raised for are SUBSUMED and need
    // no separate entry: GHSA-c3fc-8qff-9hwx (bcprov, LDAP injection, affects
    // 1.81.1), GHSA-wg6q-6289-32hp (bcpkix, broken/risky crypto algo, affects 1.79)
    // and CVE-2025-14813 (GOST 28147-2015 CTR keystream repeat in
    // `G3413CTRBlockCipher`, fixed in 1.80.2 / 1.81.1 / 1.84.0). 1.85, and so 1.86, is
    // above every fix version all three name, so removing them from this comment removes
    // nothing from the force.
    //
    // ⛔ ALL THREE MODULES, ONE VERSION. The previous force covered bcprov only,
    // which left bcpkix AND bcutil resolving at 1.79, a split bouncycastle set that
    // is both a live finding (bcpkix) and an unsupported mix (BC modules are released
    // and tested as a set; bcutil is bcpkix's own dependency). Adding a fourth
    // artifact to this tree means adding it here in the same change, at the same
    // version. 1.85.2 exists for bcprov ONLY, which is exactly why it was never used:
    // it cannot be applied across the set, while 1.86 is published for all three. (Per
    // Maven Central's maven-metadata.xml, re-read on 2026-09-26: bcprov publishes 1.85,
    // 1.85.2 and 1.86; bcpkix and bcutil publish 1.85 and 1.86 with no 1.85.2 between them.)
    // The version itself is `bouncyCastle` in gradle/libs.versions.toml (read above this block).

    configurations.configureEach {
        resolutionStrategy {
            listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach {
                force("org.bouncycastle:$it:$bouncyCastleVersion")
            }

            // GHSA-j288-q9x7-2f5v: uncontrolled recursion on long inputs in
            // `RandomStringUtils`/`WordUtils`. Affects 3.16.0, fixed in 3.18.0.
            force("org.apache.commons:commons-lang3:$commonsLang3Version")

            // GHSA-7r82-7xv7-xcpj / CVE-2020-13956: HttpClient mishandles a malformed
            // authority component in a request URI, so a URI can be interpreted
            // against the wrong host. Affects 4.5.6, fixed in 4.5.13. Same 4.5.x
            // line, so binary-compatible for Lint's use of it.
            force("org.apache.httpcomponents:httpclient:4.5.14")

            // GHSA-735f-pc8j-v9w8 / CVE-2024-7254: unbounded recursion parsing a
            // deeply nested unknown-field group, so a malicious payload exhausts the
            // stack. Affects 3.22.0, fixed in 3.25.5.
            //
            // ⛔ javaLITE, NOT `protobuf-java`, AND THE DISTINCTION IS THE WHOLE
            // POINT. Both coordinates appear in the lockfiles and only one ships:
            // `protobuf-java` is already 3.25.5 and resolves ONLY onto
            // `androidLintTool`, a build-time classpath that never reaches a device.
            // `protobuf-javalite` is the one on `debugRuntimeClasspath` /
            // `releaseRuntimeClasspath` — via firebase-messaging — and it was the
            // version stuck at 3.22.0. Forcing the wrong one bumps a lint tool and
            // leaves the shipped app exactly as it was, while the dependency finding
            // disappears, which is worse than not fixing it.
            //
            // ⚠️ It resolves on `app` and `core-media` only, which is why Dependency
            // Scanning reported exactly two findings for one defect.
            //
            // ⚠️ 3.25.9, NOT 3.25.5, AND NOT 4.x. 3.25.5 fixed the CVE; 3.25.9 is the
            // newest 3.25 release and the runtime livekit-android 2.29.0 declares in its
            // own POM (as 2.28.2 did before it), so a 3.25.5 force would hold LiveKit below
            // its stated requirement.
            // A 4.x runtime under the 3.x code LiveKit and Firebase Messaging generate is
            // unproven (no unit test here parses a real LiveKit message), so majors are
            // ignored in .github/dependabot.yml.
            force("com.google.protobuf:protobuf-javalite:3.25.9")
        }
    }
}

// ── Regenerating the lockfiles ───────────────────────────────────────────────
//
//   ./gradlew :app:dependencies :core:core-auth:dependencies :core:core-data:dependencies \
//       :core:core-designsystem:dependencies :core:core-media:dependencies \
//       :core:core-model:dependencies :core:core-network:dependencies \
//       --write-locks --no-configuration-cache
//
// Commit every module's resulting gradle.lockfile. Run it whenever a
// version in gradle/libs.versions.toml changes; the build FAILS on a dependency that
// resolves to something the lockfile does not contain, which is the point. It compiles
// nothing: each module's `dependencies` report resolves EVERY resolvable configuration of that
// module, which is exactly the set `lockAllConfigurations()` above locks. About 80 seconds.
//
// ⛔ A CONFIGURATION THAT IS NOT RESOLVED KEEPS ITS PREVIOUS LOCK ENTRY, WITHOUT A WORD. That is
// why the command resolves everything rather than a chosen few. It used to be a four-task build
// (`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest lintDebug`), which locks only
// what those tasks happen to resolve. It never resolved `debugUnitTestLintChecksClasspath` or
// `debugAndroidTestLintChecksClasspath`, so after a version bump those two kept the old versions
// (fragment 1.1.0, navigation 2.9.8 and sentry-android-core 8.57.0 sat on them after one). Every
// gate stayed green because no gate resolves them, while a `dependencies` report printed 58 FAILED
// lines on them across four modules. The same trap waits for any configuration only one task resolves:
// resolving `androidLintTool` alone for :app once left `bcprov` on two lines, the new version for
// the lint tool and the old one for `debugUnitTest*`, the split set the bouncycastle note above
// forbids.
//
// ⛔ EVERY MODULE BY PATH, NEVER A BARE `dependencies`. `./gradlew dependencies --write-locks`
// looks like the same thing and locks NOTHING here: help tasks such as `dependencies` run only for
// the project in the working directory, which is the root, and the root locks nothing (the
// `dependencyLocking` block is inside `subprojects`). Measured: it rewrote no module lockfile. A
// module added to settings.gradle.kts must be added to the command above in the same change, or its
// lockfile is never written. build-logic is a separate build and locks nothing, so it has no entry.
//
// ⚠️ WHAT ITS FIRST RUN CHANGED, AND WHAT IT PRINTS. Run on a tree the four-task build had locked,
// it moved no dependency version. It added between 18 and 23 configurations per module to the
// `empty=` line: configurations that resolve to nothing (dependency-metadata, annotation-processor,
// androidTest utility and similar) and that no task had ever resolved, so they had no lock state at
// all. Gradle 9.8 also rewrites settings-gradle.lockfile without its "To regenerate" comment, since
// it writes that hint only for a project's lockfile. A second run changes nothing. The report
// prints FAILED for `androidx.compose.ui:ui-tooling` and `ui-test-manifest` under
// `debugImplementationDependenciesMetadata` in app, core-designsystem and core-media: that is the
// Kotlin plugin's IDE metadata configuration, which does not see the Compose BOM, so a BOM-versioned
// `debugImplementation` entry has no version there. No task resolves it, it holds no lock state, and
// it printed the same on the tree before this command existed. A FAILED under any other
// configuration is a real lock mismatch. `(n)` sections list declarations and resolve nothing.
//
// ⚠️ The one locked configuration the report does not list is AGP's `androidApis`, which holds no
// module dependency (it is locked as empty) and which every compile resolves, so a build still
// checks it.
//
// ⛔ DO NOT REINTRODUCE A CUSTOM `resolveAndLockAll` TASK THAT RESOLVES
// CONFIGURATIONS IN A TASK ACTION. It cannot work on Gradle 9: every resolve throws
// "Resolution of the configuration ':app:...' was attempted without an exclusive
// lock. This is unsafe and not allowed." Measured here — all 61 resolvable
// configurations failed that way.
//
// ⚠️ And the first version of that task wrapped each resolve in `runCatching { }`,
// discarding the result, so it printed BUILD SUCCESSFUL while locking NOTHING. A lock
// step that silently locks nothing is worse than none, because any dependency scan
// then passes against a manifest describing an empty build. If a helper task is ever
// added back, it must assert that it locked something.
//
// ⚠️ HOW A STALE LOCK SHOWS UP, WHICH IS RARELY WHERE IT IS. Two cases met while the command was
// still a task list, kept because the error text points somewhere else:
//
// ⛔ `koverVerify` resolves `:app:releaseRuntimeClasspath` too, so a dependency locked only on the
// debug lanes reds the COVERAGE gate rather than a release build, with "not part of the dependency
// lock state" attached to `:app:processReleaseNavigationResources`.
// `scripts/verify-release-minification.sh` needs the same lane.
//
// ⛔ AN AAR CAN SHIP ITS OWN LINT RULES, AND THEN THE FAILURE READS AS A CONFIGURATION-CACHE BUG.
// `livekit-android` carries a `lint.jar`, so `:app:debugLintChecksClasspath` resolves the SDK and
// its whole transitive set. With that classpath unlocked the build dies with "Configuration cache
// state could not be cached: field `__lintRuleJars__`" followed by a list of artifacts "not part of
// the dependency lock state", which points at the cache rather than at the missing lock.
//
// ⛔ LOCKING OVERRIDES THE VERSION CATALOG SILENTLY. It applies locked versions as
// `strictly` constraints rather than rejecting an out-of-lock request, so bumping a
// version in gradle/libs.versions.toml WITHOUT regenerating produces a green build
// that still uses the old version. Verified here: androidxCore set to 1.18.0 against a
// lockfile pinning 1.19.0 resolved as `androidx.core:core-ktx:1.18.0 -> 1.19.0` with no
// warning. A version bump and a lockfile regeneration are one change, never two.
