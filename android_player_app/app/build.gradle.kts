plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val defaultApiBaseUrls = "http://192.168.1.98:3000;http://apontitv.com"
val configuredApiBaseUrls =
    providers.gradleProperty("APONTI_API_BASE_URLS").orNull
        ?: providers.environmentVariable("APONTI_API_BASE_URLS").orNull
        ?: defaultApiBaseUrls
val escapedApiBaseUrls = configuredApiBaseUrls
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "br.com.softextv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.softextv.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URLS", "\"$escapedApiBaseUrls\"")

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("dev.mobile:dadb:1.2.6")
}
