# Tasks: 条件付きpan慣性

## Phase 1: Setup

- [x] T001 specs/015-conditional-pan-fling/spec.md と research.md に利用者判断と標準primitiveのcensusを記録する。
- [x] T002 specs/015-conditional-pan-fling/plan.md と contracts/navigation.md に有限surfaceとConstitution整合を定義する。

## Phase 2: Foundation

- [x] T003 app/src/androidTest/java/com/thinkcanvas/canvas/FlingGestureTest.kt に実Activity/session、native入力、camera/保存観測を用意する。

## Phase 3: US1 精密操作と払い (P1)

- [x] T004 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/FlingGestureTest.kt で低速/高速/pause/cancelとcontent不変を確認する。（FR-001/002/005/006、SC-001）
- [x] T005 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt と PanFling.kt にDOWN/MOVE/UPの標準VelocityTracker、速度条件と標準decayを統合する。（FR-001/002/006）

## Phase 4: US2 割り込み (P1)

- [x] T006 [US2] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt と PanFling.kt のInitial DOWN、Back、admission、lifecycle、frame owner guardと停止境界を統合する。（FR-003）
- [x] T007 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/FlingGestureTest.kt でtouch/pinch/Back/editor/tool/lifecycleの同期停止と次gestureを確認する。（FR-003、SC-002）

## Phase 5: US3 表示履歴 (P2)

- [x] T008 [US3] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt と PanFling.kt でpan originをboundaryへ移し、自然/途中停止を一度recordする。（FR-004）
- [x] T009 [US3] app/src/androidTest/java/com/thinkcanvas/canvas/FlingGestureTest.kt でback/forward一回とRoom/save/編集Undo不変を照合する。（FR-004/005、SC-003）

## Phase 6: Verification

- [x] T010 specs/015-conditional-pan-fling/quickstart.md にfocused GMD、lint/unit/build/public boundaryとconverge結果を記録する。
- [ ] T011 specs/015-conditional-pan-fling/quickstart.md にPixel9a/Android17の比較結果を記録する。（FR-007、SC-004、外部評価待ちはIssue open）
- [ ] T012 PRでfinal-head全CI/review/fresh base/thread0を確認してmergeする。Issue #102の全ACは実機結果を含めて別途判定する。

## Dependencies and strategy

T001→T002→T003→US1→US2→US3→T010→T012。T011は実機結果待ち。初期sliceは低速/高速の対比、続いて取消と履歴を同じPRで完成する。同じCanvasScreen/testを逐次編集する。researchだけをSpec Kitの指示によりagentへdispatchした。独立なnative実行の並列起動は行わない。
