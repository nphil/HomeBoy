plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Native libs (libllmkit + libllama + libggml* + libggml-hexagon + libggml-htp-v* + libggml-opencl)
// are PREBUILT in WSL2 and committed under src/main/jniLibs/arm64-v8a — the Hexagon NPU backend can't
// be cross-compiled from Windows or CI (no Hexagon SDK there). To rebuild them, see
// docs/llmkit-framework-plan.md and the build scripts. The Kotlin API (LlmKit + Backend) is here.
android {
    namespace = "com.homeboy.llmkit"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk {
            // Device is arm64 only; the committed jniLibs only contain arm64-v8a.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // llmkit + ggml may each ship the shared C++ runtime; keep one.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    // Pure JNI + prebuilt-native module — no Android library dependencies.
}
