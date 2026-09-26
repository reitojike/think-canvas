# 実装タスク: Spatial Organization

**入力**: [仕様](spec.md)、[計画](plan.md)、[データモデル](data-model.md)、[操作契約](contracts/interaction.md)
**検証**: 保存・空間配置・接続の不変条件を JUnit で確認し、画面操作は [quickstart.md](quickstart.md) で確認する。

この完了済みタスクに記載した旧 source path と v1→v2 migration は #3 実装時の記録です。Issue #26 以降の source path と保存形式は [計画](plan.md) を参照します。

## Phase 1: セットアップ

- [x] T001 JVM の単体テストで Room 3 の migration を再現できる `sqlite-bundled-jvm` を維持する。`room3-testing` は instrumentation を必須とするため追加しない

## Phase 2: 共通基盤

- [x] T002 `app/src/main/java/com/thinkcanvas/internal/canvas/SpatialElement.kt` に図形・囲みと矢印端点の型を定義し、幅・高さを正の有限値、接続アンカーを 0〜1 の有限値に制約する
- [x] T003 [P] `app/src/main/java/com/thinkcanvas/internal/data/CanvasDatabase.kt` に schema v2 の `spatial_elements`・`arrow_elements`、v1→v2 自動 migration と単一 transaction の DAO 操作を追加し、既存二表を保持する
- [x] T004 [P] `app/src/test/java/com/thinkcanvas/internal/data/CanvasMigrationTest.kt` に v1 のボード名・文字・位置を入れて v2 への移行と新表の空状態を JVM で検証するテストを追加する
- [x] T005 `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に全要素の確定 snapshot と一操作一履歴の 80 件 Undo/Redo を導入し、既存文字操作の結果を保持する
- [x] T006 `app/src/main/java/com/thinkcanvas/internal/data/CanvasStore.kt` と `app/src/main/java/com/thinkcanvas/internal/MainActivity.kt` に全要素の読込・transaction 保存を接続し、ボード名を上書きしない
- [x] T007 `app/src/main/java/com/thinkcanvas/internal/canvas/SpatialGeometry.kt` に世界座標上の論理境界、中心、線近傍、楕円外周、接続端表示の純粋計算を実装する

## Phase 3: US1 考えを囲んでまとめる

**独立検証**: 三種類の作成・サイズ変更・入れ子移動と v1→v2 後の保存・再起動を確認する。

- [x] T008 [US1] `app/src/test/java/com/thinkcanvas/internal/canvas/SpatialGeometryTest.kt` に中心判定、重なる囲み、入れ子移動の対象が一度ずつ決まるテストを追加する
- [x] T009 [US1] `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に四角・丸・囲みの作成、名前/色変更、最小 40×30 のサイズ変更、囲み内の一括移動と Undo/Redo を追加する
- [x] T010 [US1] `app/src/main/java/com/thinkcanvas/internal/canvas/SpatialElements.kt` に墨/朱の四角・丸と名前付き破線囲み、選択枠、右下のサイズ変更つまみと要素ごとの semantics を描画する
- [x] T011 [US1] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasControls.kt` と `app/src/main/res/values/strings.xml` に右下の＋、図形・囲みツール、上部案内、名前編集と対象メニューを日本語で追加する
- [x] T012 [US1] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` にツールの tap/drag、線近傍 hit-test、長押し移動/メニュー、サイズ変更、2 指中断と preview 破棄、移動後に囲みに入った/出た案内を統合する
- [x] T013 [US1] `app/src/test/java/com/thinkcanvas/internal/data/CanvasDatabaseTest.kt` と `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` に図形・囲みの再オープン、入れ子移動と履歴の検証を追加する

## Phase 4: US2 矢印で関係を示す

**独立検証**: 接続・自由端・付け替え・曲げ・反転・削除と、接続先の移動後の追従を確認する。

