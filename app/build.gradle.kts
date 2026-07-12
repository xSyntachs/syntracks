plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.xsyntachs.tiktoksongs"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.xsyntachs.tiktoksongs"
        minSdk = 26
        targetSdk = 36
        // Releases baut die GitHub-Pipeline und setzt die Version aus der Run-Nummer,
        // die Fallbacks hier gelten nur für lokale Verify-Builds
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 30
        versionName = System.getenv("VERSION_NAME") ?: "4.5-lokal"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "tiktoksongs-2026"
            keyAlias = "tiktoksongs"
            keyPassword = "tiktoksongs-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
}
