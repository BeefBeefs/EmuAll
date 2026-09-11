import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val dreamcastAssetsDir = layout.buildDirectory.dir("generated/dreamcast-assets")

tasks.register<Copy>("prepareDreamcastBiosAssets") {
    from(rootProject.file("dc_boot.bin")) { into("dreamcast") }
    from(rootProject.file("dc_flash.bin")) { into("dreamcast") }
    into(dreamcastAssetsDir)
}

android {
    namespace = "com.beefbeefs.emuall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.beefbeefs.emuall"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.8.9"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The frontend opens each libretro core with dlopen(RTLD_LOCAL). Keep the
    // packaged core DSOs as real files in nativeLibraryDir so that absolute
    // paths work on Android devices instead of only existing inside the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].assets.srcDir(dreamcastAssetsDir)

    buildTypes {
        debug {
            val stableDebugKey = rootProject.file("ci/emuall-debug.keystore")
            if (stableDebugKey.isFile) {
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = stableDebugKey
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        release {
            isMinifyEnabled = true
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
}

tasks.named("preBuild").configure { dependsOn("prepareDreamcastBiosAssets") }

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
}
