# テキスト編集sessionの所有者と寿命

操作の正本は[テキストの1タップ編集](../../specs/017-single-tap-text-editing/spec.md)と
[外側タップによる確定・終了](../../specs/006-text-editor-dismissal/spec.md)です。
Back・囲み名・IMEは[通常キャンバスへの復帰](../../specs/007-neutral-canvas-interaction/spec.md)の
別契約を参照します。ここでは既存テキスト編集の状態所有者を整理し、入力仕様を変更しません。

## 所有者

[BoardSessionViewModel](../../app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt) はboardIdごとの
SessionにBoardState、saveState、TextEditorSession、ViewportHistoryを持ちます。
`textEditorFor`は既存sessionのeditorを返します。同じprocess内のActivity再生成を越えて保持しますが、
未確定draftをRoomへ永続化したり、process終了後に復元する契約ではありません。
`discard(boardId)`はそのsessionを除きます。

[TextEditorSession](../../app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt) はテキスト、
囲み名、画像説明の状態を保持します。以下はテキスト部分のみです。画像説明や囲み名の状態遷移を
テキストと同一とみなして統合しません。

## テキストの状態

`TextEditorSession` はボードごとの process 内 state。Room schema と BoardSnapshot には含めない。

- `Draft`: `id: String?`（null は新規）、`x/y: Float`（world 座標）、`text: String`、`kind: TextKind`、`color: TextColor`、`sessionId: String`（生成時のみ新規 UUID、copy では維持）。
- `pendingDraftAcknowledgement: BoardSaveAcknowledgement?`: 明示 Done または外側確定の共通 commit path で返された同じ request の通知。
- `pendingNewElementId: String?`: 新規確定済み要素の ack 待ち中の二重表示を防ぐ。

遷移: Idle→Editing→cancel なら Idle（snapshot/history 不変）。Editing→Done または外側確定なら既存 validation/create/edit→Saving→対象 ack 成功で Idle。外側で最新 text が exact empty の新規 draft だけは無保存破棄→Idle。validation が変更を拒否した場合は既存 Done と同じ無変更終了。Saving→Failed→Retry→対象 ack 成功で Idle。Running/Failed/pending ack は外側 finalization と cancel を拒否し、既存 retry だけを利用する。同じ process の Activity recreation では3つとも維持する。ボード session の discard で破棄する。

## primary actionと選択

- 選択集合: 既存`Set<String>`。0/1件は通常primary action、2件以上は追加/解除。
- TextPrimaryAction: EDIT / ADD_TO_SELECTION / REMOVE_FROM_SELECTION。判定は入力集合を変更しない。
- EDITは既存Draft/sessionを1回開始する。明示menu編集の既存契約は維持する。
- ADD/REMOVEは選択集合と必要な選択表示だけを変え、Draft・board・保存・Undoを変えない。
- Draft/save/acknowledgement・active gesture ownerによるguardが先行する。保存schema変更なし。

## 確定と保存成功の境界

[CanvasScreen](../../app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt) の `commitDraft` は
通常の完了と外側確定の共通入口です。変更があれば `onRequestSave` のacknowledgementを保持し、
同じackの完了を待ってdraftを閉じます。変更がなければ新しい保存をせずに閉じます。
`finalizeDraft` は保存guardとpending ackを先に確認し、外側操作から自動retryしません。
明示的な完了によるretryは既存の要求を再試行します。

`Draft.sessionId`は内容のcopyで変えず、新しい編集開始時に生成します。外側gestureの開始後に
内容が更新されても、同一sessionの最新値を扱います。古いsessionへの操作や保存成功が
別のeditorを閉じないことが境界です。Roomとqueueの契約は
[ボードの保存とデータ寿命](board-persistence.md)を参照します。

## 検証の入口と未確認の扱い

- [TextPrimaryAction](../../app/src/main/java/com/thinkcanvas/canvas/TextPrimaryAction.kt) と
  [単体テスト](../../app/src/test/java/com/thinkcanvas/canvas/TextPrimaryActionTest.kt):
  0/1件選択で編集、2件以上で追加/解除。入力集合を変更しない純粋な分岐です。
- [TextEditorDismissalTest](../../app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt):
  外側操作、更新後の内容、保存guard等の操作契約。
- native IMEの問題と隔離は[専用runbook](../runbooks/ime-quarantine.md)とIssue #106で扱います。
  文書の整理や一部suiteの成功を、未解明問題の解決や実機での再検証とみなしません。

詳細なpointer arbitration、囲み名・画像説明の取消、IME/window focus制御はこの抽出の対象外です。
元の007/012/013の契約と実装を参照し、未照合の部分を一般化しません。
