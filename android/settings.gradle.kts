pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "paladex-ex"

include(":core")

/*
 * The :app module needs the Android SDK and Google's Maven repo; :core is plain
 * Kotlin and needs neither. Including :app unconditionally would make even
 * `gradle :core:test` fail on a machine without the SDK, so it is added only
 * when an SDK is actually present.
 *
 * Android Studio always writes sdk.dir into local.properties, so this is
 * transparent there. It also means CI can run the core test suite on a bare JDK.
 */
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — configuring :core only. Open in Android Studio to build the app.")
}
