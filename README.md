# 健康データ Notion 同期

Android app for Pixel devices that syncs step, vital, and weight data between Health Connect and Notion data sources.

Notion is a trademark of Notion Labs, Inc. This app is not an official Notion Labs, Inc. app and is not provided, affiliated with, or endorsed by Notion Labs, Inc.

Each data type can be configured independently as `同期しない`, `HealthConnect→Notion`, or `Notion→HealthConnect`. Existing installations default to `HealthConnect→Notion`.

## Current scope

- Reads Health Connect step records, aggregates them by day, and writes one daily total row to Notion.
- Saves manually entered blood pressure and heart rate values to Health Connect at the same measurement time.
- Saves manually entered weight values, including voice input rounded to one decimal place, to Health Connect.
- Lets users independently select or disable completion sounds for successful manual data entry and synchronization, including an original manual-sync chime.
- Reads Health Connect blood pressure records, pairs heart rate samples recorded at the same time, and creates or updates Notion measurements using the timestamp through the minute as the key.
- Reads Health Connect weight records from all data origins and writes the latest 30 days to Notion in kilograms.
- Keeps Notion step rows to one row per day, using the day's latest Health Connect step record time as the Notion date time.
- Includes today's step, vital, and weight data in sync.
- Shows the latest Health Connect-side and Notion-side timestamps for step, vital, and weight data on the top page.
- Reports only records actually created or updated at the sync destination, and shows `すでに最新です。` when no writes are needed.
- Sends configured data to Notion from individual sync buttons, `すべて同期`, or automatic sync.
- Sends configured Notion records to Health Connect when the reverse direction is selected, without deleting existing records.
- Stores the Notion API token locally using Android Keystore-backed encryption.
- Stores data source IDs and property names locally on the device.
- Uses the latest 30 days as the sync window for Health Connect step, vital, and weight data.
- Uses blood pressure as the base vital measurement. Heart-rate-only records are not sent to Notion.
- Uses the measurement timestamp through the minute as the vital upsert key in both sync directions. Records are skipped when systolic blood pressure, diastolic blood pressure, and heart rate are also unchanged.

## Gemini Nano saved-image experiment

