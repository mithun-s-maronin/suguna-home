plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.suguna.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suguna.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.0"
        // Firebase config lives in Config.kt (public web key + DB URL, safe to ship).
        // No build-time secret — the agent authenticates with anonymous Firebase Auth.
    }

    // Same stable key as the fork so future agent updates install in place.
    signingConfigs {
        create("stable") {
            storeFile = file("../app/suguna.keystore")
            storePassword = "sugunahome"
            keyAlias = "suguna"
            keyPassword = "sugunahome"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("stable") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
}
