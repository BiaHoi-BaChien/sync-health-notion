# 個人利用向け Release APK チェックリスト

このアプリの通常リリースは、Google Play向けAABではなく、既存の署名鍵を使ったRelease APKを対象とします。

## リリース前

- `app/build.gradle.kts` の `appVersionCode` を前回リリースより必ず増やす。
- ユーザー向けバージョンを変える場合は `appVersionName` も更新する。
- 既存のkeystoreとkey aliasを使用し、署名鍵を変更しない。
- `keystore.properties` や署名鍵をGitへコミットしない。

## ビルド

WSL/Linux:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

生成先:

```text
app/build/outputs/apk/release/
```

## 確認

1. `sync-health-notion-v<versionName>-<versionCode>-release.apk` が生成されている。
2. Android SDKの `apksigner verify --print-certs <APK>` が成功する。
3. 証明書情報が前回インストール版と同じである。
4. 実機で `adb install -r <APK>` による新規インストールまたは上書き更新が成功する。
5. Health Connect権限、Notion設定保存、手動入力、同期、バックグラウンド自動同期を確認する。

Google Play申請用の設定やプライバシーポリシー文書は将来の参考として残しますが、通常のリリース手順では使用しません。
