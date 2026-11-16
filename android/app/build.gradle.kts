plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.larmstrong1127.wristfeed"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.larmstrong1127.wristfeed"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
    // VERIFY: Kotlin 1.9.24 + Compose compiler 1.5.14 + wear compose 1.3.1 (Compose UI 1.6) resolve together.
    composeOptions {
        // Compose compiler 1.5.14 is the one paired with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(project(":core"))

    // Tiles + ProtoLayout (stable).
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.0")
    // TileService returns Guava ListenableFuture.
    implementation("com.google.guava:guava:33.2.1-android")

    // Complications data source with coroutine-friendly SuspendingComplicationDataSourceService.
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Background refresh.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Open tap_url on the paired phone.
    implementation("androidx.wear:wear-remote-interactions:1.0.0")
    // RemoteInput helper for the "add feed" text entry.
    implementation("androidx.wear:wear-input:1.1.0")

    // Compose for Wear OS.
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
