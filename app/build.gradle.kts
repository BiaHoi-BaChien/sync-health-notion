plugins {
    id("com.android.application")
}

import java.util.Properties

val appVersionName = "0.1.9"
val appVersionCode = versionCodeFrom(appVersionName)
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?.takeIf { it.isNotBlank() }
        ?: releaseKeystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseSigningValues = mapOf(
    "storeFile" to releaseSigningValue("ANDROID_RELEASE_STORE_FILE", "storeFile"),
    "storePassword" to releaseSigningValue("ANDROID_RELEASE_STORE_PASSWORD", "storePassword"),
    "keyAlias" to releaseSigningValue("ANDROID_RELEASE_KEY_ALIAS", "keyAlias"),
    "keyPassword" to releaseSigningValue("ANDROID_RELEASE_KEY_PASSWORD", "keyPassword"),
)

fun versionCodeFrom(versionName: String): Int {
    val parts = versionName.split(".")
    require(parts.size == 3) {
        "appVersionName must use MAJOR.MINOR.PATCH format: $versionName"
    }

    val (major, minor, patch) = parts.map { part ->
        part.toIntOrNull() ?: error("appVersionName contains a non-numeric segment: $versionName")
    }

    require(major >= 0 && minor in 0..99 && patch in 0..99) {
        "appVersionName must satisfy MAJOR >= 0, MINOR 0..99, PATCH 0..99: $versionName"
    }

    val versionCode = major * 10_000 + minor * 100 + patch
    require(versionCode > 0) {
        "appVersionName must produce a positive Android versionCode: $versionName"
    }

    return versionCode
}

val configuredReleaseSigningValues = releaseSigningValues.filterValues { it != null }
require(configuredReleaseSigningValues.isEmpty() || configuredReleaseSigningValues.size == releaseSigningValues.size) {
    val missingValues = releaseSigningValues.filterValues { it == null }.keys.joinToString()
    "Release signing configuration is incomplete. Missing: $missingValues"
}
val hasReleaseSigningConfig = configuredReleaseSigningValues.isNotEmpty()

android {
    namespace = "net.biahoi.stepnotionsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sugi.synchealthnotion"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseSigningValues["storeFile"]))
                storePassword = checkNotNull(releaseSigningValues["storePassword"])
                keyAlias = checkNotNull(releaseSigningValues["keyAlias"])
                keyPassword = checkNotNull(releaseSigningValues["keyPassword"])
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("sync-health-notion-v$appVersionName-release.apk")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.health.connect:connect-client:1.2.0-alpha04")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
