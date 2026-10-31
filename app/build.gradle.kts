plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.samsung.music.qs.lagfix"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.samsung.music.qs.lagfix"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "2.1.1-diagnostic"
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    testImplementation("junit:junit:4.13.2")
}
