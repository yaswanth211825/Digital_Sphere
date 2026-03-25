plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)           // reads google-services.json
    alias(libs.plugins.firebase.appdistribution)  // enables appDistributionUploadDebug task
}

android {
    namespace = "com.example.digitalsphere"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.digitalsphere"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Firebase App Distribution config — runs on every debug upload
            firebaseAppDistribution {
                releaseNotes        = "Latest build — BLE attendance fixes applied."
                // Add tester emails here, comma-separated.
                // They get an email with a download link every time you upload.
                testers             = "add-tester@gmail.com"
                // Optional: create a group in Firebase Console and use it instead
                // testerGroups = "qa-team"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}