- [x] T014 [US2] `app/src/test/java/com/thinkcanvas/internal/canvas/SpatialGeometryTest.kt` に矩形/楕円の外周交点、接続先移動時の自由端不変、曲げの反転不変を検証するテストを追加する
- [x] T015 [US2] `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に矢印作成、端点の接続/解除、曲げ、反転、接続先削除時の連鎖削除と一操作 Undo/Redo を追加する
- [x] T016 [US2] `app/src/main/java/com/thinkcanvas/internal/canvas/SpatialElements.kt` に矢印の線と頭、接続/自由を区別する端点、中間つまみと semantics を追加する
- [x] T017 [US2] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` と `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasControls.kt` に矢印作成、端点 drag、曲げ、反転メニュー、接続 feedback を統合する
- [x] T018 [US2] `app/src/test/java/com/thinkcanvas/internal/data/CanvasDatabaseTest.kt` に矢印の接続先・自由端・曲がりの再オープンと削除後の参照整合を追加する

## Phase 5: US3 複数の要素をまとめて動かす

**独立検証**: 囲み選択、追加・解除・選択数、複数種の一括移動と一回の Undo を確認する。

- [x] T019 [US3] `app/src/test/java/com/thinkcanvas/internal/canvas/SpatialGeometryTest.kt` に多角形の内側判定、矢印の両端条件、重複しない選択対象を検証するテストを追加する
- [x] T020 [US3] `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に選択集合に対する一括移動を追加し、選択した矢印の自由端だけを移動する規則を守る
- [x] T021 [US3] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` と `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasControls.kt` に囲み選択、長押し追加、tap 解除、選択数表示、まとめ移動と支援技術向けの選択操作を追加する
- [x] T022 [US3] `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` に入れ子囲みと複数選択が重なっても各要素が一度だけ動き、Undo が一回で戻るテストを追加する

## Phase 6: US4 余白を作る

**独立検証**: 横/縦、全体/囲み内、境界をまたぐ伸長、対象外の位置保持と Undo/Redo を確認する。

- [x] T023 [US4] `app/src/test/java/com/thinkcanvas/internal/canvas/SpatialGeometryTest.kt` に横/縦の押し出し側、境界をまたぐ図形・囲みの伸長、最小囲みの範囲と対象外不変のテストを追加する
- [x] T024 [US4] `app/src/main/java/com/thinkcanvas/internal/canvas/BoardState.kt` に開始時 snapshot で対象を固定する余白挿入と一操作 Undo/Redo を追加する
- [x] T025 [US4] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` と `app/src/main/java/com/thinkcanvas/internal/canvas/SpatialElements.kt` に空白長押しの輪、方向決定、斜線帯、対象の薄朱 preview、取消と確定を追加する
- [x] T026 [US4] `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasControls.kt` と `app/src/main/res/values/strings.xml` に余白の案内・短い通知・振動なしでも読める状態を追加する

## Phase 7: 仕上げ

- [x] T027 `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasScreen.kt` と `app/src/main/java/com/thinkcanvas/internal/canvas/CanvasControls.kt` に各 gesture の代替操作、48dp 以上の新しいつまみ、保存失敗時の無効状態を確認して不足を直す
- [x] T028 `specs/002-spatial-organization/quickstart.md` の受け入れシナリオと HTML モックの対象状態を照合し、実機で未確認の事項は明示する
- [x] T029 `app/src/test/java/com/thinkcanvas/internal/canvas/BoardStateTest.kt` と `app/src/test/java/com/thinkcanvas/internal/data/CanvasDatabaseTest.kt` の全種・一操作履歴・再起動のテストを通し、lint、デバッグビルド、公開情報境界、`git diff --check` を実行する

## 依存関係

T001/T002 → T003/T004/T005/T007 → T006 → US1 → US2 → US3 → US4 → T027〜T029。US2 の矢印は US1 の図形へ接続できるようにする。US3 と US4 は同じ gesture 調停箇所を使うため、統合は順番に行う。異なるテストファイルや schema の作業は、共通のモデル確定後なら並行できる。

## 実装方針

US1 を最初の検証可能な増分とする。各段階で #2 の文字作成・編集・パン・ズーム・保存を壊していないことを確認し、最後に全要件を converge で照合する。
