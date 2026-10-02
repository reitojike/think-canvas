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
    namespace = "com.thinkcanvas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.thinkcanvas.internal"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.environmentVariable("THINKCANVAS_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("THINKCANVAS_VERSION_NAME").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel7Api37") {
                    device = "Pixel 7"
                    apiLevel = 37
                    systemImageSource = "google"
                    require64Bit = true
                }
                create("pixel9Api37") {
                    device = "Pixel 9"
                    apiLevel = 37
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }

    signingConfigs {
        create("internal") {
            providers.environmentVariable("THINKCANVAS_INTERNAL_KEYSTORE_FILE").orNull?.let {
                storeFile = file(it)
            }
            storePassword = providers.environmentVariable("THINKCANVAS_INTERNAL_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("THINKCANVAS_INTERNAL_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("THINKCANVAS_INTERNAL_KEY_PASSWORD").orNull
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
        create("internal") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("internal")
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
    implementation("androidx.core:core:1.19.1")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("androidx.room3:room3-runtime:3.0.3")
    implementation("androidx.sqlite:sqlite-framework:2.7.1")
    ksp("androidx.room3:room3-compiler:3.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
