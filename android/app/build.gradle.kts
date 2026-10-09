plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "io.github.veritasx1.lichat"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.veritasx1.lichat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        // The test build lives next to a real LiChat (Michelle Z5 c): own package and name.
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            manifestPlaceholders["appLabel"] = "LiChat Test"
        }
        release {
            manifestPlaceholders["appLabel"] = "LiChat"
            signingConfig = signingConfigs.getByName("debug")   // until Olaf's release key exists
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The core for this computer (JNA finds liblichat_core.so there): cargo build --release --features mobile
            it.systemProperty("jna.library.path", rootProject.file("../core/target/release").absolutePath)
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("lichat.server", rootProject.file("../server").absolutePath)
            (project.findProperty("shots") as String?)?.let { folder -> it.systemProperty("lichat.shots", folder) }
        }
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // The Rust core through uniffi (JNA calls into liblichat_core.so).
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
