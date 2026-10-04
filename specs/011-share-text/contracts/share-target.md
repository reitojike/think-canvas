# Android Share Target contract

## 外部入力

- ACTION_SEND、MIME text/plain、EXTRA_TEXT CharSequence一件。URLも普通の本文。
- 不正型/blank/他MIME/SEND_MULTIPLEは拒否しboard不変。
- 外部ID/tokenは使わない。新onNewIntentは新要求。同task onCreateはtrusted checkpointで復元。
- log/外部network/URL取得なし。

## UI

- 表示中board→最後に開いた有効board→picker。確認前のcontent変更なし。
- 全文とboard名をpreview。変更/取消/取り込む。pickerに選択状態と明示board作成。
- 保留中は元操作を完了でき、preview以降はlive guardが古いCanvas actionも拒否。
- 選択boardの準備済み視点中央に一件。一Undo/一save。
- Failedは明示retry、同patch。通常Back/IMEは既存優先順位。
- busy二件目は上書きせず再共有案内。

## 保存

- 共有ID完了とcontentは同Room transaction。
- receiptありの再処理は全置換なし。
- ack前成功/後からUndo/削除/OS復元で旧要求を再適用しない。
- explicit task終了後fresh taskは保留を再開しない。保存済みcontentを消さない。
- schema2→3で既存data維持。
