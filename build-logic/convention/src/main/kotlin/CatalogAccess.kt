import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * Version-catalog access for the convention plugins.
 *
 * ⛔ WHY NOT THE GENERATED `libs.` ACCESSOR. The type-safe accessor
 * (`org.gradle.accessors.dm.LibrariesForLibs`) is generated for a build's own build
 * scripts and is NOT placed on the compile classpath of a project containing
 * precompiled script plugins. Importing it here fails with
 * `Unresolved reference 'accessors'`, and every catalog lookup in the file then
 * fails too — one missing classpath entry presenting as a dozen unrelated errors.
 *
 * The widely-copied workaround is
 * `compileOnly(files(libs.javaClass.superclass.protectionDomain.codeSource.location))`,
 * which reaches through a generated class's protection domain to find a jar path.
 * It works until Gradle changes how those accessors are packaged, and then it fails
 * at configuration time with nothing pointing at the cause. Not worth it for a file
 * every future module depends on.
 *
 * `VersionCatalogsExtension` is the supported API and needs no classpath surgery.
 * The cost is string keys instead of compile-time checking, which is why the helpers
 * below throw with the offending key and the file to fix rather than returning an
 * empty Optional that surfaces later as a mysterious missing dependency.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * A version by catalog key, e.g. `version("compileSdk")`.
 *
 * `requiredVersion` rather than `displayName`: for a plain pinned version like
 * "36" they read the same, but for a rich version they do not, and silently
 * feeding a range string into `toInt()` would be a confusing failure.
 */
internal fun VersionCatalog.version(key: String): String =
    findVersion(key)
        .orElseThrow {
            IllegalStateException(
                "No version '$key' in gradle/libs.versions.toml. " +
                    "Catalog keys are referenced as strings from the convention plugins, " +
                    "so a rename there must be mirrored in build-logic/.",
            )
        }
        .requiredVersion

/** Same as [version], parsed as an SDK level. */
internal fun VersionCatalog.intVersion(key: String): Int =
    version(key).toIntOrNull()
        ?: error("Version '$key' in gradle/libs.versions.toml must be an integer, was '${version(key)}'")

/**
 * A library by catalog key. ⚠️ The key uses DASHES as in the TOML
 * (`androidx-compose-ui`), not the dots of the generated accessor
 * (`androidx.compose.ui`). Passing the dotted form throws here rather than
 * resolving to nothing.
 */
internal fun VersionCatalog.library(key: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(key)
        .orElseThrow {
            IllegalStateException(
                "No library '$key' in gradle/libs.versions.toml. " +
                    "Use the dashed TOML key (androidx-compose-ui), not the dotted accessor form.",
            )
        }
