plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.suguna.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suguna.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 40
        versionName = "2.0"
        // Firebase config now lives in AppConfig (public web key + DB URL, safe to ship).
        // No build-time secret injection — the app authenticates with anonymous Firebase Auth.
    }

    // Stable signing key so every build has the same signature → tablets can be updated in place
    // (without this, each cloud build would be signed differently and need a wipe to update).
    signingConfigs {
        create("stable") {
            storeFile = file("suguna.keystore")
            storePassword = "sugunahome"
            keyAlias = "suguna"
            keyPassword = "sugunahome"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No extra libraries: the app uses only the Android framework + Kotlin stdlib.
dependencies {
}
