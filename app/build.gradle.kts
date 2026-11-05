import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.mathieu.gpssender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mathieu.gpssender"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_KEY", "\"${localProperties.getProperty("API_KEY") ?: ""}\"")
        buildConfigField("String", "API_URL", "\"${localProperties.getProperty("API_URL") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Signature des releases en CI (voir .github/workflows/release.yml). Sans ces variables,
    // la release reste signable comme avant via Android Studio (Build > Generate Signed APK).
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Les APK de release sont publiés sur un dépôt public : jamais de clé API ni d'URL
            // intégrées. Elles se saisissent dans l'écran de réglages de l'appli.
            buildConfigField("String", "API_KEY", "\"\"")
            buildConfigField("String", "API_URL", "\"\"")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.socket.io.client)
    // Force une version récente d'OkHttp (socket.io-client embarque OkHttp 3.x par défaut)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}