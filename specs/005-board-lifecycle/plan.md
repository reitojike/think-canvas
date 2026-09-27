# 実装計画: Board Lifecycle

**Branch**: `codex/issue-6-board-lifecycle` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

## Summary

既存の Room 3 保存をボード ID ごとの操作に拡張し、複数ボードの一覧と編集切替を提供する。一覧のカードは遠景表現を再利用する。共有は選択した snapshot から独立したプレビューと PNG を描画し、Android の標準保存・コピー・共有経路に渡す。既存の単一ボードと全要素は schema を作り直さず保持する。

## Technical Context

**Language/Version**: Kotlin、JDK 25 でビルド、bytecode 17
**Primary Dependencies**: 既存の Jetpack Compose、Room 3、AndroidX Ink。画像 URI の提供には既存の AndroidX Core `FileProvider` を用いる
**Storage**: 既存 `thinkcanvas.db` schema v2 の boards と4種の要素表、端末内設定の最後に開いた ID と案内完了、明示操作で作る PNG
**Testing**: JUnit のボード複製・範囲・寸法・保存不変条件、Room を使う Android instrumentation テスト、Android 17 エミュレーターで一覧・保存・共有・TalkBack、lint・ビルド
**Target Platform**: Android API 26–37
**Project Type**: 単一 module の Android アプリ
**Performance Goals**: 20件の一覧を操作でき、プレビューを含む表示で目立つ入力停止を起こさない。画像出力時も UI を長時間占有しない
**Constraints**: オフライン、元データを変えない共有、画像は 1–2 px/世界座標単位・最大 4096 px / 16 MP、PRD と HTML モックは非公開
**Scale/Scope**: 複数ボード、文字・図形・囲み・矢印・手書き、一覧と初回案内、全体/選択範囲の画像共有

## Constitution Check

- **製品 authority**: [spec.md](spec.md) は非公開 PRD のボード一覧、共有、案内を観測可能な条件に整理した。HTML モックは配置と色、シートの操作感の参考とする。
- **Standard-first**: 一覧の長押しメニュー、削除確認、Android の共有メニュー、メディア保存、文書作成、content URI を優先する。ボードの自由配置と遠景プレビューは製品固有なので [仕様](spec.md) に範囲を限定する。
- **空間配置**: 複製では座標と関係を保つ。プレビューと画像出力は snapshot の読み取りのみで、元の配置を変えない。
- **ローカル優先**: ボードと利用状態は端末内。共有先への送信は利用者が「ほかのアプリ」を選んだ場合だけ Android に委ねる。
- **仕様先行**: 保存と削除、案内、選択範囲、画像の対象・背景を spec と [操作契約](contracts/interaction.md) で確定してから実装する。
- **公開境界**: PRD/HTML モックの本文、ローカルパス、実際のボード内容、画像 cache、端末情報を repository に追加しない。

設計後も上記 gate に違反しない。画像共有に広いストレージ権限や独自の外部サービスは導入しない。

## 設計判断

- `CanvasDao` の ID 1 固定の query をボード ID 引数に置き換え、一覧・作成・名前更新・削除を追加する。4種の要素の保存と削除は対象ボード内でまとめて行い、存在しないボードへの保存は失敗させる。既存 schema v2 とボード id=1 はそのまま使う。
- `CanvasStore` は保存、複製、削除、切替用の読込を単一の順序列に通す。`save(boardId, snapshot)` には呼び出し時の ID と不変 snapshot を渡す。画面側でも編集中・保存中・保存失敗時の遷移を制御する。
- ボード複製は純粋な snapshot 変換で全要素 ID を新規生成し、矢印の接続先を新 ID へ変換する。Undo/Redo は開いたボードの `BoardState` に閉じ、削除で破棄する。
- 最後に開いた ID と案内を閉じた状態は端末内設定で保持する。初回起動時は空のボードを1件用意し、削除で0件になった後は一覧の空状態を表示する。
- `MainActivity` は読み込み中、一覧、ボードの画面状態を管理する。`CanvasScreen` に表示名と一覧へ戻る操作を渡し、最終保存が完了してから画面を移る。開くときは snapshot の外接範囲に viewport を合わせる。
- 一覧カードは `LazyVerticalGrid` の2列とし、ボード名・日付・遠景の縮小図を表示する。縮小図は遠景で可視の図形・見出し・矢印・手書きを描き、マーカーとペンの重なり順をキャンバスに合わせる。カードの長押しは下部の操作シートを開く。案内と共有も下部シートとして表示し、TalkBack のラベルと操作を与える。
- 画像対象の計算、全体と選択範囲の要素抽出、余白と寸法決定を UI から分ける。余白は世界座標で上下左右に同じ `max(24, 対象長辺×0.05)` を加える。画像は原則 2 px/世界座標単位で作り、長辺4096 px・総画素16 MPの上限に合わせて1 px/単位まで縮小する。1 px/単位でも収まらない場合は失敗として利用者に示す。画像プレビューと PNG は同じ描画計画を使う。共有画像には本文・手書き・矢印を含め、操作 chrome、選択ハンドル、検索の強調は含めない。一覧カードだけに Spec 004 の遠景投影を適用する。
- 共有開始時の Compose の density・font scale・文字間隔を文字の組版へ渡す。本文・囲み名の測定結果を画像の外接範囲と描画で共有し、折り返しや行間が異なる計算で切り抜かれないようにする。組版、画像計画、描画は UI thread の外で行う。
- PNG 保存は API 29 以上で `MediaStore`、API 26–28 で文書作成画面を使う。コピーと Android 共有は `FileProvider` の cache URI とし、URI の read grant を受け手へ渡す。圧縮・書込は UI thread の外で行い、失敗時に不完全な出力を掃除する。

## Project Structure

```text
specs/005-board-lifecycle/
  spec.md                     利用者の振る舞い
  plan.md                     本文書
  research.md                 採用判断
  data-model.md               ボードと共有対象
  contracts/interaction.md   画面と画像の契約
  quickstart.md               検証手順
  tasks.md                    実装順
app/src/main/java/com/thinkcanvas/
  MainActivity.kt             画面遷移と現在のボード
  data/CanvasDatabase.kt      ID 指定 DAO
  data/CanvasStore.kt         ボード単位保存と操作順序
  canvas/CanvasScreen.kt      ボード名、一覧へ戻る、範囲共有
  board/                    一覧、案内、共有シート、画像出力
app/src/test/java/com/thinkcanvas/
  board/                    複製・共有対象・寸法の単体テスト
app/src/androidTest/java/com/thinkcanvas/
  data/                     既存データ保持・複数ボード分離
  board/                    一覧・案内・画像出力の操作
```

**構成の判断**: 既存の app module を保つ。ボードの一覧・共有は独立した `board` package に置き、保存モデルとキャンバスの操作は既存 package を拡張する。

## Complexity Tracking

外部サービスや新たな DB schema は追加しない。専用の画像描画面は全体/範囲画像に画面 UI が混入しないために必要で、プレビューと出力で同じ描画計画を使う。
