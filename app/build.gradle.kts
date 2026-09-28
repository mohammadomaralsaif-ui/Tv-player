plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvplayer.app"
        minSdk = 21
        targetSdk = 35
        // Every CI build gets a higher version so it installs as an update.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = build
        versionName = "1.$build"
        // Where the app looks for updates (GitHub releases of this repository).
        buildConfigField("String", "UPDATE_REPO", "\"${System.getenv("GITHUB_REPOSITORY") ?: "mohammadomaralsaif-ui/Tv-player"}\"")
    }

    // Permanent release key. The keystore file is encrypted with SIGNING_PASSWORD,
    // which lives only in the GitHub repository secrets.
    val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("release") {
            if (signingPassword != null) {
                storeFile = file("release.jks")
                storePassword = signingPassword
                keyAlias = "tvplayer"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingPassword != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Chromecast: send the video to a TV
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
}
