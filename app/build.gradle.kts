import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the version: /version.properties
val appVersion: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("VERSION_NAME")

// 1.3.0 -> 10300, so every semver bump installs as an upgrade
val appVersionCode: Int = appVersion.split(".").map { it.toInt() }.let { (ma, mi, pa) -> ma * 10000 + mi * 100 + pa }

android {
    namespace = "sg.buscatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "sg.buscatch"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            // Permanent key so updates install over earlier versions.
            // The password comes from the KEYSTORE_PASSWORD GitHub secret (or your environment).
            storeFile = rootProject.file("signing/buscatch-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "buscatch"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildFeatures { buildConfig = true }
    // Token that can only create issues in this repo, for in-app feedback (repo secret FEEDBACK_TOKEN)
    defaultConfig { buildConfigField("String", "FEEDBACK_TOKEN", "\"${System.getenv("FEEDBACK_TOKEN") ?: ""}\"") }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_PASSWORD").isNullOrEmpty())
                signingConfigs.getByName("debug") // local builds without the password
            else signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
