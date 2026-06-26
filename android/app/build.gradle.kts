plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.homeboy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.homeboy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.0.18"
    }

    // Permanent self-signed key committed to the repo so every CI build has an
    // identical signature — required for in-place updates (Obtainium/adb) without
    // signature-mismatch errors. Sideload-only app; key secrecy is not a goal.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore.jks")
            storePassword = "homeboy-release"
            keyAlias = "homeboy"
            keyPassword = "homeboy-release"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.material3.adaptive.navigation.suite)
    implementation(libs.material3.adaptive)
    implementation(libs.material3.adaptive.layout)
    implementation(libs.material3.adaptive.navigation)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
