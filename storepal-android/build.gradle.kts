plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}
android {
    namespace = "app.storepal.android"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    testOptions { targetSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    api(project(":storepal-core"))
    api(platform("androidx.compose:compose-bom:2025.10.01"))
    api("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("io.noties.markwon:core:4.6.2")
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(kotlin("test"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]) } }
    }
}
