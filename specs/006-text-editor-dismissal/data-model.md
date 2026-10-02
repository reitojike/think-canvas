# 編集状態

`TextEditorSession` はボードごとの process 内 state。Room schema と BoardSnapshot には含めない。

- `Draft`: `id: String?`（null は新規）、`x/y: Float`（world 座標）、`text: String`、`kind: TextKind`、`color: TextColor`。
- `pendingDraftAcknowledgement: BoardSaveAcknowledgement?`: 明示確定で返された同じ request の通知。
- `pendingNewElementId: String?`: 新規確定済み要素の ack 待ち中の二重表示を防ぐ。

遷移: Idle→Editing→cancel なら Idle（snapshot/history 不変）。Editing→Done なら既存 create/edit→Saving→対象 ack 成功で Idle。Saving→Failed→Retry→対象 ack 成功で Idle。Running/Failed/pending ack は cancel 禁止。同じ process の Activity recreation では3つとも維持する。ボード session の discard で破棄する。
