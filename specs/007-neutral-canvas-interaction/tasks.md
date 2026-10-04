# 実装タスク: 通常キャンバスへの復帰

## Phase 1: 仕様と開始時checkpoint

- [x] T001 main/#71のfinite censusとPO確認をspecs/007-neutral-canvas-interaction/spec.md、plan.md、research.mdに記録する。
- [x] T002 specs/007-neutral-canvas-interaction/data-model.md、contracts/interaction.md、quickstart.md、checklists/を作成し要件をanalyzeする。

## Phase 2: 共通editor基盤

- [x] T003 app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.ktにRegionNameDraftと変更判定を追加する。newはtext.isNotEmpty、existingはtext/kind/color、regionはname != originalName、sessionIdはcopyで保持する。
- [x] T004 app/src/test/java/com/thinkcanvas/canvas/EditorExitTest.ktで空/空白、種類/色、元へ戻した内容、region空名とidentityを検証する。

## Phase 3: US1 入力のBack終了

- [x] T005 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktにeditor変更あり/なし、確認継続/破棄、IME、recreation、save guardsの回帰を追加する。
- [x] T006 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktとapp/src/main/res/values/strings.xmlにlive guard/session bound確認、region session保持、共通focus/IME/bounds cleanupを実装する。

## Phase 4: US2 ツールと通常状態

- [x] T007 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktにspatial/lasso/連続ink/Back/preview cancellation/次の操作/recreationを追加する。
- [x] T008 [US2] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktで有限Back優先順、tool状態保持、pointer continuation取消、共通preview cleanupを実装する。

## Phase 5: 検証と収束

- [x] T009 app/でlint/unit/debug/androidTest build、schema、公開境界、diff、focused/full instrumentationと既存Spec 006 regressionを検証しspecs/007-neutral-canvas-interaction/quickstart.mdへ結果を記録する。
- [x] T010 specs/007-neutral-canvas-interaction/tasks.mdにconvergeで残差を確認し、README.mdとSpec 006のBack参照を同期する。
- [x] T011 GitHub PRでfinal-head CI/canonical review/threads/baseを収束し、merge後にIssue #73の最新ACを逐条判定する。specs/007-neutral-canvas-interaction/quickstart.mdから証跡へ到達できるようにする。

## 依存・実装戦略

T001→T002→T003/T004→US1→US2→検証・converge。US1はeditorからの無保存close、US2はtoolからの復帰を独立に検証できる。テストsuiteとCanvasScreenの作業は別ファイルで並行可能だがGradle実行は一つにする。custom checklistはreviewer-ownedの未査読状態を保持し、利用者の「提案した挙動で進める」という実装許可に従い進める。

## Phase 6: Convergence

- [x] T012 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktのDOWN admissionと全event/Releaseでlive gesture generationを照合し、recomposition前の終了後UPによる確定を拒否する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでBackと古いUPを同一UI turnでdispatchし、修正前failure・修正後無確定を確認する。FR-006/008、US2/AC4（contradicts）。
- [x] T013 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktのtext/region自動focusを破棄確認中は抑制し、確認解除時だけ編集へ復帰する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでtext/region確認の再生成後に背後editorが非focusであること、IMEを強制hideせず一回のBackで確認を閉じ入力を保持することを検証する。FR-007、US1再生成（contradicts）。
- [x] T014: app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktのnew/existing/region entryとContinue/dialog Back/outside resumeで、editor focus・Activity window focus・active input connection・IME visible/bottomを同じreadiness helperで待つ。次のhideより後にIMEが表示される競合をfixtureで防ぎ、再生成確認の非focus・一回Back・Board/Room保持assertを維持する。US1 continuationの検証不足（missing）。

## Phase 7: Convergence

- [x] T015 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktでIME/確認/editor/menu/search/tool/expanded/selection/listの受理Backによるpointer continuationを同期に失効させ、preview前DOWNも古いMOVE/UPから確定させない。Initial passのtext outside loopもDOWN/live世代を照合する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでpreview前のtext/grip/MOVE/resize、menu/search/expanded、outside-confirmをBackとMOVE/UP同一UI turnで検証し、priority・入力/選択/Room/Undo・次の操作を維持する。FR-006/007/008、US1/AC3、US2/AC4（contradicts）。

最終T009/T011は[Issue73のclosure証跡](https://github.com/reitojike/think-canvas/issues/73#issuecomment-5970533783)で完了。merge d56c098、final-head c4b5df7の両CI成功・fresh110/0/0/0・canonical clean・thread0・最新17 AC達成を記録した。

## Phase 8: Issue91 独立IME復帰follow-up

- [x] T016 HIGH: `CanvasScreen.kt` のtext/search/regionNameの既存focus effectでwindow ownerを標準WindowInfoのone-shotで待ち、frame適用後にIMEをshowする。`NeutralInteractionTest.kt` へnative別window所有中のeditor entryと、Backで隠したIMEのwindow復帰時保持の回帰を追加し、dialogのhide/Back fixtureを明示isDialog rootへ限定する。Spec007 FR-003/007/009。main修正前のFocused=trueをnative回帰で再現。PO/save/Undo/geometry/Back priority維持。
- [ ] T017 `Issue91` の最終headでfocused、lint/unit/build、公開境界、full CI/source XML census、canonical review/base/threadを収束し、merge後に最新7 ACをsemantic closureする。PR90の視点履歴とはreview/rollback境界を分離する。
