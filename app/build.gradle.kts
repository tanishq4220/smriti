plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smriti.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smriti.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
        // litertlm-android 0.17.0 ships Kotlin 2.4.0 metadata. KSP (required by Room)
        // tops out at 2.3.11, so Kotlin cannot be bumped to 2.4 here. This tells the
        // 2.0.21 compiler to read the newer metadata anyway.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The only LLM runtime: loads the .litertlm Gemma file AI Edge Gallery uses.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")

    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    implementation("com.google.code.gson:gson:2.11.0")

    // On-device text embeddings for offline recall (brute-force cosine, no vector DB).
    implementation("com.google.mediapipe:tasks-text:0.10.35")

    // Vosk provides fully offline speech recognition and requires no NDK build.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    testImplementation("junit:junit:4.13.2")
}

/**
 * Smriti's entire pitch is that the app cannot reach the network. That claim is only true if
 * nothing re-introduces INTERNET through a transitive dependency's manifest — which ML Kit's
 * datatransport backend did, silently, on 2026-09-01.
 *
 * This task reads the MERGED manifest, which is what actually ships, and fails the build if
 * either network permission is present. A green build is now proof of the claim.
 */
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    // Widened when the Smriti Desk bridge landed. The bridge is Bluetooth, and Bluetooth is not
    // an IP socket, so the offline flavour has no use for Wi-Fi state either. Naming it here
    // means the build now proves a stricter claim than it did before the feature, not a looser
    // one. Verified absent from the merged manifest at the time this line was added.
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    // Scanning would drag a location prompt behind it. The phone only ever advertises.
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION"
)

tasks.register("assertNoNetworkPermission") {
    group = "verification"
    description = "Fails if a network permission survives manifest merging."
    doLast {
        val allManifests = fileTree(layout.buildDirectory.dir("intermediates")) {
            include("**/merged_manifest*/**/AndroidManifest.xml")
            include("**/packaged_manifests/**/AndroidManifest.xml")
        }.files
        val manifests = allManifests
        require(manifests.isNotEmpty()) { "No merged manifest found - run a build first." }

        val offenders = mutableListOf<String>()
        manifests.forEach { file ->
            val text = file.readText()
            forbiddenPermissions.forEach { perm ->
                if (text.contains(perm)) offenders += "$perm in ${file.name} (${file.parentFile.name})"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Network permission leaked into the merged manifest:\n" +
                    offenders.joinToString("\n") { "  - $it" } +
                    "\n\nFind the source with:\n" +
                    "  grep -B2 'uses-permission#android.permission.INTERNET' " +
                    "app/build/outputs/logs/manifest-merger-debug-report.txt\n" +
                    "then strip it with tools:node=\"remove\" in app/src/main/AndroidManifest.xml."
            )
        }
        logger.lifecycle("assertNoNetworkPermission: clean - ${manifests.size} merged manifest(s) carry no network permission.")
    }
}

afterEvaluate {
    tasks.named("assembleDebug") { finalizedBy("assertNoNetworkPermission") }
    tasks.findByName("assembleRelease")?.finalizedBy("assertNoNetworkPermission")
}
