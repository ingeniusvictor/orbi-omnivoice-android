import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableDebugKeystore = layout.buildDirectory.file("orbi-debug.keystore").get().asFile
val stableDebugKeystoreB64 = rootProject.file("ci/orbi-debug.keystore.b64")
if (stableDebugKeystoreB64.isFile) {
    stableDebugKeystore.parentFile.mkdirs()
    stableDebugKeystore.writeBytes(
        Base64.getMimeDecoder().decode(stableDebugKeystoreB64.readText().trim())
    )
}

android {
    namespace = "com.orbiecosystem.omnivoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbiecosystem.omnivoice.edgelab"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.2-public-wav-export"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("orbiStableDebug") {
            storeFile = stableDebugKeystore
            storePassword = "android"
            keyAlias = "orbidebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("orbiStableDebug")
        }
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

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.zhufucdev.hgtk:core:0.1.1")
}
