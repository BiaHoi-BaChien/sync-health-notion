# AGENTS.md

## 基本方針

回答は常に日本語で行うこと。

このリポジトリは Android アプリ `健康データ Notion 同期` の開発用リポジトリである。最小差分、再現性、実機での確認可能性を重視する。

## 1. まず現状確認

- 推測で変更しない。
- 作業前に次を確認する。
  - `git status --short --branch`
  - `README.md`
  - `build.gradle.kts`
  - `app/build.gradle.kts`
  - `app/src/main/AndroidManifest.xml`
  - 関連する Kotlin ソースとテスト
- 同期、Health Connect、Notion 連携、日付変換、バックグラウンド実行に関わる変更では、表示側の取得条件と同期側の取得条件を比較してから原因を判断する。
- 重複レコードの統合、Notion 側データ修復、既存データ削除は、ユーザーが明示的に依頼しない限り実装しない。

## 2. 変更方針

- `main` へ直接変更しない前提で作業する。
- 既存の設計、命名、フォーマットを尊重する。
- 変更は PR 化しやすい単位に限定する。
- ユーザー表示名、Notion プロパティ名、GitHub Release 表記、Google Play 用表記は、ユーザー向け文言として扱い、勝手に正規化しない。
- 内部識別子は、明示依頼がない限り変更しない。
  - `applicationId`
  - `namespace`
  - package name
  - repository name
  - APK 名
- バージョン更新を伴う場合は、`appVersionName` と `appVersionCode` の両方を確認する。

## 3. 標準検証コマンド

Android/Gradle の検証では、リポジトリローカルの Gradle home を使う。

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash gradlew testDebugUnitTest
```

PR 前または作業完了前には、可能な限り次を実行する。

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash gradlew testDebugUnitTest
git diff --check
git status --short --branch
git diff --stat
```

ビルド確認が必要な場合は次を使う。

```bash
GRADLE_USER_HOME="$PWD/.gradle-user-home" bash gradlew assembleDebug
```

依存関係の更新、Gradle wrapper 更新、Android Gradle Plugin 更新は、ユーザー確認なしに行わない。

## 4. Android 実機確認

ユーザーが「スマホで確認」「実機確認」「Pixel で確認」などを依頼した場合、ビルド成功だけで完了扱いにしない。

まず端末認識を確認する。

Windows 側:

```powershell
adb.exe devices -l
```

WSL/Linux 側:

```bash
adb devices -l
```

WSL の `adb` と Windows の `adb.exe` は認識状態が異なることがある。`no devices/emulators found` の場合は、APK やビルド成果物ではなく、USB デバッグ、信頼ダイアログ、ケーブル、接続モード、ADB サーバーの問題として扱う。

実機確認では、必要に応じて次を確認する。

- APK インストール可否
- アプリ起動可否
- Health Connect 権限
- 通知アクセス権限
- バックグラウンド同期の実行可否
- `logcat`
- `dumpsys notification`

## 5. Health Connect / Notion 同期の注意点

- 日付・時刻はタイムゾーンと日境界を明示して扱う。
- 歩数などの日次集計では、対象日の開始時刻と終了時刻を確認する。
- bucket の終了時刻をフォールバック timestamp として使うと翌日にずれる可能性があるため注意する。
- Notion API への書き込み、更新、削除は影響範囲を説明してから実行する。
- Notion 側の既存ページ削除、統合、アーカイブは事前確認必須。

## 6. セキュリティと秘密情報

- Notion token、API key、keystore、署名情報、パスワードを出力、保存、コミットしない。
- `.env`、local properties、署名関連ファイルは扱いに注意する。
- 認証、権限、署名、Google Play 提出、外部 API 本番書き込みに関わる変更は、実行前にユーザー確認する。

## 7. Git / GitHub 運用

- 作業前に現在ブランチと差分を確認する。
- PR 作成前に、変更ファイルを明示してステージングする。
- unrelated な差分やモード変更を PR に混ぜない。
- protected branch、ruleset、merge queue、admin bypass が関係する場合は、状態を確認してから説明する。
- admin bypass はユーザーの明示承認なしに実行しない。

よく使う確認:

```bash
git status --short --branch
gh pr list
gh pr checks
```

`gh` がリポジトリを推定できない場合は、明示的に `BiaHoi-BaChien/sync-health-notion` を指定する。

## 8. 外部サービス操作

次の操作は、事前にユーザー確認する。

- GitHub PR の ready 化
- GitHub PR の merge
- GitHub Release 作成・更新
- Google Play 関連操作
- Notion への本番データ書き込み
- Notion 既存データの更新・削除
- 本番環境への反映
- 依存関係更新

読み取り、状態確認、ログ確認は原則として実行してよい。

## 9. 説明形式

作業完了時は、以下の順で簡潔に説明する。

1. 変更した内容
2. 理由
3. 影響範囲
4. 確認方法
5. 注意点

テストやビルドを実行できなかった場合は、その理由を明記する。
