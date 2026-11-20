import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.metro.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.metro.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Personal build: signed with the local debug key so it installs over the debug build
            // (same signature, settings kept). Use a real release key before distributing.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    // Compose: foundation only. No Material: every Metro surface is drawn by hand.
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Spring physics (tilt now, lockscreen curtain in Phase 3).
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
