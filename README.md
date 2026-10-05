# ThinkCanvas

ThinkCanvas は Android 向けのローカルファーストな思考キャンバスです。テキストや手書き、
図形・囲み・矢印・写真を自由に置き、配置に込めた意味を保ちながら考えを組み替えられます。
複数のボードを管理し、検索で見返し、必要な範囲を画像として持ち出せます。
ボードと要素は Room 3 で端末内に保存し、記録・閲覧・編集・保存はアカウントや AI、通信なしで完結します。

## 現在の状態

[初期構築 #1](https://github.com/reitojike/think-canvas/issues/1) と Spec 001〜005 の実装、
[初期ロードマップ #7](https://github.com/reitojike/think-canvas/issues/7) は完了しています。
現在は日常利用での検証（dogfooding）と、使い勝手の改善を進める段階です。
[Roadmap v2 #81](https://github.com/reitojike/think-canvas/issues/81)で、mobile操作と記録の改善を追跡しています。

| 実装済みの仕様 | 対象 Issue | 公開仕様 |
| --- | --- | --- |
| Spec 001: Canvas Foundation | [#2](https://github.com/reitojike/think-canvas/issues/2) | [spec](specs/001-canvas-foundation/spec.md) |
| Spec 002: Spatial Organization | [#3](https://github.com/reitojike/think-canvas/issues/3) | [spec](specs/002-spatial-organization/spec.md) |
| Spec 003: Ink & Stylus | [#4](https://github.com/reitojike/think-canvas/issues/4) | [spec](specs/003-ink-stylus/spec.md) |
| Spec 004: Semantic Navigation | [#5](https://github.com/reitojike/think-canvas/issues/5) | [spec](specs/004-semantic-navigation/spec.md) |
| Spec 005: Board Lifecycle | [#6](https://github.com/reitojike/think-canvas/issues/6) | [spec](specs/005-board-lifecycle/spec.md) |
| Spec 006: Text Editor Dismissal | [#71](https://github.com/reitojike/think-canvas/issues/71) | [spec](specs/006-text-editor-dismissal/spec.md) |
| Spec 007: Neutral Canvas Interaction | [#73](https://github.com/reitojike/think-canvas/issues/73) | [spec](specs/007-neutral-canvas-interaction/spec.md) |
| Spec 008: Drag Edge Auto-Pan | [#78](https://github.com/reitojike/think-canvas/issues/78) | [spec](specs/008-drag-edge-auto-pan/spec.md) |
| Spec 009: Offscreen Direction Indicators | [#76](https://github.com/reitojike/think-canvas/issues/76) | [spec](specs/009-offscreen-direction-indicators/spec.md) |
| Spec 010: Viewport History | [#77](https://github.com/reitojike/think-canvas/issues/77) | [spec](specs/010-viewport-history/spec.md) |
| Spec 011: Android Share Target | [#79](https://github.com/reitojike/think-canvas/issues/79) | [spec](specs/011-share-text/spec.md) |
| Spec 012: Blank Tap Arbitration | [#67](https://github.com/reitojike/think-canvas/issues/67) | [spec](specs/012-blank-tap-arbitration/spec.md) |
| Spec 013: Image Element | [#80](https://github.com/reitojike/think-canvas/issues/80)（代表実機確認待ち） | [spec](specs/013-image-element/spec.md) |

## 利用できる主な機能

- テキストの作成・編集、見出し／本文と墨色／朱色の切り替え。
- テキストの外側タップによる確定・終了。編集時のBackは未確定の変更がある場合だけ破棄を確認し、ツール・検索・選択のBackは通常キャンバスへ段階的に戻ります。
- パン・ピンチズーム、要素の選択・移動、Undo/Redo。表示の移動や倍率変更では保存された配置を変えません。
- 要素・複数選択を移動中に指を画面端で保持すると自動panし、指を離さず遠くへ運べます。中央へ戻ると停止し、一回の移動を一回の保存・Undoとして扱います。取消と検証範囲は[Spec008](specs/008-drag-edge-auto-pan/spec.md)と[検証手順](specs/008-drag-edge-auto-pan/quickstart.md)を参照してください。
- 四角・丸・名前付き囲みの作成・サイズ変更、囲みと中身の移動、矢印の接続・曲げ・反転、
  複数選択とまとめて移動、空白の長押しドラッグによる余白挿入。
- Jetpack Ink によるペン／マーカーの手書きとスタイラス入力。手書きも選択・移動・削除・保存できます。
- Semantic Zoom による近・中・遠の表示、囲みへのフィット、ボード内のテキスト・囲み名の検索と結果の前後移動。
- 現在の検索結果または選択範囲が完全に画面外になると、画面端の方向表示から戻れます。複数選択は1まとまり、同じ単一対象は統合し、必要時だけ最大2個を表示します。対象と検証範囲は[Spec009](specs/009-offscreen-direction-indicators/spec.md)を参照してください。
- 視点の戻る／進むを編集Undo/Redoと独立して使えます。検索・fit・倍率切替・pan/pinchを辿り、画面外の編集Undo/Redoでは変更位置を表示します。視点操作で編集Redoや保存内容を変えません。対象と寿命は[Spec010](specs/010-viewport-history/spec.md)を参照してください。
- 最終編集順のボード一覧とサムネイル、作成・再開・名前変更・複製・削除、最後に開いたボードの復元。
- 初回の空ボードでの操作案内と、一覧の「使い方」からの再表示。
- ボード全体または選択範囲の画像プレビュー、端末への画像保存、コピー、Android 共有メニューへの受け渡し。
- ボードと要素のローカル専用保存。内容と位置関係を保って再起動後に復元します。
- Android共有メニューからテキスト・URLを取り込み、追加先を確認してボードへ配置できます。
- 写真・画像ファイルpickerから静止JPEG・PNG・WebPを追加できます。画像も選択・移動・縦横比resize・削除・Undo/Redo・囲み・矢印・複製・出力へ参加し、元ファイルに依存せず端末内に保存します。任意の代替テキストは画像メニューから編集でき、空欄は「画像」と読み上げます。検証範囲は[Spec013の検証手順](specs/013-image-element/quickstart.md)を参照してください。

現在の対象外は、クラウド同期・端末間同期、リアルタイム共同編集、AI による自動整理、
画像Share Target受信、動画、画像の画素編集、OCR、手書き認識・図形の自動補正です。
自動整列・グリッド吸着・自動分類は行いません。

## APK 配布と実機確認

[Android 実機確認 runbook](docs/runbooks/device-verification.md) に配布と確認の手順をまとめています。

- **PR debug APK**: PR の [Android checks](https://github.com/reitojike/think-canvas/actions/workflows/android.yml)
  から `think-canvas-debug-apk` artifact（7 日保存）を zip で取得します。
  applicationId は `com.thinkcanvas.internal.debug` です。詳しくは [配布経路](docs/runbooks/device-verification.md#配布経路) を参照してください。
- **署名済み internal APK**: 継続利用・更新確認には [Releases](https://github.com/reitojike/think-canvas/releases)
  の pre-release にある `think-canvas.apk`（`com.thinkcanvas.internal`）を使います。
  作成は `main` から手動実行する Internal APK release と [pre-release の作成手順](docs/runbooks/device-verification.md#pre-release-を作る) に従います。
- **実機確認**: 初回インストール、Obtainium、更新後のボード保持の確認は
  [端末での確認手順](docs/runbooks/device-verification.md#端末で確認する) を参照してください。

debug APK と internal APK は applicationId と署名が異なり、端末上で併存できます。
相互に上書き更新はできません。

## 開発環境

- Android Studio と Android SDK Platform 37 / Build Tools 36.0.0
- JDK 25
- インターネット接続（初回の Gradle と Android 依存関係の取得に必要）
- Spec Kit の操作には Python 環境と `uv` が必要です

`compileSdk` / `targetSdk` は Android 17 / API 37、`minSdk` は API 26 です。
[Android checks](.github/workflows/android.yml) は Ubuntu 26.04 / JDK 25 で lint・単体テスト・
debug build・Room schema の差分・公開情報境界を確認します。必須の Gradle Managed Device
（GMD）は Pixel 9 / Android 17（API 37、`google_apis`、`x86_64`）で、
`:app:pixel9Api37DebugAndroidTest` を実行します。
Windows ローカルの GMD 検証は [Windows GMD 検証](docs/runbooks/windows-android-gmd.md) を参照してください。

プラグインとライブラリの版数は [root の Gradle 設定](build.gradle.kts) と
[app の Gradle 設定](app/build.gradle.kts)、Gradle の版数は
[Gradle Wrapper の設定](gradle/wrapper/gradle-wrapper.properties) を参照してください。

リポジトリを clone して Android Studio で開き、SDK Platform 37 をインストールしてください。
コマンドラインでは `sdkmanager "platforms;android-37.0" "build-tools;36.0.0"` を使います。
Android Studio が作成する `local.properties` は追跡しません。コマンドラインでは JDK 25 と
`ANDROID_HOME` または `ANDROID_SDK_ROOT` を設定し、Windows なら次を実行します。

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

macOS / Linux では各 Gradle コマンドの先頭を `./gradlew` に置き換えます。ビルド成果物は
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
Codex では `$speckit-constitution` などの skill 名を使います。各機能の成果物は
[`specs/`](specs/) 内にあり、公開仕様は上の「現在の状態」から参照できます。
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
