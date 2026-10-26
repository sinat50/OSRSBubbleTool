plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sinat.osrsbubbletool"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sinat.osrsbubbletool"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // the real JSON reader, for PC tests only (Android's own isn't there)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}