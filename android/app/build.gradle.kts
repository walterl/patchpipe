plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.patchpipe.app"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "dev.patchpipe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
    }
    sourceSets {
        getByName("main") {
            java.srcDir("${rootDir}/bindings")
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += listOf("**/libpatchpipe_core.so")
        }
    }
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    val crateDir = rootDir.resolve("..")
    val jniOut = layout.projectDirectory.dir("src/main/jniLibs")
    workingDir = crateDir
    environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    val cargoBin = System.getProperty("user.home") + "/.cargo/bin"
    environment(
        "PATH",
        cargoBin + ":" + (System.getenv("PATH") ?: ""),
    )
    commandLine(
        "cargo",
        "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", jniOut.asFile.absolutePath,
        "build",
        "--release",
        "-p", "patchpipe-core",
    )
    inputs.files(
        fileTree(crateDir.resolve("patchpipe-core")) {
            include("**/*.rs", "Cargo.toml", "uniffi.toml")
        },
        crateDir.resolve("Cargo.lock"),
    )
    outputs.dir(jniOut)
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
