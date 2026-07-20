import java.util.Base64

plugins {
    id("com.android.application")
}

android {
    namespace = "com.antidetect.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antidetect.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            val keystoreB64 = System.getenv("SIGNING_KEY")
            if (keystoreB64 != null) {
                val keystoreFile = File.createTempFile("release-key", ".jks")
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreB64))
                storeFile = keystoreFile
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val signing = signingConfigs.findByName("release")
            if (signing != null && System.getenv("SIGNING_KEY") != null) {
                signingConfig = signing
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
}
