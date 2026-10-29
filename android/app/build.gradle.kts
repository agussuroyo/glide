plugins {
    id("com.android.application")
}

android {
    namespace = "dev.glide"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.glide"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    // QR scanning UI from Google Play services: no camera permission, no camera code in the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
