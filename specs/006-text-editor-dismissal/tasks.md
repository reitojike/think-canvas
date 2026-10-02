# Tasks: テキスト入力の外側取り消し

## Phase 1: Setup

- [X] T001 CanvasScreen の draft/pointer/focus/chrome/save と既存 spec の契約を read-only で照合し、specs/006-text-editor-dismissal/research.md に checkpoint を記録する。

## Phase 2: Foundation

- [X] T002 app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に実 pointer と Room 照合の harness を追加する。

## Phase 3: US1 外側取り消し

単独検証: 空・文字ありの新規 draft と既存編集を外側タップで閉じ、要素と保存データを照合する。

- [X] T003 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に新規/既存、次の tap、field/toolbar、旧 chrome、double tap/非タップの回帰を追加する（FR-001～005、FR-008、SC-001/003）。
- [X] T004 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt に bounds 登録/cleanup、Initial pass の dismiss-only gesture と共有 cancel/focus cleanup を追加する（FR-001～005、FR-008）。

## Phase 4: US2 入力と保存の継続

単独検証: draft と running/failed save を再生成し、内容維持と同じ要求の完了を確認する。

- [X] T005 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に新規/既存 draft の再生成・lifecycle・IME back、running/failed の外側 tap・再生成・retry の回帰を追加する（FR-006/007、SC-002）。
- [X] T006 [US2] app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt に draft/ack/newId holder を追加し、app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt の board session と app/src/main/java/com/thinkcanvas/MainActivity.kt と CanvasScreen.kt で共有する（FR-006/007）。

## Phase 5: 検証と収束

- [X] T007 specs/006-text-editor-dismissal/quickstart.md の focused instrumentation、lint/unit/build/公開境界と現行 .github/workflows/android.yml の全 GMD を実行し、受け入れ条件の証跡を PR に残す。
- [X] T008 specs/006-text-editor-dismissal/spec.md、plan.md、tasks.md と対象実装を converge で照合し、残差があれば tasks.md に追記して修正する。

## 依存関係と実装方針

T001→T002→T003→T004→T005→T006→T007→T008。US1 は外側 gesture、US2 は lifecycle 継続を検証する。US1 が操作上の最小 increment だが、US2 も #71 の完了に必須。テストを先に記述する。全タスクは同じ editor family のため、コード実装の並行作業は行わない。異なる provider による review は PR のリスクに応じて追加する。

## Phase 6: Convergence

- [X] T009 app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt の Draft に内容更新後も維持する編集セッション識別子を追加し、CanvasScreen.kt の外側 DOWN/UP 判定で照合する。TextEditorDismissalTest.kt に DOWN 後の文字更新と UP による取り消しの回帰を追加し、focused instrumentation で未保存・focus/IME 解除を確認する（FR-001/004、SC-001/003、partial）。