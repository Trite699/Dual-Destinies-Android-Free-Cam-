plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freecam.loader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.freecam.loader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    // frida-inject and hook.js are large/binary or environment-specific;
    // they're fetched by the CI workflow before the build (see
    // .github/workflows/build.yml) and dropped into src/main/assets/.
    // If you're building locally, run scripts/fetch-frida-inject.sh first.
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Shizuku: lets the app run privileged commands (needed to launch
    // frida-inject) via the Shizuku service instead of a raw `su` shell.
    // The user must have the Shizuku app installed and the service started
    // (either via ADB pairing on an unrooted device, or from root on a
    // rooted one) — see README.md.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
