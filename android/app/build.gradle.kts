import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sharedDir = rootProject.file("../shared")

android {
    namespace = "com.halworks.basicart"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.halworks.basicart"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The only native code is Compose's tiny libandroidx.graphics.path.so.
        // -Pabi=64 → arm64-v8a + x86_64 APK, -Pabi=32 → armeabi-v7a APK; default (AAB) = all.
        when (project.findProperty("abi")?.toString()) {
            "64" -> ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            "32" -> ndk { abiFilters += listOf("armeabi-v7a") }
            else -> ndk { abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") }
        }
    }

    sourceSets {
        getByName("main") {
            // Bundled data is used straight from ../shared (never copied into android/).
            // Each folder's contents land at the root of the APK's assets/.
            assets.srcDirs(
                File(sharedDir, "fonts"),
                File(sharedDir, "palettes"),
                File(sharedDir, "presets"),
            )
        }
        getByName("androidTest") {
            assets.srcDirs(File(sharedDir, "fixtures"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed for now (store signing is configured before release).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("ttf", "otf", "json")
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("basicart.shared", sharedDir.absolutePath)
            // Cross-platform exchange: -Pxplat=<dir> writes <dir>/android-out and reads <dir>/ios-out.
            project.findProperty("xplat")?.let { d -> it.systemProperty("basicart.xplat", d.toString()) }
            it.outputs.upToDateWhen { project.findProperty("xplat") == null }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-process:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Installs the baseline profiles (ours + Compose's) for sideloaded and store installs.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Device guard: tasks that talk to devices (connected*, install*, uninstall*) target every attached
// device unless pinned, so ANDROID_SERIAL must be set. An optional allow-list can be kept in the
// (git-ignored) local.properties: basicart.allowedDevices=serial1,serial2
val allowedDevices: Set<String> = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }.getProperty("basicart.allowedDevices")
}?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
gradle.taskGraph.whenReady {
    val deviceTasks = allTasks.filter { t ->
        t.project == project && listOf("connected", "install", "uninstall", "deviceCheck").any { t.name.startsWith(it) }
    }
    if (deviceTasks.isNotEmpty()) {
        val names = deviceTasks.joinToString { it.name }
        val serial = System.getenv("ANDROID_SERIAL")
        if (serial.isNullOrBlank()) {
            throw GradleException("Refusing to run $names: set ANDROID_SERIAL to the device to use. Device tasks must never target all attached devices.")
        }
        if (allowedDevices.isNotEmpty() && serial !in allowedDevices) {
            throw GradleException("Refusing to run $names on '$serial': not in basicart.allowedDevices $allowedDevices (local.properties).")
        }
    }
}
