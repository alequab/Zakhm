import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kept outside the project so the signing key can never be committed.
val signing = Properties().apply {
    val file = File(System.getProperty("user.home"), ".zakhm/signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "app.zakhm"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.zakhm"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.2"

        val updateUrl = providers.gradleProperty("zakhmUpdateUrl").getOrElse("")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
    }

    signingConfigs {
        if (!signing.isEmpty) {
            create("release") {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { test ->
            test.systemProperty("replayFile", providers.gradleProperty("replayFile").getOrElse(""))
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
