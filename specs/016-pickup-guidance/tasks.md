# Tasks: 長押しpickupの案内

## Phase 1: Setup
- [x] T001 採択済み判断と現行mainの範囲を specs/016-pickup-guidance/spec.md に固定する。

## Phase 2: Foundation
- [x] T002 一時channelとcleanup契約を specs/016-pickup-guidance/data-model.md と contracts/pickup.md に定義する。

## Phase 3: US1 (P1)
Goal: 成立後の案内とrelease/dragの意味を明示。Independent Test: native held→menu/dragとLongPress一回。
- [x] T003 [US1] held案内とrelease/dragを app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt で観測する。
- [x] T004 [US1] 独立pickup channelと成立案内を app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt に実装する。

## Phase 4: US3 (P1)
Goal: 残留をなくしaccessibilityを保つ。Independent Test: held→cancel/Back、長時間保持。
- [x] T005 [US3] 終了/取消/Back/pinch/stylus/guard失効でcueを消し、pickupだけpolite live regionを app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt に付ける。
- [x] T006 [US3] 長時間保持/cancel/Backと状態不変を app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt で確認する。

## Phase 5: US2 (P2)
Goal: 画像と集合/追加/blankの差を伝える。Independent Test: 各案内と結果、画像枠とselection不変。
- [x] T007 [US2] 集合/追加/blank案内を app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt に実装する。
- [x] T008 [US2] movingのみ画像のtint/solid枠を app/src/main/java/com/thinkcanvas/canvas/ImageElements.kt に実装する。
- [x] T009 [US2] 画像/集合/選択追加/blankを app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt で検証する。

## Phase 6: Convergence
- [x] T010 lint/unit/build、native focused/fullと保存/Undo回帰を実行し specs/016-pickup-guidance/quickstart.md に証拠を残す。
- [ ] T011 Pixel 9a/Android17へAPK配布し理解しやすさ/誤操作を Issue #103 に記録する。未実施ならIssue open。
- [ ] T012 現行headのCI/review/thread/baseを確認し Issue #103 / PRにmerge gateを記録する。

## Dependencies & Execution Order
T001→T002→US1→US3→US2→T010。T011は外部実機評価、T012の機械gateと区別する。
US1はMVPだが全storyを同じPRで検証する。US3は寿命、US2はfamily表示が追加の独立観測対象。

## Parallel Opportunities
US2のImageElements描画とテストfixture調査は別ファイルなら並行可能。実装は同じCanvasScreenを触るため順次行う。GMDは一件ずつ、他Gradleと重ねない。

## Phase 7: Convergence

- [x] T013 空白pickupのBackをselection/listより先に消費する per FR-004、US3/AC1 (partial)。app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt と app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt で、選択あり/なしのblank held→Back→UPと内容不変を確認する。
