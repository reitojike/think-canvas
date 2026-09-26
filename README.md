# ThinkCanvas

ThinkCanvas は Android 向けのローカルファーストな思考キャンバスです。
[#2 Canvas Foundation](https://github.com/reitojike/think-canvas/issues/2) では、最初のボードに
テキストを配置・編集し、パン・ズーム、選択・移動、Undo/Redo を行える基礎を実装します。
確定済み要素は Room 3 で端末内に保存します。

## 開発環境

- Android Studio と Android SDK Platform 36 / Build Tools 36.0.0
- JDK 25
- インターネット接続（初回の Gradle と Android 依存関係の取得に必要）
- Spec Kit の操作には Python 環境と `uv` が必要です

プラグインとライブラリの版数は [root の Gradle 設定](build.gradle.kts) と
[app の Gradle 設定](app/build.gradle.kts)、Gradle の版数は
[Gradle Wrapper の設定](gradle/wrapper/gradle-wrapper.properties) を参照してください。

リポジトリを clone して Android Studio で開き、SDK Platform 36 をインストールしてください。
コマンドラインでは `sdkmanager "platforms;android-36" "build-tools;36.0.0"` を使います。
Android Studio が作成する `local.properties` は追跡しません。コマンドラインでは JDK 25 と
`ANDROID_HOME` または `ANDROID_SDK_ROOT` を設定し、Windows なら次を実行します。

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

macOS / Linux では各 Gradle コマンドの先頭を `./gradlew` に置き換えます。ビルド成果物は
`app/build/outputs/apk/debug/` に作成されます。

PR の一時 APK と署名済み internal APK の実機配布は
[Android 実機確認](docs/runbooks/device-verification.md) にまとめています。

## Spec Kit

Spec Kit は **v1.0.12** に固定しています。Codex skills integration は `.agents/skills/`、
共通テンプレートと PowerShell スクリプトは `.specify/` にあります。初期化オプションと
バージョンは `.specify/init-options.json` に記録されています。

`uv` を用意した後、次のコマンドで同じバージョンの CLI と設定を再現・更新できます。
既存の Constitution を変更する場合は、再初期化後に差分を確認してください。

```powershell
uvx --from specify-cli==1.0.12 specify version
uvx --from specify-cli==1.0.12 specify init --here --force --integration codex --script ps --ignore-agent-tools
```

Constitution は [`.specify/memory/constitution.md`](.specify/memory/constitution.md) にあります。
開発と PR レビューの手順は [`docs/runbooks/change-review.md`](docs/runbooks/change-review.md)
にまとめています。`AGENTS.md` はこれらの判断基準を案内します。
Codex では `$speckit-constitution` などの skill 名を使います。#2 の成果物は
[`specs/001-canvas-foundation/`](specs/001-canvas-foundation/) にあります。
`$speckit-specify` → `$speckit-clarify` → `$speckit-plan` → `$speckit-checklist` →
`$speckit-tasks` → `$speckit-analyze` → `$speckit-implement` → `$speckit-converge`
の順に進め、設計判断をその機能の成果物に残します。

## 要件の基準と公開境界

PRD が製品要件の authority です。HTML モックは操作とコンポーネントの見た目の reference implementation
として扱います。内容が食い違う場合は PRD を優先し、差分と理由を spec に記録します。
PRD が決めていないことは spec で明示してから実装します。両ファイル自体は公開しません。

このリポジトリには API キー、トークン、認証情報、個人情報、ローカルパス、非公開サービス情報、
会話ログ、PRD、HTML モックを追加しません。`.gitignore` は既知のローカル設定と秘密ファイルを
除外します。公開前には次を実行し、さらに差分を目視確認してください。

```powershell
pwsh -File scripts/check-public-boundary.ps1
git diff --check
git status --short
```

チェックは典型的なパターンの検出補助です。新しい名前の秘密情報や、文章に混ざった
非公開情報まで自動的に判定するものではありません。

## #1 の初期構築

Kotlin、Compose、Gradle wrapper と起動できる最小アプリを用意しました。#1 では
キャンバスや保存モデルを先取りせず、#2 の仕様に基づいて追加しています。Ink は後続の仕様で扱います。
