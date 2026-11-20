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
        versionCode = 37
        versionName = "1.6"

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
