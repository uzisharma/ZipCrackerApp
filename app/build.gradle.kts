plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias { libs.plugins.compose.compiler }
}

android {
    namespace = "com.example.zipcrackerapp"

    compileSdk = 36 // or 35; but use a real released SDK, not a fake "release(36)"

    defaultConfig {
        applicationId = "com.example.zipcrackerapp"
        minSdk = 26   // 34 is way too high; but choose what you really need
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
    }

    buildFeatures {
        compose = true
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"

    }
}

//kotlin {
//    jvmToolchain(21)
//}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)   // <-- activity-compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    // (optional but recommended)
    // debugImplementation("androidx.compose.ui:ui-tooling")
    // debugImplementation("androidx.compose.ui:ui-tooling-preview")

    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Zip4j
    implementation(libs.zip4j)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}



//dependencies {
//    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.appcompat)
//    implementation(libs.material)
//    implementation(libs.androidx.activity)
//    implementation(libs.androidx.constraintlayout)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//
//    // Coroutines
//
//    implementation(libs.kotlinx.coroutines.android)
//    implementation(libs.kotlinx.coroutines.core)
//
//    // Zip4j for ZIP handling
//    implementation(libs.zip4j)
//}