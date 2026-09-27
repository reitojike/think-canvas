# 実装作業: Board Lifecycle

**入力**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[操作契約](contracts/interaction.md)

## Phase 1: 準備

- [ ] T001 `app/src/main/java/com/thinkcanvas/data/CanvasDatabase.kt` と既存 schema JSON を照合し、id=1 の既存行を変更せず複数ボード化できる境界を確認する
- [ ] T002 `app/src/main/AndroidManifest.xml` と `app/src/main/res/xml/` に限定した共有 cache 用 `FileProvider` を設定する

## Phase 2: 共通の保存基盤

- [ ] T003 `app/src/androidTest/java/com/thinkcanvas/data/` に既存ボード保持、ボード間の要素分離、削除後の遅延保存を検証するテストを追加する
- [ ] T004 `app/src/main/java/com/thinkcanvas/data/CanvasDatabase.kt` の ID 1 固定 query と置換をボード ID 指定へ変え、一覧・作成・名前更新・対象削除を transaction で実装する
- [ ] T005 `app/src/main/java/com/thinkcanvas/data/CanvasStore.kt` で ID と snapshot を固定した保存、順序化した読込・複製・削除を実装し、存在しないボードの自動再作成を防ぐ
- [ ] T006 `app/src/test/java/com/thinkcanvas/board/` に全要素 ID と矢印接続を写す複製テストを追加し、`app/src/main/java/com/thinkcanvas/board/` に純粋な複製変換を実装する

**Checkpoint**: 既存の1件を保持しながら、複数ボードを独立に保存できる。

## Phase 3: User Story 1 - ボードを使い分ける (P1)

**目的**: 一覧から作成・再開・名前変更・複製・削除を安全に行う。

**独立した検証**: 3件を作り、各操作後と再起動後の内容・順序・最後に開いた ID を照合する。

- [ ] T007 [US1] `app/src/androidTest/java/com/thinkcanvas/board/` に一覧・切替・再起動・削除確認の UI テストを追加する
- [ ] T008 [US1] `app/src/main/java/com/thinkcanvas/MainActivity.kt` に読み込み中・一覧・編集中の画面状態と最後に開いた ID の保存を導入する
- [ ] T009 [US1] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` に実際のボード名と一覧へ戻る操作を追加し、編集中・保存中の遷移を制御する
- [ ] T010 [US1] `app/src/main/java/com/thinkcanvas/board/BoardListScreen.kt` に2列の一覧、日付、作成、開く、長押しの下部メニュー、名前変更・複製・削除確認を実装する
- [ ] T011 [US1] `app/src/main/java/com/thinkcanvas/board/BoardThumbnail.kt` に Spec 004 の遠景投影を使う縮小プレビューと空ボード表示を実装する
- [ ] T012 [US1] `app/src/main/java/com/thinkcanvas/board/` でボード全体に合う初期 viewport を求め、開いた際の配置を確認する

## Phase 4: User Story 2 - 画像で共有する (P2)

**目的**: 全体と選択範囲を同じプレビュー/PNG にし、Android の保存・コピー・共有へ渡す。

**独立した検証**: 全体・選択範囲それぞれで3出力を試し、画像内容と元データの不変性を照合する。

- [ ] T013 [US2] `app/src/test/java/com/thinkcanvas/board/` に選択・囲み・矢印の包含、空範囲、画像寸法上限、元データ不変性のテストを追加する
- [ ] T014 [US2] `app/src/main/java/com/thinkcanvas/board/SharePlan.kt` に全体/選択範囲の抽出と外接範囲・余白・画像寸法の計算を実装する
- [ ] T015 [US2] `app/src/main/java/com/thinkcanvas/board/BoardImageRenderer.kt` に本文・図形・囲み・矢印・手書きの専用描画を実装し、プレビューと PNG で共用する
- [ ] T016 [US2] `app/src/main/java/com/thinkcanvas/board/ShareSheet.kt` に対象名、画像プレビュー、保存・コピー・ほかのアプリの下部シートを実装する
- [ ] T017 [US2] `app/src/main/java/com/thinkcanvas/board/ImageDelivery.kt` に PNG 圧縮、MediaStore/文書作成、FileProvider URI、clipboard、Android 共有メニューを実装する
- [ ] T018 [US2] `app/src/main/java/com/thinkcanvas/board/BoardListScreen.kt` と `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` から全体/選択範囲の共有シートを開けるようにする
- [ ] T019 [US2] `app/src/androidTest/java/com/thinkcanvas/board/` で出力画像と保存/コピー/共有経路、キャンセル、他ボードの不変性を検証する

## Phase 5: User Story 3 - 基本操作を知る (P3)

**目的**: 初回の空ボードだけ案内し、一覧から再表示できる。

**独立した検証**: 初回、案内を閉じた後、2件目、再起動、一覧からの再表示を確認する。

- [ ] T020 [US3] `app/src/androidTest/java/com/thinkcanvas/board/` に案内の初回条件と再表示の UI テストを追加する
- [ ] T021 [US3] `app/src/main/java/com/thinkcanvas/board/GuideSheet.kt` に PRD の操作説明と「はじめる」を実装し、キャンバスの誤操作を防ぐ
- [ ] T022 [US3] `app/src/main/java/com/thinkcanvas/MainActivity.kt` と `BoardListScreen.kt` に案内完了の端末内設定と「使い方」入口を実装する

## Phase 6: 仕上げ

- [ ] T023 `app/src/main/java/com/thinkcanvas/board/` のタッチ領域、TalkBack の名前、状態、下部シートの操作順を確認する
- [ ] T024 [P] `specs/005-board-lifecycle/quickstart.md` に従い、単体テスト、Android 17 エミュレーター、lint、ビルド、公開情報境界を確認する
- [ ] T025 `specs/005-board-lifecycle/tasks.md` と実装を照合し、残る差分を converge で追記して完了させる

## 依存関係

準備 → 共通の保存基盤 → US1 → US2・US3 → 仕上げ。US2 の画像描画と US3 の案内 UI は保存基盤の後に別ファイルで進められる。US1 の一覧ができると、US2 の全体共有と US3 の再表示入口を接続できる。

## 並行できる作業の例

- T013 の共有対象テストと T020 の案内テストは異なる振る舞いを検証する。
- T015 の画像描画と T021 の案内 UI は異なるファイルで作業できる。
- T023 のアクセシビリティ確認と T024 の自動確認は独立して行える。

## 実装の進め方

まず既存データを保つ保存基盤と US1 を完成させ、複数ボードの独立性を確認する。次に共有を追加して元データ不変性を検証し、案内とアクセシビリティを仕上げる。各段階で実際の検証結果を残し、未確認の実機項目は Issue に明記する。
