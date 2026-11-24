plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val passkeyHost = providers.gradleProperty("protocolXPasskeyHost").getOrElse("protocolx.mandonothing.com")
require(passkeyHost.matches(Regex("[a-z0-9.-]+")) && "." in passkeyHost) { "Configure a DNS name for Protocol X Passkeys." }

android {
    namespace = "com.mandonothing.ops"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mandonothing.ops"
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "0.27.3"
        manifestPlaceholders["protocolXPasskeyHost"] = passkeyHost
        buildConfigField("String", "PASSKEY_HOST", "\"$passkeyHost\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.3.aar"))
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.livekit:livekit-android:2.30.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
