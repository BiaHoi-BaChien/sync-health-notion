# Google Play 登録チェックリスト

この文書は Play Console 申請時に入力する内容の下書きです。公開前に実際の配布地域に合わせて更新してください。

- サポートメールアドレス: sugi@clb-biahoi.net
- プライバシーポリシーURL: https://clb-biahoi.net/privacy-policy-ja-health-notion-sync.html

## 登録前の必須確認

- `app/build.gradle.kts` の `versionName` と `versionCode` が前回公開版より大きいこと。
- `bundleRelease` で `app/build/outputs/bundle/release/app-release.aab` が生成できること。
- 実機で Health Connect 権限許可、Notion 設定保存、手動入力、同期、バックグラウンド自動同期を確認すること。
- プライバシーポリシーをWeb上に公開し、Play ConsoleのPrivacy policy URLへ `https://clb-biahoi.net/privacy-policy-ja-health-notion-sync.html` を設定すること。
- Health Connect権限の用途説明、Data safety、アプリ内説明が一致していること。

## Data safety 入力方針

このアプリはユーザーが入力したNotion接続情報を使い、Health ConnectとNotionの間で健康・フィットネスデータを同期します。アプリ提供者の独自サーバーにはデータを送信しません。

- 収集/共有する可能性があるデータ:
  - 健康とフィットネス: 歩数、血圧、心拍、体重。
  - アプリ情報とパフォーマンス: Play ConsoleやAndroid標準のクラッシュ/診断データを利用する場合のみ該当。
- データ送信先:
  - Notion API。ユーザーが設定したNotionワークスペース/データソースへ同期します。
  - Health Connect。端末内のHealth Connectへ読み書きします。
- 暗号化:
  - Notion API通信はHTTPSを使用します。
  - Notion access tokenはAndroid Keystoreで保護した鍵により端末内で暗号化保存します。
- 削除:
  - アプリの設定画面でトークンを空にして保存すると保存済みトークンを削除できます。
  - Notion上の同期済みデータはユーザー自身のNotionワークスペースで削除します。
  - Health Connect上のデータはHealth Connectまたは対応アプリから削除します。

## Health Apps declaration 下書き

アプリの主目的:

```text
Health Connectに保存された歩数、血圧、心拍、体重データを、ユーザーが指定したNotionデータソースと同期する個人向け記録アプリです。ユーザーはデータ種別ごとに同期しない、Health ConnectからNotion、NotionからHealth Connectを選択できます。
```

権限の用途:

- `READ_STEPS` / `WRITE_STEPS`: 歩数データをNotionへ同期、またはNotionからHealth Connectへ同期するため。
- `READ_BLOOD_PRESSURE` / `WRITE_BLOOD_PRESSURE`: 血圧データをNotionへ同期、または手入力/NotionデータをHealth Connectへ保存するため。
- `READ_HEART_RATE` / `WRITE_HEART_RATE`: 血圧と同時刻の心拍をNotionへ同期、または手入力/NotionデータをHealth Connectへ保存するため。
- `READ_WEIGHT` / `WRITE_WEIGHT`: 体重データをNotionへ同期、または手入力/NotionデータをHealth Connectへ保存するため。
- `READ_HEALTH_DATA_IN_BACKGROUND`: ユーザーが自動同期を有効にした場合に、指定時刻のバックグラウンド同期を実行するため。
- `RECORD_AUDIO`: 体重、血圧、心拍を音声入力するため。録音データはアプリ独自サーバーへ送信しません。

審査用メモ:

```text
初回起動後、設定画面でNotion access token、Data Source ID、プロパティ名を入力します。Health Connect権限を許可すると、トップ画面の各「すぐに同期」ボタンから同期を確認できます。体重またはバイタルの追加ボタンで手入力と音声入力を確認できます。自動同期は設定画面の時刻選択で有効化できます。
```

## ストア掲載素材

- アプリ名: 健康データ Notion 同期
- 短い説明案: Health Connectの歩数、血圧、心拍、体重をNotionと同期します。
- 詳細説明案:

```text
健康データ Notion 同期は、Health Connectに保存された歩数、血圧、心拍、体重を、ユーザー自身のNotionデータソースと同期する個人向けアプリです。データ種別ごとに同期しない、Health ConnectからNotion、NotionからHealth Connectを選択できます。Notion access tokenは端末内で暗号化保存され、アプリ提供者の独自サーバーには送信されません。
```

必要素材:

- スマートフォン用スクリーンショット 2枚以上。
- 512x512 アプリアイコン。
- 1024x500 Feature Graphic。
- サポートメールアドレス: sugi@clb-biahoi.net
- 公開済みプライバシーポリシーURL: https://clb-biahoi.net/privacy-policy-ja-health-notion-sync.html

## リリース確認コマンド

WSL/Linux:

```bash
env GRADLE_USER_HOME=$PWD/.gradle-user-home bash gradlew test
env GRADLE_USER_HOME=$PWD/.gradle-user-home bash gradlew lint
env GRADLE_USER_HOME=$PWD/.gradle-user-home bash gradlew bundleRelease
```

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat bundleRelease
```
