# Step Notion Sync

Android app for Pixel devices that syncs step and vital data between Health Connect and Notion data sources when the user taps a sync button.

## Current scope

- Reads Health Connect step records, aggregates them by day, and writes one daily total row to Notion.
- Reads blood pressure and heart rate values from Notion and writes them to Health Connect.
- Keeps Notion step rows to one row per day, using the day's latest Health Connect step record time as the Notion date time.
- Includes today's step and vital data in sync.
- Shows the latest phone-side and Notion-side timestamps for step and vital data on the top page.
- Syncs only when the user taps `歩数データを同期`, `血圧・心拍データを同期`, or `すべて同期`.
- Stores the Notion integration token locally using Android Keystore-backed encryption.
- Stores data source IDs and property names locally on the device.
- Uses the latest 30 days as the sync window for Health Connect step data and Notion vital data.

## Notion data source requirements

The step data source must have:

- A date property with time enabled, default name: `日付`
- A number property, default name: `歩数`

Step sync uses the daily latest Health Connect step record time in the date property. When that time differs from the Notion row for the same day, the app updates the row with the latest daily total.

The blood pressure data source must have:

- A date property, default name: `日付`
- A number property, default name: `収縮期`
- A number property, default name: `拡張期`
- A number property, default name: `脈拍`

The parent databases must be shared with the Notion integration. Use data source IDs, not database IDs.

## Security notes

- The app reads and writes health data only after the user taps a sync button.
- The Notion integration token is entered on the device and stored locally with Android Keystore-backed encryption.
- Use a Notion integration that is shared only with the data sources this app needs.
- `local.properties` is local Android SDK configuration and must not be committed.

## Build

Install Android Studio and Android SDK, then build:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Release APK builds use `keystore.properties` and `release-key.jks` when they are present locally:

```properties
storeFile=release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`keystore.properties` and `*.jks` are local secret files and must not be committed.

## GitHub release automation

When source is pushed or merged into `main` with a changed `appVersionName`, GitHub Actions builds a signed release APK and creates a GitHub Release with the APK attached. Changes that leave `appVersionName` unchanged skip the release build, release upload, and release notification path.

Before merging a release change, update `appVersionName` in `app/build.gradle.kts`. Android `versionCode` is generated from that SemVer value as `MAJOR * 10000 + MINOR * 100 + PATCH`, so `0.0.3` builds with `versionCode 3` and future release APKs remain installable over older releases.

Configure these repository secrets before using the workflow:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: Base64-encoded contents of the release keystore.
- `ANDROID_RELEASE_STORE_PASSWORD`: Keystore password.
- `ANDROID_RELEASE_KEY_ALIAS`: Release key alias.
- `ANDROID_RELEASE_KEY_PASSWORD`: Release key password.

To create `ANDROID_RELEASE_KEYSTORE_BASE64` from PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))
```

## Install

Enable USB debugging on the Pixel device, then install with Gradle:

```powershell
.\gradlew.bat installDebug
```
