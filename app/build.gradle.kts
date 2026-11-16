import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// The key that signs the release APK lives outside the repository: in ~/.config/devx-android/signing.properties (storeFile,
// storePassword, keyAlias, keyPassword), or in the file named by DEVX_SIGNING_PROPERTIES, or in keystore.properties here (not
// committed). Without any of them the release is signed with the debug key, which is fine for trying it out but not for sharing.
val signing = Properties().apply {
    val file = System.getenv("DEVX_SIGNING_PROPERTIES")?.let { file(it) }
        ?: file("${System.getProperty("user.home")}/.config/devx-android/signing.properties").takeIf { it.exists() }
        ?: rootProject.file("keystore.properties").takeIf { it.exists() }
    if (file != null && file.exists()) file.inputStream().use { load(it) }
}
val releaseKey = signing.getProperty("storeFile")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.apvcode.devx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apvcode.devx"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.3.0"
        // Python (Chaquopy) comes for the two kinds of processor that matter: phones and the emulator.
        ndk { abiFilters += ((project.findProperty("abis") as String?) ?: "arm64-v8a,x86_64").split(",") }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = releaseKey
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Python (Chaquopy). Packages with native code only exist for 3.13, so that is the version; the build needs a Python 3.13
// of its own on the computer: put its path as python313=... in local.properties (or have python3.13 on the PATH).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(localProperties.getProperty("python313") ?: "python3.13")
        pip {
            // Offline or without working name lookup: -PpipLocal=<folder with the downloaded wheels> installs from there.
            (project.findProperty("pipLocal") as String?)?.let { options("--no-index", "--find-links", it) }
            // What the document and picture skills use: pictures, XML, tables, Word, PowerPoint, Excel and PDF files.
            install("pillow")
            install("numpy")
            install("lxml")
            install("defusedxml")
            install("openpyxl")
            install("XlsxWriter")
            install("python-docx")
            install("python-pptx")
            install("pypdf")
            install("reportlab")
            install("imageio")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Haze pulls newer activity builds that need compileSdk 36; keep the ones this project was built with.
    constraints {
        implementation("androidx.activity:activity:1.10.1") { version { strictly("1.10.1") } }
        implementation("androidx.activity:activity-ktx:1.10.1") { version { strictly("1.10.1") } }
        implementation("androidx.activity:activity-compose:1.10.1") { version { strictly("1.10.1") } }
    }
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.documentfile:documentfile:1.0.0")
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.greek)
    implementation(libs.haze)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network)
    implementation(libs.pdfbox.android)
    implementation(libs.jsoup)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.apksig)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
}
