# Tasks: テキスト入力の外側確定・終了

## Phase 1: Setup

- [X] T001 CanvasScreen の draft/pointer/focus/chrome/save と既存 spec の契約を read-only で照合し、specs/006-text-editor-dismissal/research.md に checkpoint を記録する。

## Phase 2: Foundation

- [X] T002 app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に実 pointer と Room 照合の harness を追加する。

## Phase 3: US1 外側確定・終了

単独検証: 空・文字ありの新規 draft と既存編集を外側タップで閉じ、要素と保存データを照合する。

- [X] T003 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に新規/既存、次の tap、field/toolbar、旧 chrome、double tap/非タップの回帰を追加する（FR-001～005、FR-008、SC-001/003）。
- [X] T004 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt に bounds 登録/cleanup、Initial pass の finalize-only gesture と共通 finalization/既存 cancel/focus cleanup を追加する（FR-001～005、FR-008）。

## Phase 4: US2 入力と保存の継続

単独検証: draft と running/failed save を再生成し、内容維持と同じ要求の完了を確認する。

- [X] T005 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt に新規/既存 draft の再生成・lifecycle・IME back、running/failed の外側 tap・再生成・retry の回帰を追加する（FR-006/007、SC-002）。
- [X] T006 [US2] app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt に draft/ack/newId holder を追加し、app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt の board session と app/src/main/java/com/thinkcanvas/MainActivity.kt と CanvasScreen.kt で共有する（FR-006/007）。

## Phase 5: 検証と収束

- [X] T007 specs/006-text-editor-dismissal/quickstart.md の focused instrumentation、lint/unit/build/公開境界と現行 .github/workflows/android.yml の全 GMD を実行し、受け入れ条件の証跡を PR に残す。
- [X] T008 specs/006-text-editor-dismissal/spec.md、plan.md、tasks.md と対象実装を converge で照合し、残差があれば tasks.md に追記して修正する。

## 依存関係と実装方針

T001→T002→T003→T004→T005→T006→T007→T008。US1 は empty discard / Done commit の外側 gesture、US2 は lifecycle 継続を検証する。US1 が操作上の最小 increment だが、US2 も #71 の完了に必須。テストを先に記述する。全タスクは同じ editor family のため、コード実装の並行作業は行わない。異なる provider による review は PR のリスクに応じて追加する。

## Phase 6: Convergence

- [X] T009 app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt の Draft に内容更新後も維持する編集セッション識別子を追加し、CanvasScreen.kt の外側 DOWN/UP 判定で照合する。TextEditorDismissalTest.kt に DOWN 後の文字更新と UP による終了の回帰を追加し、focused instrumentation で最新内容の empty discard / Done commit と focus/IME 解除を確認する（FR-001/004、SC-001/003、partial）。
## Phase 7: Product-contract correction #2

旧 head e806f77141439937d94b268325a8676c64dbcba6 の outside cancel 契約と MERGE_READY は撤回。初期タスクの完了履歴と correction #1 の sessionId は維持し、現在の product authority を以下で補正する。

- [X] T010 specs/006-text-editor-dismissal/ の spec/plan/research/data-model/contracts/checklist を fresh Issue #71 の empty new discard / existing Done commit / explicit cancel の契約へ同期する（FR-001～008、contradicts）。
- [X] T011 TextEditorDismissalTest.kt の13 regression を authority に合わせて更新し、explicit cancel、既存 Done、latest empty/non-empty、reopen・world geometry・kind/color・history・save count、blank/既存 empty validation を検証する。外側から開始した Running/Failed/recreation/retry の同一要求を照合する（FR-001～008、SC-001～003）。
- [X] T012 CanvasScreen.kt に唯一の finalizeDraft 入口を置き、最新の新規 exact empty だけを cancelDraft、それ以外を既存 commitDraft() へ渡す。live saveState と pending ack の guard、sessionId、gesture consumption を維持する（FR-001/003/007/008）。
- [X] T013 relevant focused instrumentation、全 unit、lint、debug/androidTest build、public boundary、diff、schema 不変を確認し、source census と identity 対応・coverage の更新を記録する（SC-001～003）。
- [X] T014 push 前に fresh Issue #71 の全 AC を reverse audit し、spec/plan/tasks と実装を converge で照合して残差と制限を PR に記録する（FR-001～008、Constitution I/V）。

T010→T011→T012→T013→T014 の有限な補正集合として1 commit/pushする。push後は source を freeze し、corrected exact head の full Pixel9 GMD/XML identity、canonical review、全 thread、fresh base、Issue #71 の最終 reverse audit を PR の durable report で確認する。新たな material finding は3回目の inline correctionをせず Process #36 の family checkpoint で分類して STOP。merge/Issue close は別 checkpoint。