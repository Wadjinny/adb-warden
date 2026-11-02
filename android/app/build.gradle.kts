import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: environment variables (CI) win, else the local file
// ~/.android-keys/adb-warden-release.properties (or the path in ADBWARDEN_SIGNING_PROPS).
// Without either, release builds fall back to the debug key so anyone can still build.
val signingFile = file(
    System.getenv("ADBWARDEN_SIGNING_PROPS")
        ?: "${System.getProperty("user.home")}/.android-keys/adb-warden-release.properties"
)
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
fun signingValue(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(key)
val releaseKeystore = signingValue("ADBWARDEN_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.wadjinny.adbwarden"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wadjinny.adbwarden"
        minSdk = 30
        targetSdk = 35
        // CI passes these from the git tag (v1.2.3 → 1.2.3) and the run number.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("ADBWARDEN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("ADBWARDEN_KEY_ALIAS")
                keyPassword = signingValue("ADBWARDEN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
