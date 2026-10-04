# Data model: 共有テキスト

| Entity | Fields / 制約 | authority |
| --- | --- | --- |
| ShareImportRequest | requestId/elementIdは生成UUID、textは非blankの原文、destinationIdはnullable、確定後boardIdは有効Long、x/yはfinite world座標 | 受信時ID生成、確認時固定 |
| Task checkpoint | taskTokenはアプリ生成UUID。現在要求またはterminal。初期Intent処理済み。private fileに本文、Bundleはtokenのみ | 同taskのみ復元 |
| ShareImportReceipt | requestId TEXT PK、boardId INTEGER、elementId TEXT。本文なし、board削除cascadeなし | content transaction |
| BoardSaveAcknowledgement | 既存boardId/requestId/completion。共有requestIdは別metadata | BoardSessionの耐久成功 |

`textは非blankの原文`、`x/yはfinite world座標`を検査。外部IntentのID/token指定は使わない。本文を切り詰め/URL変換しない。

## 状態

受信→checkpoint書き込み→Deferred→Preview/Picker→Opening destination→Accepted checkpoint→Saving→Completed。

- Preview/Picker→Canceled: content/Undo/save不変。terminal checkpointで初期Intentを再生しない。
- Saving→Failed→明示Retry→Saving: 同patch/receipt/ack、追加Undoなし。
- config recreation: retained ownerの現在状態。
- fresh owner/task restoration: tokenから最新checkpoint、receipt照合。完了ならterminal、未完了AcceptedはFailed/manual retry。
- explicit task close/new launch: 新token。旧要求を再開せず、commit済みcontent保持。
- busy二件目: 上書きせず再共有案内。空/不正/対象外: 拒否してboard不変。

restoreで古い全snapshotは適用しない。最新Roomに固定patchを一回加え、要素IDが既にあれば追加しない。receiptは要素存在より優先する。
