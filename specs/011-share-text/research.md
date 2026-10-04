# 技術調査: Share Target

## 既存authority

- Decision: 一回のBoardState.applyとBoardSession request-specific ackへ接続する。
- Rationale: 現行ownerはRoom成功後にackを完了する。Store actorの直列化だけでは二つのActivityの古いsnapshotを防がない。
- Alternatives: 独立Share Activity/save writer、本文hashによる重複判定は不採用。

## Android受信

- Decision: ACTION_SEND/text/plain/EXTRA_TEXTのみ。singleTask/onNewIntentを単一MainActivityに使う。
- Rationale: warmの未確定editorを持つownerを維持。singleTop単独はtop以外で別instanceを作り得る。
- Alternatives: standard、singleTop単独、独自Activity registry。上位Activity取消は画像exportの既存result cleanupで検証する。
- Source: [Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack)。標準Backを独自finishに変更しない。

## task restorationとB

- Decision: task token + private AtomicFile。要求変更ごとに同tokenの最新checkpointを保存。Bundleは小さなtokenだけ。
- Rationale: saved stateだけでは最後のSTOP後の変更が戻り得る。fresh taskはfresh tokenで旧保留を再開しない。
- Alternatives: SavedStateHandleのみ、Room pending inbox全件再開、本文hash。次回新taskへ持ち越す方式はPOのBに反する。
- Source: [Saved state](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)、[Saving UI states](https://developer.android.com/topic/libraries/architecture/saving-states)。task終了とOS復元を区別。
- Scope: 外部提供の安定delivery IDを仮定しない。onNewIntentはfresh操作、同task checkpointは復元操作。

## 耐久保存

- Decision: schema3 receiptと全replaceAllを同transaction。既存receiptがあれば書き直さない。
- Rationale: 要素IDだけでは保存後Undo/削除時の完了を判断できない。UI ackよりtransactionをauthorityにする。
- Alternatives: memory ackのみ、要素存在チェック、別transaction receipt、旧全snapshot再適用。
- Existing facts: schema2、MIGRATION_1_2はink追加。2→3はreceiptのみ追加。一般retryは現在snapshotを使うため共有完了まで操作制限する。

## preview

- Decision: 本文確認/board選択。本文編集は取り込み後の既存editor。
- Rationale: v1のIssueに閉じ、第二editorのdraft/lifecycleを追加しない。全文scroll/TalkBackを提供。
- Alternatives: preview内editable text。Androidの推奨として検討し、今回の範囲では不採用。
- Source: [Receive simple data](https://developer.android.com/develop/ui/compose/sharing/receive)。入力の型/空値を検査。URL取得なし。

## 調査完了

A/A・Bで製品判断は解消済み。技術選択はplanの有限surface内に閉じ、追加依存/保存ownerは導入しない。
