# Tasks: 視点履歴と編集変更表示

**Input**: spec/plan/research/data-model/contracts。テストはSC-001～004に従う。
**Format**: ID / [P]独立file / Story / 完了対象path。

## Setup / Foundation
- [x] T001 authorityとPO回答を `specs/010-viewport-history/spec.md` へ記録し、clarify/plan/checklistを完了する。
- [x] T002 tasks後のread-only analyzeでFR10/SC4/scenario12のcoverageとConstitutionを確認する（報告はquickstart.md）。

## US1 P1 - 視点だけ戻る/進む（MVP）
- [x] T003 [P] [US1] `app/src/test/java/com/thinkcanvas/canvas/ViewportHistoryTest.kt` に復元/分岐/duplicate/group/80上限/無効測定/resize/densityのテストを先に追加する。
- [x] T004 [US1] `app/src/androidTest/java/com/thinkcanvas/canvas/ViewportHistoryInteractionTest.kt` に全navigation family/native pan-pinch-cancel/内容Redo保存不変/独立controlを検証するテストを先に追加する。
- [x] T005 [US1] `app/src/main/java/com/thinkcanvas/canvas/ViewportHistory.kt` に独立したworld中心/倍率の有限stackとcamera Stateを実装する。FR-001/003/004「新navigationで視点forwardだけ破棄」。
- [x] T006 [US3] `app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt` と `MainActivity.kt` でboard session所有とCanvasScreenへの受け渡しを実装する。FR-008「process終了後のstack永続化はしない」。
- [x] T007 [US1] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` に全navigation boundary/検索group/gesture完了/restore非記録/conditional48dp button/hidden hit/live guardを統合する。FR-002/009/010「取消gesture除外」「既存system Back維持」。

## US2 P1 - 編集の変更位置表示
- [x] T008 [P] [US2] `app/src/test/java/com/thinkcanvas/canvas/ContentHistoryFocusTest.kt` に全family/attached arrow/multi/消失/未変更除外の差分・boundsテストを先に追加する。
- [x] T009 [US2] `app/src/main/java/com/thinkcanvas/canvas/ContentHistoryFocus.kt` にbefore/after差分と存在after/消失before boundsを実装する。
- [x] T010 [US2] `CanvasScreen.kt` と `ViewportHistoryInteractionTest.kt` にvisible不移動/offscreen focus/multi fit/消失/auto一viewentry/一save/Redo維持を統合・検証する。FR-005/006/007「視点backによって内容を再適用/再取消しない」。

## US3 P2 / Polish
- [x] T011 [US3] `ViewportHistoryInteractionTest.kt` の再生成/board別session/guard/stale action/hidden chrome/次gestureを実装し、既存runbookのlint/unit/build/focused GMDをsource固定で実行する。証拠は `specs/010-viewport-history/quickstart.md`。
- [ ] T012 convergeでspec/plan/tasksと実装を突合し残差を `tasks.md` に追記・解消する。現行完成済Spec009状態を事実ベースで同期し、公開差分確認後PRを作る。最終head両CI/canonical reviewを収束しmergeする。
- [ ] T013 merge後に最新Issue77 ACを個別検証しproof/checkbox/close、親81の子状態を同期する。親の実機dogfoodingを子のCIから推定しない。

## 依存と実行方針
T001→T002→tests T003/T004/T008→T005/T006→T007→T009/T010→T011→T012→T013。
T003/T008は別fileで並行可能。CanvasScreen編集は一担当で順序実行。local Gradle/GMDは一つずつ、GMD中source/docs変更なし。
MVPはUS1。US2の内容操作/保存不変を確認しUS3の寿命/guardへ広げる。全作業はmerge後Issue完了まで既に許可されている。

## Phase 4: Convergence

- [x] T014 HIGH: `ContentHistoryFocus.kt` と `CanvasScreen.kt` で接続先変更により候補となった未変更arrowを実際のbefore/after render geometryで絞り、色だけの変更等で変わらないarrowをfitへ含めない。`ContentHistoryFocusTest.kt` と `ViewportHistoryInteractionTest.kt` へ表示不変arrowとgeometry変化arrowの回帰を追加する。FR-006、SC-003、Edge Cases「未変更要素をfitへ入れない」（partial）。
- [x] T015 HIGH: PR90の第三補正前family-level completeness checkpointでcamera writerを全列挙。`CanvasScreen.animateViewport` のFloat補間が最小倍率を下回る不足を既存scale clampと正常completionのexact targetで補正し、`ViewportHistoryInteractionTest` のregion fit→最小倍率back/forward往復で回帰を追加する。FR-001/003/008、SC-001/004。checkpointで同じauthority/responsibility/review/rollbackの追加一roundに限定。最終CI/reviewはT012で収束する。
