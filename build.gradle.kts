plugins {
    // AGP 9 compiles Kotlin itself: the separate org.jetbrains.kotlin.android plugin is no longer applied.
    id("com.android.application") version "9.5.0" apply false
    // Must match the Kotlin version AGP 9.5 compiles with (2.2.10, see `gradlew buildEnvironment`).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
