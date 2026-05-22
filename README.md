# Step Notion Sync

Android app for Pixel devices that reads health data from Health Connect and syncs it to Notion data sources when the user taps a sync button.

## Current scope

- Reads step counts, blood pressure records, and heart rate samples from Health Connect.
- Excludes today's step data from sync because it may still change.
- Shows yesterday's latest phone-side and Notion-side timestamps for step and vital data on the top page.
- Syncs only when the user taps `歩数データを同期`, `血圧・心拍データを同期`, or `すべて同期`.
- Stores the Notion integration token locally using Android Keystore-backed encryption.
- Stores data source IDs and property names locally on the device.
- Uses the latest 30 days of Health Connect data as the sync window.

## Notion data source requirements

The step data source must have:

- A date property, default name: `Date`
- A number property, default name: `Steps`

The blood pressure data source must have:

- A date property, default name: `Measured At`
- A number property, default name: `Systolic`
- A number property, default name: `Diastolic`
- A number property, default name: `Heart Rate`

The parent databases must be shared with the Notion integration. Use data source IDs, not database IDs.

## Build

Install Android Studio and Android SDK, then build:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install

Enable USB debugging on the Pixel device, then run:

```powershell
& 'D:\Users\Sugi\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
```
