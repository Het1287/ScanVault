plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
}

android {
    namespace = "com.example.ownscan"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ownscan"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // --- FIX FOR META-INF/DEPENDENCIES COLLISION ---
    packaging {
        resources {
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.kotlin_module"
        }
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
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Reorderable LazyGrid / LazyList
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Coil Image Loading with size hints
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Google Play Services Auth
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Google API Client & Drive REST API v3
    implementation("com.google.api-client:google-api-client-android:2.4.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240123-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.44.1")

    // Biometric & Device Credential Authentication (Phase 5)
    implementation("androidx.biometric:biometric:1.1.0")

    // Apache-2.0 Password-Protected PDF generation (Phase 5)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Jetpack Glance AppWidget (Phase 6)
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")
}