plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.topeck.omniwheel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.topeck.omniwheel"
        minSdk = 21
        targetSdk = 35
        versionCode = 31
        versionName = "0.9.14"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Secrets come from the environment (local shell or CI secrets).
            // They were previously hardcoded in this file, which leaked them
            // to the public repo. The fallbacks keep old local builds working
            // until you rotate the keystore.
            storeFile = file(System.getenv("OMNIWHEEL_STORE_FILE") ?: "omniwheel-release.jks")
            storePassword = System.getenv("OMNIWHEEL_STORE_PASSWORD") ?: "omniwheel123"
            keyAlias = System.getenv("OMNIWHEEL_KEY_ALIAS") ?: "omniwheel"
            keyPassword = System.getenv("OMNIWHEEL_KEY_PASSWORD")
                ?: System.getenv("OMNIWHEEL_STORE_PASSWORD") ?: "omniwheel123"
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}