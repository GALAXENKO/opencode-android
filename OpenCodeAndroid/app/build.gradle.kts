plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.opencode.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.opencode.mobile"
        minSdk = 26
        // 28 is deliberate, not stale: Android 10+ denies `execve` of files inside the
        // app's private data dir for apps targeting API 29+, which would make the bundled
        // Python runtime (and every `pip`-installed console script) unrunnable. Termux
        // pins the same value for the same reason.
        targetSdk = 28
        versionCode = 5
        versionName = "2.3.3"
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

    // The bundled opencode server is an aarch64-musl ELF that is dynamically linked
    // against musl + libstdc++. It must be extracted to disk (not mmapped from the APK)
    // so it can be exec'd, and the musl loader / C++ runtime ship next to it.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Never strip the loader or the C++ runtime: `strip` mangles ELF files that are
            // meant to be loaded by musl's own dynamic loader.
            keepDebugSymbols += listOf("**/libopencode.so", "**/ld-musl*", "**/libstdc*", "**/libgcc*")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += "pem"
        // `python.zip` is extracted by PythonRuntime on first boot; leaving it stored keeps the
        // APK build fast and lets ZipInputStream stream straight out of the asset.
        noCompress += "zip"
    }

    sourceSets {
        // `-PwithX86` also packages an x86_64 build of the CLI (src/x86/jniLibs) so the
        // whole server can be smoke-tested on an x86_64 emulator. Plain builds ship arm64 only.
        if (project.hasProperty("withX86")) {
            getByName("main").jniLibs.srcDir("src/x86/jniLibs")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
