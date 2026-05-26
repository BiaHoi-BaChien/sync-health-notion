plugins {
    id("com.android.application")
}

val appVersionName = "0.0.1"

android {
    namespace = "net.biahoi.stepnotionsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.biahoi.stepnotionsync"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = appVersionName
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("sync-health-notion-v$appVersionName-release-unsigned.apk")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.health.connect:connect-client:1.2.0-alpha04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
