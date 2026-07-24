plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// SPEC §9: versionCode/versionName injected by CI via -PversionCode= / -PversionName=
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val ciVersionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"

android {
    namespace = "com.fingerly.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fingerly"
        minSdk = 34
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("release") {
            // Keystore decoded from the KEYSTORE_B64 secret in CI (SPEC §9).
            val ks = rootProject.file("keystore.jks")
            if (ks.exists()) {
                // trim(): secrets pasted on mobile can pick up stray whitespace/newlines
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")?.trim()
                keyAlias = System.getenv("KEY_ALIAS")?.trim()
                keyPassword = System.getenv("KEY_PASSWORD")?.trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fall back to debug signing until the release keystore secrets exist,
            // so the Releases-page install loop works from day one.
            signingConfig = if (rootProject.file("keystore.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
