plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.predix.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.predix.app"
        minSdk        = 21
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"

        // Store your Gemini API key here — never hardcode in source
        buildConfigField("String", "GEMINI_API_KEY", "\"YOUR_GEMINI_API_KEY_HERE\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AppCompat — required for AppCompatActivity
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Activity KTX — required for registerForActivityResult
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.13.1")

    // Fragment KTX
    implementation("androidx.fragment:fragment-ktx:1.8.1")

    // Material
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Google Gemini SDK — for AI report analysis
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Coroutines — required for Gemini async calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Lifecycle + ViewModel — for coroutine scope
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // OkHttp — for image loading from URI
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
