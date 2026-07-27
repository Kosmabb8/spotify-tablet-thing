plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.carthingspotify.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.carthingspotify.controller"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStore = providers.gradleProperty("CARTHING_KEYSTORE").orNull
    val releaseAlias = providers.gradleProperty("CARTHING_KEY_ALIAS").orNull
    val releaseStorePassword = providers.gradleProperty("CARTHING_STORE_PASSWORD")
        .orElse(providers.environmentVariable("CARTHING_STORE_PASSWORD")).orNull
    val releaseKeyPassword = providers.gradleProperty("CARTHING_KEY_PASSWORD")
        .orElse(providers.environmentVariable("CARTHING_KEY_PASSWORD")).orNull
    signingConfigs {
        if (listOf(releaseStore, releaseAlias, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseStore!!)
                keyAlias = releaseAlias!!
                storePassword = releaseStorePassword!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
