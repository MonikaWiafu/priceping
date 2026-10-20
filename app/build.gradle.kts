plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: the GitHub build passes your own key through these environment variables
// (set the KEYSTORE_BASE64 and KEYSTORE_PASSWORD repository secrets). Without them the
// APK is signed with a temporary debug key, which still installs fine.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.priceping"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.priceping"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "priceping"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
}
