/*
 * Only the plugins :core needs are declared here. The Android plugins are
 * declared in app/build.gradle.kts instead — naming them at the root would
 * force Gradle to resolve the Android Gradle Plugin even when :app is excluded,
 * which defeats the point of being able to build and test :core on a plain JDK.
 */
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}
