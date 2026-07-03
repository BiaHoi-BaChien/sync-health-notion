plugins {
    id("com.android.application")
}

import java.util.Properties

val appVersionName = "0.2.7"
val appVersionCode = 118
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

val configuredReleaseSigningValues = releaseSigningValues.filterValues { it != null }
require(configuredReleaseSigningValues.isEmpty() || configuredReleaseSigningValues.size == releaseSigningValues.size) {
    val missingValues = releaseSigningValues.filterValues { it == null }.keys.joinToString()
    "Release signing configuration is incomplete. Missing: $missingValues"
}
val hasReleaseSigningConfig = configuredReleaseSigningValues.isNotEmpty()
val releaseBuildRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').endsWith("Release", ignoreCase = true)
}
require(!releaseBuildRequested || hasReleaseSigningConfig) {
    "Release builds require the existing release keystore configuration."
}

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
            output.outputFileName.set("sync-health-notion-v$appVersionName-$appVersionCode-release.apk")
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
