# Step Notion Sync

Android app for Pixel devices that reads daily step counts from Health Connect and syncs them to a Notion data source when the user taps the sync button.

## Current scope

- Reads today's step count from Health Connect.
- Syncs only when the `同期` button is tapped.
- Stores Notion integration token, data source ID, and property names locally on the device.
- Queries the Notion data source for today's page.
- Updates the existing page when found, or creates a new page when missing.

## Notion data source requirements

The target Notion data source must have:

- A date property, default name: `Date`
- A number property, default name: `Steps`

The parent database must be shared with the Notion integration. Use the data source ID, not the database ID.

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
