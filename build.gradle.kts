plugins {
    // AGP 9 compiles Kotlin itself: the separate org.jetbrains.kotlin.android plugin is no longer applied.
    id("com.android.application") version "9.5.0" apply false
}
