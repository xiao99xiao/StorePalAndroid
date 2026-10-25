plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.storepal.sample"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.storepal.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"
        // Supply an app-scoped key locally; the sample never embeds a production credential.
        buildConfigField("String", "STOREPAL_API_KEY", "\"${providers.gradleProperty("storepalApiKey").orElse("").get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    // Keep the offline demo usable in every supported language without a Play download.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    implementation(project(":storepal-android"))
    implementation("androidx.activity:activity-compose:1.10.1")
}
