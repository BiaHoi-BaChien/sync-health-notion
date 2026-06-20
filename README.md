# 健康データ Notion 同期

Android app for Pixel devices that syncs step, vital, and weight data between Health Connect and Notion data sources.

NotionはNotion Labs, Inc.の商標です。本アプリはNotion Labs, Inc.の公式アプリではなく、同社による提供・提携・承認を受けたものではありません。

Each data type can be configured independently as `同期しない`, `HealthConnect→Notion`, or `Notion→HealthConnect`. Existing installations default to `HealthConnect→Notion`.

## Current scope

- Reads Health Connect step records, aggregates them by day, and writes one daily total row to Notion.
- Saves manually entered blood pressure and heart rate values to Health Connect at the same measurement time.
- Saves manually entered weight values, including voice input rounded to one decimal place, to Health Connect.
- Reads Health Connect blood pressure records, pairs heart rate samples recorded at the same time, and creates or updates Notion measurements using the timestamp through the minute as the key.
- Reads Health Connect weight records from all data origins and writes the latest 30 days to Notion in kilograms.
- Keeps Notion step rows to one row per day, using the day's latest Health Connect step record time as the Notion date time.
- Includes today's step, vital, and weight data in sync.
- Shows the latest Health Connect-side and Notion-side timestamps for step, vital, and weight data on the top page.
- Reports only records actually created or updated at the sync destination, and shows that data is already current when no writes are needed.
- Sends configured data to Notion from individual sync buttons, `すべて同期`, or automatic sync.
- Sends configured Notion records to Health Connect when the reverse direction is selected, without deleting existing records.
- Stores the Notion API token locally using Android Keystore-backed encryption.
- Stores data source IDs and property names locally on the device.
- Uses the latest 30 days as the sync window for Health Connect step, vital, and weight data.
- Uses blood pressure as the base vital measurement. Heart-rate-only records are not sent to Notion.
- Uses the measurement timestamp through the minute as the vital upsert key in both sync directions. Records are skipped when systolic blood pressure, diastolic blood pressure, and heart rate are also unchanged.

## Notion data source requirements

The step data source must have:

- A date property with time enabled, default name: `日付`
- A number property, default name: `歩数`

Step sync keeps one Notion row per day and uses the daily latest Health Connect step record time in the date property. It skips writes when that timestamp matches through the minute and the daily step total is unchanged.

The blood pressure data source must have:

- A date property, default name: `日付`
- A number property, default name: `収縮期`
- A number property, default name: `拡張期`
- A number property, default name: `脈拍`

The heart rate property is left empty when no heart rate sample exists at the blood pressure measurement time.

The weight data source must have:

- A date property with time enabled, default name: `日付`
- A number property in kilograms, default name: `体重`

Weight sync uses the measurement timestamp through the minute as the upsert key in both directions. It skips writes when the weight value is also unchanged.

Use either an internal connection token or a personal access token (PAT). Internal connections must be shared with the parent databases. PATs use the permissions of the Notion user who created them. Use data source IDs, not database IDs.

## Security notes

- The app writes manually entered vital data to Health Connect when the user taps the registration button.
- The app reads and writes Health Connect data according to each configured synchronization direction.
- The Notion API token is entered on the device and stored locally with Android Keystore-backed encryption.
- Do not add the Notion API token to Gradle properties or environment variables used at build time. Build-time values are embedded in the APK and can be extracted.
- Prefer a dedicated internal connection shared only with the data sources this app needs. A PAT is also supported for a trusted personal device.
- `local.properties` is local Android SDK configuration and must not be committed.

## Build

This app is distributed for personal use as a signed release APK. It does not use an Android App Bundle in the normal release workflow.

Release signing reads environment variables first:

- `ANDROID_RELEASE_STORE_FILE`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

For local builds, copy `keystore.properties.example` to the gitignored `keystore.properties` and enter the existing release keystore values:

```properties
storeFile=upload-keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Environment variables override matching values in `keystore.properties`. The signing configuration must contain all four values or none. Continue using the same keystore and key alias so an installed app can be updated in place. `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, and `*.pfx` are local secret files and must not be committed.

Build the signed release APK.

WSL/Linux:

```bash
./gradlew assembleRelease
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleRelease
```

The release APK is generated at:

```text
app/build/outputs/apk/release/sync-health-notion-v<versionName>-<versionCode>-release.apk
```

Verify the APK signature with the Android SDK `apksigner` command:

WSL/Linux:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/*.apk
```

Windows PowerShell:

```powershell
apksigner verify --print-certs (Get-ChildItem app\build\outputs\apk\release\*.apk).FullName
```

## GitHub release automation

Every push to GitHub builds a debug APK. Download it from the workflow run's `Artifacts` section. The artifact name includes the commit SHA and is retained for 14 days. This APK is signed with Android's debug key and is intended for development verification only.

When source is pushed or merged into `main` with an increased `appVersionCode`, GitHub Actions builds a signed release APK with `assembleRelease`, verifies its signature, and creates a GitHub Release with the APK attached. Changes that leave `appVersionCode` unchanged skip the release build. A decrease fails the workflow.

Before every release, increment `appVersionCode` in `app/build.gradle.kts`. Update `appVersionName` when the user-visible version should change. Release tags and APK filenames include both values to remain unique and identifiable.

Configure these repository secrets before using the workflow:

- `ANDROID_UPLOAD_KEYSTORE_BASE64`: Base64-encoded contents of `upload-keystore.jks`.
- `ANDROID_UPLOAD_STORE_PASSWORD`: Upload keystore password.
- `ANDROID_UPLOAD_KEY_ALIAS`: Upload key alias.
- `ANDROID_UPLOAD_KEY_PASSWORD`: Upload key password.

To create `ANDROID_UPLOAD_KEYSTORE_BASE64` from PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks"))
```

From WSL/Linux:

```bash
base64 -w 0 upload-keystore.jks
```

## Install

Enable USB debugging on the Android device, then install or update the signed release APK. The existing app can only be updated when the APK uses the same application ID and signing key.

WSL/Linux:

```bash
adb install -r app/build/outputs/apk/release/*.apk
```

Windows PowerShell:

```powershell
adb install -r (Get-ChildItem app\build\outputs\apk\release\*.apk).FullName
```

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
