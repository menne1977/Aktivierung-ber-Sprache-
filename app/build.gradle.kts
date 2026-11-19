plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dtm.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dtm.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Festes Schluessel-Paar, damit neue Versionen immer ueber die alte APK installiert werden koennen
    signingConfigs {
        create("dtm") {
            storeFile = file("dtm-voice.keystore")
            storePassword = "dtmvoice"
            keyAlias = "dtmvoice"
            keyPassword = "dtmvoice"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dtm")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
