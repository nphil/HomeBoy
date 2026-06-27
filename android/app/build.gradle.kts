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
        versionCode = 24
        versionName = "1.0.23"
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

    // The transitive com.qualcomm.qti:qnn-runtime ships HTP backends for every Hexagon
    // generation (V68–V81) plus DSP/GPU backends — ~200 MB of native libs. We only target
    // the Snapdragon 8 Elite (Hexagon V79), so keep libQnnHtp.so + the V79 skel/stub +
    // System + Prepare (needed for on-device graph compile) and drop the other Hexagon
    // generations. We DO keep libQnnGpu.so so float models that the NPU can't run fall back
    // to the Adreno GPU instead of all the way to CPU. Other SoCs simply use CPU.
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libQnnHtpV68Skel.so", "**/libQnnHtpV68Stub.so",
                "**/libQnnHtpV69Skel.so", "**/libQnnHtpV69Stub.so",
                "**/libQnnHtpV73Skel.so", "**/libQnnHtpV73Stub.so",
                "**/libQnnHtpV75Skel.so", "**/libQnnHtpV75Stub.so",
                "**/libQnnHtpV81Skel.so", "**/libQnnHtpV81Stub.so",
                "**/libQnnDsp.so", "**/libQnnDspV66Skel.so", "**/libQnnDspV66Stub.so"
            )
        }
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
    implementation(libs.onnxruntime.android.qnn)
    debugImplementation(libs.compose.ui.tooling)
}
