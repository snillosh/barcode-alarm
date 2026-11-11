plugins { id("com.android.application") }

android {
    namespace = "uk.co.barcodealarm"
    compileSdk = 36
    defaultConfig {
        applicationId = "uk.co.barcodealarm"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // ZXing references ContextCompat and ActivityCompat but omits Core from its POM.
    implementation("androidx.core:core:1.13.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
}
