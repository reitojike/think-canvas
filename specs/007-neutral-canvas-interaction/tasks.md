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

- [ ] T009 app/でlint/unit/debug/androidTest build、schema、公開境界、diff、focused/full instrumentationと既存Spec 006 regressionを検証しspecs/007-neutral-canvas-interaction/quickstart.mdへ結果を記録する。
- [x] T010 specs/007-neutral-canvas-interaction/tasks.mdにconvergeで残差を確認し、README.mdとSpec 006のBack参照を同期する。
- [ ] T011 GitHub PRでfinal-head CI/canonical review/threads/baseを収束し、merge後にIssue #73の最新ACを逐条判定する。specs/007-neutral-canvas-interaction/quickstart.mdから証跡へ到達できるようにする。

## 依存・実装戦略

T001→T002→T003/T004→US1→US2→検証・converge。US1はeditorからの無保存close、US2はtoolからの復帰を独立に検証できる。テストsuiteとCanvasScreenの作業は別ファイルで並行可能だがGradle実行は一つにする。custom checklistはreviewer-ownedの未査読状態を保持し、利用者の「提案した挙動で進める」という実装許可に従い進める。
