plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.avoska"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.avoska"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    packaging {
        jniLibs {
            // Stored uncompressed and 16 KB-aligned in the APK, loaded in place (16 KB page support).
            useLegacyPackaging = false
        }
    }
}

// The gomobile bridge (engine/bridge) is built from source into a gitignored AAR; never committed.
// Needs ANDROID_NDK_HOME (see dev/build-bridge.sh). Up to date while its Go inputs are unchanged.
val bridgeAar = rootProject.layout.projectDirectory.file(".build/bridge/bridge.aar")
val buildBridge = tasks.register<Exec>("buildBridge") {
    description = "Builds the Syncthing bridge AAR with gomobile (dev/build-bridge.sh)."
    val root = rootProject.layout.projectDirectory
    inputs.files(root.file("dev/build-bridge.sh"), root.file("engine/go.mod"), root.file("engine/go.sum"))
    inputs.dir(root.dir("engine/bridge"))
    outputs.file(bridgeAar)
    commandLine("bash", root.file("dev/build-bridge.sh").asFile.absolutePath)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.qrcodegen)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(files(bridgeAar).builtBy(buildBridge))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
