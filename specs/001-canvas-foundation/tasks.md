# 実装タスク: Canvas Foundation

**入力**: [仕様](spec.md)、[計画](plan.md)、[データモデル](data-model.md)、[操作契約](contracts/interaction.md)

## Phase 1: セットアップ

- [x] T001 `build.gradle.kts` と `app/build.gradle.kts` に Room 3.0.3、KSP 2.3.12、JUnit と schema export を設定する（KSP は Issue #10 で更新）

## Phase 2: 共通基盤

- [x] T002 `app/src/main/java/com/thinkcanvas/internal/canvas/Viewport.kt` に世界座標変換、15～300% 制限、ピンチ中心固定を実装する
- [x] T003 `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に TextElement と作成・編集・移動の 80 件 Undo/Redo を実装する
- [x] T004 `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` に世界座標と履歴の不変条件テストを追加する

## Phase 3: US1 その場に書き残す

**独立検証**: 新規作成・編集・色/種別変更、オフライン再起動後の内容と位置を確認する。

- [x] T005 [US1] `app/src/main/java/com/thinkcanvas/internal/data/CanvasDatabase.kt` に固定 Board、TextElement と Room 3 DAO/schema v1 を定義する
- [x] T006 [US1] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` に最初のボード、空白 tap、テキスト Draft、タイトル/本文・墨/朱・確定/取消を実装する
- [x] T007 [US1] `app/src/main/java/com/thinkcanvas/internal/data/CanvasStore.kt` と `app/src/main/java/com/thinkcanvas/internal/MainActivity.kt` に保存状態の読込、確定操作の保存、画面接続を実装する

## Phase 4: US2 空間を見渡す

**独立検証**: 要素上/空白上からパン、ピンチ、世界座標の保持を確認する。

- [x] T008 [US2] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` に 1 指 pan、2 指 pinch と要素起点 pan を実装する
- [x] T009 [US2] `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` に反復 pan/zoom でも要素座標が変わらない検証を追加する

## Phase 5: US3 配置と履歴

**独立検証**: 選択、長押し移動、作成・編集・移動の Undo/Redo と再起動を確認する。

- [x] T010 [US3] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` に選択と長押し移動、代替の accessibility action、左下の Undo/Redo を実装する
- [x] T011 [US3] `app/src/main/java/com/thinkcanvas/internal/MainActivity.kt` に移動と Undo/Redo 後の保存を接続する
- [x] T012 [US3] `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` に作成・編集・移動の戻し/再適用を検証する

## Phase 6: 仕上げ

- [x] T013 `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` と `app/src/main/res/values/strings.xml` に日本語表示、semantics、44dp 以上のボタン領域を整える
- [x] T014 `app/src/test/java/com/thinkcanvas/internal/data/CanvasDatabaseTest.kt` に Room 3 の再オープンと確定状態の保存テストを追加する
- [x] T015 `specs/001-canvas-foundation/quickstart.md` の検証を実施し、`scripts/check-public-boundary.ps1` と `git diff --check` を通す

## Phase 7: 追加要件（モック準拠と CI）

- [x] T016 [US1][US3] `CanvasScreen.kt` の対象コンポーネントを HTML モックの見た目とインライン編集操作に合わせ、空・選択・編集の画面を照合する
- [x] T017 `.github/workflows/android.yml` に Android lint、ユニットテスト、デバッグビルド、公開情報境界チェックを組み込み、runbook に実際のコマンドを反映する

## 依存関係

T001 → T002/T003 → T004 → US1 → US2 → US3 → T013/T014/T015。US1 は永続化と入力、US2 は視点操作、US3 は選択/履歴を単独で検証できる。異なるファイルの作業は並行可能だが、gesture の単一調停箇所は逐次統合する。

## 実装方針

US1 を最初の MVP とし、世界座標を崩さず US2/US3 を積み上げる。実装後に全タスクと受け入れ条件を照合し、残差は converge で追記する。

## Phase 8: Convergence

- [x] T018 保存完了を待ってから確定操作を閉じ、保存失敗時に同じ snapshot を再試行できるようにする per FR-009 (partial)
- [x] T019 gesture 中断時に preview を破棄し、未確定移動を表示・保存へ残さない per 境界条件 (partial)
- [x] T020 Room 再オープンテストの名称と実際の検証範囲を一致させる per T014 (partial)
- [x] T021 保存待ち・保存失敗時に編集ツールの無効状態を表示と accessibility semantics に反映し、再試行操作は有効に保つ per accessibility contract (partial)
- [x] T022 保存ブロック中の要素と移動グリップの accessibility action を無効化する per accessibility contract (partial)
- [x] T023 ボード名・履歴・倍率表示の範囲を canvas tap 判定から除き、表示部から Draft を作らない per US1 (partial)
