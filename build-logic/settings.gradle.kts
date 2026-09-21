// Included build providing this project's convention plugins.
//
// It is a SEPARATE build with its own settings so it can compile Kotlin against
// the Android and Kotlin Gradle plugins before the main build is configured. It
// reads the SAME version catalog as the main build, so a version bump happens in
// exactly one file.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
