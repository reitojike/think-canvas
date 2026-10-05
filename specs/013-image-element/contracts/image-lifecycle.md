# 画像操作と保存の契約

## 取り込み

1. 開始時に最新neutral/IME/save Idle/modalなしとboardの同一性を確認する。
2. request/element UUIDと開始board/viewportを固定し、Picker checkpoint成功後に標準pickerを一回launchする。
3. null resultはterminalへし、Board/Undo/saveは不変。古いphase、二重result、別ownerのcallbackは拒否する。
4. resultの素材を既存Store actorでbounded copy/header検証し、leaseを保持したaccepted patchをatomic checkpointへ記録する。
5. current request、開始boardの存在、既存owner、live guard、durable receiptを再確認する。別boardへsilent mutationしない。
6. receipt済みならterminal。未保存なら既存BoardSessionの一Undo/receipt/save ackで適用し、同じidentityをretryに使う。
7. save failureは同じrequestの明示retry。fresh owner復元は自動再適用せず、receiptを先に照合して未完了をFailedとして示す。
8. 明示task終了は未保存要求を取消し、成功済みRoom内容とreceiptを保持する。新taskは古いInboxとして検索しない。

画像追加pending/説明modalは既存外部block/neutral readinessへ参加する。text共有はその操作を黙って終了せずDeferredとし、開始元操作が通常完了してからpreviewを開く。

## 編集とgeometry

- 一つのBoardSnapshot、既存ID selection、world boundsと80編集履歴を使う。
- selected imageのresizeは左上固定、右下handle、intrinsic比。a11yは拡大/縮小actionを同じmodel関数へ通す。
- region包含は画像bounds中心、region moveは開始集合を一度ずつ。gapはimageを歪めず中心で移動する。
- arrow attachmentは画像IDと相対u/v、同じ四角の外周計算。画像削除では接続矢印も同じUndo単位へ含める。
- image→marker→shape→arrow→region label→text→penの順。既存hitが優先しimageは最後。選択・tier・cache状態はgeometryを変更しない。
- stale callbackの拒否は見た目のenabledだけに依存せず、callback内のlive guardを使う。

## 代替テキスト（PO A）

menu「代替テキストを編集」→Draft→完了/取消。空欄を確定可能。変更済みBackは破棄確認、未変更は閉じる。一つのUndo/save、読み上げは説明または「画像」。captionを描かず、ファイル名・asset path・OCR/AIをauthorityにしない。

## 素材の保存と回収

素材finalizeが成功するまでimage metadataを保存しない。Board saveは既存actor transaction。DB/history/queued save/import/checkpoint/exportのいずれかの参照がある素材を消さない。複製はimmutable素材を共有。 collectorは同じactorで全rootを照合し、失敗時は保存結果を偽らず後で再試行する。

## 表示と出力

静止JPEG/PNG/WebPだけを保証し、不正/animated/上限超過は追加0件。copy64MiB・source100MP/65535px・decode4MP/2048px・cache32MiB/並列1を上限にする。8種類のEXIF向き、透明alphaを表示/thumbnail/outputで揃える。

出力は既存SharePlanの対象集合/region拡張/4096px・16MP上限とImageDeliveryを使う。全素材をpinし一枚ずつ描画する。欠けたassetのPNGは成功扱いせず、元EXIF/位置情報/alt text/内部pathをPNGへ埋め込まない。
