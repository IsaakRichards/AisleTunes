plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: uses repository secrets when they are set (see README),
// otherwise the bundled project keystore so every build can update the last one.
val envStore = System.getenv("AISLETUNES_KEYSTORE_FILE")

android {
    namespace = "com.isaakrichards.aisletunes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.isaakrichards.aisletunes"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (!envStore.isNullOrBlank()) {
                storeFile = file(envStore)
                storePassword = System.getenv("AISLETUNES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AISLETUNES_KEY_ALIAS")
                keyPassword = System.getenv("AISLETUNES_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/aisletunes.keystore")
                storePassword = "aisletunes"
                keyAlias = "aisletunes"
                keyPassword = "aisletunes"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
}
