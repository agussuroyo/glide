import java.util.Properties

plugins {
    id("com.android.application")
}

// The release key lives outside the repo (~/.config/glide/release.properties). Lose it and Play can no
// longer accept updates signed by you, so keep a backup.
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.config/glide/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.glide"
    compileSdk = 37

    defaultConfig {
        // Permanent once uploaded to Play.
        applicationId = "io.github.agussuroyo.glide"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { releaseProps.isNotEmpty() }
        }
    }
}

dependencies {
    // QR scanning UI from Google Play services: no camera permission, no camera code in the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
