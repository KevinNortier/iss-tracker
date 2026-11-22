import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: read from the git-ignored keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without that file the release build is unsigned,
// so anyone can still build the project.
val signingProperties: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// The app version: change only this. versionCode is derived from it as
// MAJOR * 10000 + MINOR * 100 + PATCH (1.0.0 -> 10000, 1.2.3 -> 10203), so it always increases.
val appVersion = "1.1.0"
val appVersionCode: Int = run {
    val parts = appVersion.split('.').map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it in 0..99 } && parts[0]!! >= 1) {
        "appVersion must be MAJOR.MINOR.PATCH with MAJOR >= 1 and MINOR/PATCH 0..99, was \"$appVersion\""
    }
    parts[0]!! * 10000 + parts[1]!! * 100 + parts[2]!!
}

// APK file names: iss-tracker-1.0.0-release.apk / iss-tracker-1.0.0-debug.apk
base {
    archivesName.set("iss-tracker-$appVersion")
}

android {
    namespace = "io.github.kevinnortier.isstracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.kevinnortier.isstracker"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds install next to the release app instead of replacing it
            // (the app name comes from src/debug/res/values/strings.xml).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrink and optimise the whole app with R8. (Limiting it to library packages with
            // packageScope crashed at startup: an optimised Kotlin class became inaccessible to its users.)
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}