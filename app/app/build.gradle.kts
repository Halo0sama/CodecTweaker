plugins {
    id("com.android.application")
}

android {
    namespace = "com.lhdcprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lhdcprobe"
        minSdk = 26
        targetSdk = 37
        versionCode = 68
        versionName = "8.8"
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation(files(
        "libs/shizuku-api.jar",
        "libs/shizuku-provider.jar",
        "libs/shizuku-aidl.jar",
        "libs/shizuku-shared.jar",
        "libs/hab.jar"
    ))
}
