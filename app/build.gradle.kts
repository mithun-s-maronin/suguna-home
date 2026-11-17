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
        versionCode = 35
        versionName = "1.5"

        // Firebase Realtime Database config for the remote-admin agent. Injected at build time via
        // -PDB_URL / -PDB_SECRET (or env), so the secret is never committed to the repo.
        val dbUrl = (project.findProperty("DB_URL") as String?) ?: System.getenv("DB_URL") ?: ""
        val dbSecret = (project.findProperty("DB_SECRET") as String?) ?: System.getenv("DB_SECRET") ?: ""
        buildConfigField("String", "FIREBASE_DB_URL", "\"$dbUrl\"")
        buildConfigField("String", "FIREBASE_DB_SECRET", "\"$dbSecret\"")
    }

    buildFeatures {
        buildConfig = true
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No extra libraries: the app uses only the Android framework + Kotlin stdlib.
dependencies {
}
