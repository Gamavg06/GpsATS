import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ats.gps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ats.gps"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("signing.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                FileInputStream(keystorePropertiesFile).use { fis ->
                    keystoreProperties.load(fis)
                }
            }

            val keystorePath = System.getenv("ATS_KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
                ?: "../release.keystore"
            val storePass = System.getenv("ATS_KEYSTORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
                ?: "atsgpspassword123"
            val keyAl = System.getenv("ATS_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
                ?: "atsgps"
            val keyPass = System.getenv("ATS_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")
                ?: "atsgpspassword123"

            val keystoreFile = file(keystorePath)
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Red y WebSocket (OkHttp + Coroutines)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
