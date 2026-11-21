plugins { id("com.android.application") }

android {
    namespace = "uk.co.barcodealarm"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.co.barcodealarm"
        minSdk = 28
        targetSdk = 36
        versionCode = 16
        versionName = "0.13.0"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Robolectric resolves Android runtimes separately from Gradle's dependency repositories.
// Use Maven Central's canonical host rather than depending on the repo1 mirror in CI.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}

dependencies {
    implementation("androidx.work:work-runtime:2.11.0")
    testImplementation("androidx.work:work-testing:2.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // ZXing references ContextCompat and ActivityCompat but omits Core from its POM.
    implementation("androidx.core:core:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    // Android supplies org.json at runtime; JVM tests need a real implementation instead of SDK
    // stubs.
    testImplementation("org.json:json:20240303")
}
