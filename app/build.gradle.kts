plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.reitojike.thinkcanvas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reitojike.thinkcanvas"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room3:room3-runtime:3.0.3")
    implementation("androidx.sqlite:sqlite-framework:2.7.1")
    ksp("androidx.room3:room3-compiler:3.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.1")
}
