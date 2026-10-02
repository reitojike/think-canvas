# 編集状態

`TextEditorSession` はボードごとの process 内 state。Room schema と BoardSnapshot には含めない。

- `Draft`: `id: String?`（null は新規）、`x/y: Float`（world 座標）、`text: String`、`kind: TextKind`、`color: TextColor`、`sessionId: String`（生成時のみ新規 UUID、copy では維持）。
- `pendingDraftAcknowledgement: BoardSaveAcknowledgement?`: 明示 Done または外側確定の共通 commit path で返された同じ request の通知。
- `pendingNewElementId: String?`: 新規確定済み要素の ack 待ち中の二重表示を防ぐ。

遷移: Idle→Editing→cancel なら Idle（snapshot/history 不変）。Editing→Done または外側確定なら既存 validation/create/edit→Saving→対象 ack 成功で Idle。外側で最新 text が exact empty の新規 draft だけは無保存破棄→Idle。validation が変更を拒否した場合は既存 Done と同じ無変更終了。Saving→Failed→Retry→対象 ack 成功で Idle。Running/Failed/pending ack は外側 finalization と cancel を拒否し、既存 retry だけを利用する。同じ process の Activity recreation では3つとも維持する。ボード session の discard で破棄する。
