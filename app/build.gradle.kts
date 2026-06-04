plugins {
    id("com.android.application")
}

import java.util.Properties

val appVersionName = "0.1.0"
val appVersionCode = versionCodeFrom(appVersionName)
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

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

val hasReleaseSigningConfig = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
).all { releaseKeystoreProperties.getProperty(it)?.isNotBlank() == true }

android {
    namespace = "net.biahoi.stepnotionsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.biahoi.stepnotionsync"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
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