`バイタルをHealth Connectに登録` → `画像から入力（検証）` checks the actual device with ML Kit Prompt API before enabling image selection. It shows `AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, or `UNAVAILABLE`, and the base model name when available. A listed supported device is not proof that its model is ready. Model download is an explicit button and requires network access.

Select one locally saved image using the system document picker. The image is decoded with orientation handling and downscaled to a maximum edge of 1600 pixels without cropping, then passed directly to Gemini Nano through AICore. No OCR, seven-segment reader, cloud inference fallback, camera, API key, or image upload is used. The app does not persist the image URI, image, or raw model response, or log health values. The experiment screen suppresses screenshots and recent-app previews. The existing app still uses the network for Notion sync, and the SDK/AICore may use it for model setup and operational telemetry; this is not a network-disabled app.

Systolic, diastolic, and pulse are unverified candidates. Unreadable fields stay empty; malformed, multiple, or truncated responses are rejected. Contradictory pressure values are cleared instead of swapped. A generative model can still return a plausible but incorrect number, so review the image and use `候補を確認・修正する` to edit the existing input fields. Only the user's existing `Health Connectに登録` action saves values. The measurement timestamp remains the registration time, not the photo timestamp; use a fresh measurement when actually saving. Manual entry, voice input, validation, and sync keep their existing behavior.

`読取の検証詳細を表示` shows the response count, finish reason, and bounded model response text on the protected experiment screen. These details remain in memory and are not written to logs/files, copied to the clipboard, or registered as health records. Failure messages distinguish no response, multiple responses, output-length truncation, abnormal completion, format rejection, and explicitly unknown values. A format rejection alone does not establish that the model failed to read the digits.

### Device evaluation protocol

1. Record device/Android version, AICore version, SDK version (`genai-prompt:1.0.0-beta4`), displayed model name/status, and model preparation time separately. If unavailable, retain the status and retry after AICore initialization; do not assume the hardware is unsupported.
2. Begin with one local blood-pressure image. Keep the photo on the phone. Record the true three values by personally reading the device/image, then the initial candidates before correction. Do not add private photos or values to Git or logs.
3. `処理時間` includes the status recheck, image decode, and inference; it does not include image picking. There is no warmup, so separate first and subsequent runs. The registration result reports total elapsed time from the first image-selection button press through picking, retries, confirmation, correction, warnings, and successful Health Connect insertion. Model preparation is excluded. Both the screen's back button and system Back return timing metadata without applying candidates, so retries from the same entry retain the first start time and attempt count. Closing the entry dialog and opening a new entry starts a new trial.
4. For manual comparison, total time starts when the normal vital-entry dialog opens and ends after successful insertion. Use the same image and alternate trial order to reduce memorization effects. Voice-assisted trials remain labelled separately through image retries. If voice was used before the first image selection, the total retains the entry-dialog start time to include that voice operation. Record failed/cancelled trials separately; they are not successful registrations. Rotation preserves the active experiment in memory; process death restarts it without restoring a private image.
5. Use fresh measurements or a separately approved test environment for actual registration. Do not register the same photo repeatedly into the user's real Health Connect/Notion data just to benchmark. When registration is not approved, stop before saving and measure time to a corrected draft externally; label it as excluding registration.
6. After the first image works, compare multiple lighting/angle/blur cases. Record per-field exact matches, complete three-field matches, unknowns, wrong candidates, corrections, retry counts, processing time, and end-to-end time. Report cold/warm latency and median total time, including correction cost and failures. Do not claim accuracy from parser tests or one image. Add camera capture only after accuracy and total effort demonstrate an advantage over manual entry.

| Trial | Method | Cold/warm | Initial matches / 3 | Unknown / wrong | Corrections / retries | Processing seconds | Total seconds | Saved / draft / failed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| (keep results locally) | image / manual / voice | | | | | | | |

References: [Prompt API setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [device support](https://developers.google.com/ml-kit/genai), [ML Kit privacy](https://developers.google.com/ml-kit/terms), [SDK data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

### First device checkpoint (2026-10-02, Ho Chi Minh time)

- Pixel 9 / Android 17, AICore `0.release.prod_aicore_20260820.00_RC08.974405357`. The signed experimental APK (`0.3.6` / `130`) was installed over the existing app with its data retained.
- The actual `checkStatus()` result was `DOWNLOADABLE`. Download was requested, but completion and the base model name have not yet been confirmed. The image-selection button stays disabled until `AVAILABLE`. Keep the experiment in the foreground; it keeps the screen awake during preparation and inference. This checkpoint does not establish image recognition accuracy or speed.
- 71 JVM tests passed; debug/release builds, `lintDebug` (zero errors, warnings remain), and `git diff --check` passed. Local Java HTTPS trust failed for new Maven Central artifacts, so these checks used official checksum-verified artifacts in an ignored temporary Maven repository via a local Gradle init script. Source repository URLs, the wrapper, and the Android Gradle Plugin were unchanged.
- No blood-pressure image was exported, and no health record was registered or manually synchronized during this check. Image recognition, correction/registration timing, and an offline inference trial remain unverified. No camera integration or GitHub release was performed.

### Saved-image follow-up (2026-10-02, Ho Chi Minh time)

- The same Pixel 9 subsequently reported `AVAILABLE` with base model `nano-v3`. The user-supplied photo matched the existing on-device photo byte for byte; inference used that local copy.
- The first diagnosed failure completed normally (`STOP`) in 3.3 seconds, but returned three unlabelled numbers. The parser correctly rejected the response, and visual comparison also found two incorrect fields. Shorter instructions restored the required labelled format in 3.6 seconds but did not fix those digit errors.
- The final prompt explicitly requests `UNKNOWN` for digits obscured by glare, reflection, or low contrast. On the same photo it completed in 3.6 seconds with one visually matching field, one incorrect field, and one unknown field. These are results for one photo, not an accuracy estimate. Prompt instructions do not guarantee that the model abstains from an incorrect answer. No expected measurement values were supplied to the model or hardcoded into the app.
- The candidates returned to the existing editable fields, with the unknown field empty. The draft was cancelled without registration. No manual sync was triggered. End-to-end registration timing, comparison against the user's manual entry, and offline inference remain unverified. This result does not justify camera integration.
- The final APK was installed with existing data retained. All 71 JVM tests, debug/release builds, `lintDebug` (0 errors, 109 warnings), and `git diff --check` passed using the same temporary dependency-cache workaround described above. Private photos and measurement values are not included in the repository.

### Timing review fixes (2026-10-02, Ho Chi Minh time)

- Cancelling an image attempt now returns only timing metadata. Existing draft values remain unchanged, and subsequent attempts in the same entry retain the first start time and accumulated attempt count. An interrupted inference does not reuse a previous attempt's processing duration.
- Input methods use explicit states so an image retry cannot erase prior voice use. The current method and attempt count are also shown in the entry dialog and preserved during activity recreation.
- Seven regression tests cover cancelled reads, picker cancellation, interruption during inference, reopening without another read, and voice use before/after image input. All 78 JVM tests, debug/release builds, `lintDebug` (0 errors, 109 warnings), and `git diff --check` passed with the same local dependency-cache workaround. The updated APK signature was verified. Installation and device verification of these timing fixes are pending reconnection of the Pixel 9; no registration or manual sync was performed for this follow-up.

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

Download the signed release APK from GitHub Releases, or build it locally with `assembleRelease`.

The current release is `v0.2.1-112`, published as `sync-health-notion-v0.2.1-112-release.apk`.

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
