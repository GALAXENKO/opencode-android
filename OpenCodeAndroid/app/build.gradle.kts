import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. The keystore lives outside the repo (buildenv/keys) and is referenced
// through an ignored `keystore.properties`, so store passwords never reach git.
// RuStore rejects APKs signed with the standard debug certificate ("Р­С‚Рѕ РґРµР±Р°Рі-СЃР±РѕСЂРєР°"),
// therefore `assembleRelease` must be signed with this key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
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
        versionCode = 7
        versionName = "2.5.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v1 + v2: v2 is required from Android 7, v1 keeps very old devices working.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a keystore the build still works on a fresh clone, but the result is
            // debug-signed and will be rejected by RuStore - generate your own key first.
            signingConfig = if (keystorePropertiesFile.isFile) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint {
        // `targetSdk = 28` is deliberate (see defaultConfig): Android 10+ refuses to exec
        // binaries from the app's data dir for apps targeting API 29+, and the whole port
        // depends on running the bundled opencode CLI and CPython. RuStore accepts >= 28.
        // Without this the release build dies on lintVitalRelease with ExpiredTargetSdkVersion.
        disable += "ExpiredTargetSdkVersion"
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
