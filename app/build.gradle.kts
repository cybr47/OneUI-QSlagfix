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
        versionCode = 7
        versionName = "2.3.0-native-drag"
    }

    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            // R8 is intentionally OFF. The only BuildConfig.DEBUG uses are log statements, so R8 (shrink,
            // obfuscate, optimize) was the one build step that changed the module's executed code between
            // the debug APK, where the panel gesture worked, and the release APK, where it did not.
            // The root cause inside R8 was not isolated (no Android build tooling was reachable when
            // this was fixed), so release now compiles through the same D8 path as debug instead.
            // The module is a few kilobytes: minifying saves nothing. Release stays non-debuggable and
            // signed with the same key; only the verbose BuildConfig.DEBUG logging is inactive.
            isMinifyEnabled = false
            isShrinkResources = false
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
