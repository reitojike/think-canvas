# ThinkCanvas

ThinkCanvas は Android 向けのローカルファーストな思考キャンバスです。このリポジトリは現在、
GitHub Issue [#1](https://github.com/reitojike/think-canvas/issues/1) の初期構築段階です。
アプリは ThinkCanvas の名前だけを表示する最小の Compose アプリで、キャンバス機能はまだありません。

## 開発環境

- Android Studio と Android SDK Platform 36 / Build Tools 35.0.0
- JDK 17（Android Studio 同梱の JDK も利用できます）
- インターネット接続（初回の Gradle と Android 依存関係の取得に必要）
- Spec Kit の操作には Python 環境と `uv` が必要です

この初期構成は AGP 8.13.2、Gradle 8.13、API 36 と Compose BOM `2026.04.01` を使用します。
[AGP 8.13 の互換表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
と [Compose 2026 年 4 月リリース](https://developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-april-26-release)
を基準に選んでいます。Compose 1.12 系を含む新しい BOM へ上げる場合は
[API 37 / AGP 9.1 以上への移行](https://developer.android.com/blog/posts/what-s-new-in-the-jetpack-compose-august-26-release)
も同時に検討してください。

リポジトリを clone して Android Studio で開き、SDK 36 をインストールしてください。
Android Studio が作成する `local.properties` は追跡しません。コマンドラインでは JDK 17 と
`ANDROID_HOME` または `ANDROID_SDK_ROOT` を設定し、Windows なら次を実行します。

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux では `bash ./gradlew :app:assembleDebug` を使います。ビルド成果物は
`app/build/outputs/apk/debug/` に作成されます。

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
Codex では `$speckit-constitution` などの skill 名を使います。次の機能は
[#2 Canvas Foundation](https://github.com/reitojike/think-canvas/issues/2) です。
`$speckit-specify` → `$speckit-clarify` → `$speckit-plan` → `$speckit-checklist` →
`$speckit-tasks` → `$speckit-analyze` → `$speckit-implement` → `$speckit-converge`
の順に進め、設計判断をその機能の成果物に残します。

## 要件の基準と公開境界

PRD が製品要件の authority です。HTML モックは操作の reference implementation
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

## #1 の範囲

Kotlin、Compose、Gradle wrapper と起動できる最小アプリを用意します。Jetpack Ink と Room 3 は
後続の機能で使用する方針ですが、現時点では依存関係や schema を追加しません。
`BoardEngine`、ワールド座標、gesture、保存モデル、画面デザインは #2 以降で決めます。
