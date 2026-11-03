import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tvhinput"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tvhinput"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // Lets JVM unit tests run code that logs through android.util.Log.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio decoders (MP2, AC3, E-AC3...) that most Android TV devices lack.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
