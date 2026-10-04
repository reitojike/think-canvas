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
- [x] T012 convergeでspec/plan/tasksと実装を突合し残差を `tasks.md` に追記・解消する。現行完成済Spec009状態を事実ベースで同期し、公開差分確認後PRを作る。最終head両CI/canonical reviewを収束しmergeする。
- [x] T013 merge後に最新Issue77 ACを個別検証しproof/checkbox/close、親81の子状態を同期する。親の実機dogfoodingを子のCIから推定しない。

## 依存と実行方針
T001→T002→tests T003/T004/T008→T005/T006→T007→T009/T010→T011→T012→T013。
T003/T008は別fileで並行可能。CanvasScreen編集は一担当で順序実行。local Gradle/GMDは一つずつ、GMD中source/docs変更なし。
MVPはUS1。US2の内容操作/保存不変を確認しUS3の寿命/guardへ広げる。全作業はmerge後Issue完了まで既に許可されている。

## Phase 4: Convergence

- [x] T014 HIGH: `ContentHistoryFocus.kt` と `CanvasScreen.kt` で接続先変更により候補となった未変更arrowを実際のbefore/after render geometryで絞り、色だけの変更等で変わらないarrowをfitへ含めない。`ContentHistoryFocusTest.kt` と `ViewportHistoryInteractionTest.kt` へ表示不変arrowとgeometry変化arrowの回帰を追加する。FR-006、SC-003、Edge Cases「未変更要素をfitへ入れない」（partial）。
- [x] T015 HIGH: PR90の第三補正前family-level completeness checkpointでcamera writerを全列挙。`CanvasScreen.animateViewport` のFloat補間が最小倍率を下回る不足を既存scale clampと正常completionのexact targetで補正し、`ViewportHistoryInteractionTest` のregion fit→最小倍率back/forward往復で回帰を追加する。FR-001/003/008、SC-001/004。checkpointで同じauthority/responsibility/review/rollbackの追加一roundに限定。最終CI/reviewはT012で収束する。

## Phase 5: Convergence

- [x] T016 HIGH: `ViewportHistoryInteractionTest.kt` の検索entryと検索中Undo/Redoで、native入力readinessと保存Running→Idleの再開境界を明示する。実Room保存を保持したtest用request gateでRunningを観測し、durable完了後のIME復帰→一段階IME Back→native非表示を待ってからview操作する。内容/Room/保存数/Redo/camera/一entryのassertとproduction guardを維持し、標準focusedと最終153件CIを確認する。FR-005/006/007/010、SC-002/003/004（partial）。[統合後family checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5978097837)の同PR追加一roundに限定する。
- [x] T017 HIGH: `EdgeAutoPanTest#multiSelectionTranslatesTextShapeAndFreeArrowTogether` の最初の高速native MOVEとreleaseの座標を軽いWindow.Callback delegateで観測し、開始→previewとpreview→実配達UPのrenderを元2pxで比較する。全選択delta・Room/save/Undo/Redo一回とlayout/scale不変を維持する。既存Spec008 FR-004/005/006/010、SC-002/004のnative oracle不足を同PRの有限test/docs差分で補正する。制御RED/GREENのtimestamp/probeをfinalから除去し、更新head全153件CIで収束する。旧Issue78の原因未確定の歴史を変更しない。
- [x] T018 HIGH: `ViewportHistoryInteractionTest#unchangedOffscreenAttachedArrowDoesNotMoveVisibleColorUndo` で、色edit後の検索focusとUndoの前後を分離する。既存waitForIdle後に対象の画面内preconditionをassertし、Undo直前のworld focusを基準に同じ許容差・内容/保存assertで比較する。FR-006/SC-003（partial）。観測非再現の限界を記録し、probeを除去してfocusedと最終全CIを確認する。製品/search effect/geometry filter/入力/CI/launcherは変更しない。

## Phase 6: canonical review convergence

- [x] T019 HIGH: `ViewportHistory.record` の無移動更新で未開始検索groupを消費する不足を補正し、`ViewportHistoryTest` に既存履歴のあるnear→最初の実移動→同group継続→往復を追加する。Spec010 FR-003/004、SC-001。[検索family checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5978808072)の同PR追加一round。
- [x] T020 HIGH: `CanvasScreen` のUndo/Redo検索再focus抑止時に現在位置だけを新件数へclampする。`ViewportHistoryInteractionTest` に2→1→0→1→2の件数/現在位置/current indicator、visible camera、内容/Room/saveのnative回帰を追加する。Spec010 FR-005/006/007、merge済Spec004 FR-013/014/016、SC-003/004。camera/内容authorityを変えず、更新head全154件CIとcanonicalを再収束する。

## Phase 7: native IME Back convergence

- [x] T021 HIGH: `ViewportHistoryInteractionTest.Harness.hideSearchIme` のIME表示確認後の直接Activity dispatcherを、既存Neutral回帰と同じplatform `GLOBAL_ACTION_BACK` に合わせる。mergedSpec007のIMEだけ閉じ入力維持、Spec010 FR-005/006/007/010の観測経路を補正し、field/query・native IME非表示・内容/Room/save/cameraのassertを維持する。[native Back checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5979053043)の追加一round。旧CIの具体的なinsets timing原因は未確定として保持し、標準focusedと更新head全154件CI/reviewで再確認する。

## Phase 8: search group cycle convergence

- [x] T022 HIGH: `ViewportHistory.kt` で同一検索groupの始点復帰時にそのgroupが積んだanchorだけを除去し、近似重複で保持された既存履歴を守る。`ViewportHistoryTest.kt` にowned/unowned始点復帰、再開始、capacity、別boundaryの回帰を追加する。Spec010 FR-003/004、SC-001。[group cycle checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5979349931)の追加一round。旧実装RED→unit/basic・標準focused→更新head全154件CI/canonicalで再収束する。

## Delivery同期（2026-10-04）

T012/T013/T016〜T022は[PR90](https://github.com/reitojike/think-canvas/pull/90)のhead 2dd8b354a3でCI154件/canonicalを収束し、merge 047b0a1、[Issue77完了proof](https://github.com/reitojike/think-canvas/issues/77#issuecomment-5979642387)でdelivery完了を確認した。次の必要な統合でcheckboxを同期するという記録に従い、本変更で反映する。過去CIの未確定原因を新しい証拠から推定し直さない。
