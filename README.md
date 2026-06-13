# Step Notion Sync

Android app for Pixel devices that syncs step, vital, and weight data between Health Connect and Notion data sources.

Each data type can be configured independently as `同期しない`, `HealthConnect→Notion`, or `Notion→HealthConnect`. Existing installations default to `HealthConnect→Notion`.

## Current scope

- Reads Health Connect step records, aggregates them by day, and writes one daily total row to Notion.
- Saves manually entered blood pressure and heart rate values to Health Connect at the same measurement time.
- Reads Health Connect blood pressure records, pairs heart rate samples recorded at the same time, and writes measurements not already present to Notion.
- Reads Health Connect weight records from all data origins and writes the latest 30 days to Notion in kilograms.
- Keeps Notion step rows to one row per day, using the day's latest Health Connect step record time as the Notion date time.
- Includes today's step, vital, and weight data in sync.
- Shows the latest Health Connect-side and Notion-side timestamps for step, vital, and weight data on the top page.
- Sends configured data to Notion from individual sync buttons, `すべて同期`, or automatic sync.
- Sends configured Notion records to Health Connect when the reverse direction is selected, without deleting existing records.
- Stores the Notion API token locally using Android Keystore-backed encryption.
- Stores data source IDs and property names locally on the device.
- Uses the latest 30 days as the sync window for Health Connect step, vital, and weight data.
- Uses blood pressure as the base vital measurement. Heart-rate-only records are not sent to Notion.
- Uses the exact measurement timestamp as the Notion vital deduplication key.

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

The heart rate property is left empty when no heart rate sample exists at the blood pressure measurement time.

The weight data source must have:

- A date property with time enabled, default name: `日付`
- A number property in kilograms, default name: `体重`

Weight sync uses the exact Health Connect measurement timestamp as the deduplication key. It does not delete or update existing Health Connect or Notion records.

Use either an internal connection token or a personal access token (PAT). Internal connections must be shared with the parent databases. PATs use the permissions of the Notion user who created them. Use data source IDs, not database IDs.

## Security notes

- The app writes manually entered vital data to Health Connect when the user taps the registration button.
- The app reads and writes Health Connect data according to each configured synchronization direction.
- The Notion API token is entered on the device and stored locally with Android Keystore-backed encryption.
- Do not add the Notion API token to Gradle properties or environment variables used at build time. Build-time values are embedded in the APK and can be extracted.
- Prefer a dedicated internal connection shared only with the data sources this app needs. A PAT is also supported for a trusted personal device.
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

Release APK signing reads environment variables first:

- `ANDROID_RELEASE_STORE_FILE`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

For local builds, copy `keystore.properties.example` to the gitignored `keystore.properties` and enter the local values:

```properties
storeFile=release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Environment variables override matching values in `keystore.properties`. The signing configuration must contain all four values or none. `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, and `*.pfx` are local secret files and must not be committed.

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
