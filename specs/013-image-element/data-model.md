# データモデル: ローカル画像要素

## ImageElement / BoardSnapshot

`ImageElement`は`id: String`、`assetId: String`、`x/y/width/height: Float`、`intrinsicWidth/intrinsicHeight: Int`、`altText: String = ""`。

- idとassetIdはcanonical UUID。assetIdをfilenameやURI/絶対pathと解釈しない。
- x/y/width/heightは有限、width/heightは正、x+width/y+heightも有限。intrinsic寸法は1〜65535、積は100,000,000以下。
- width/heightの比は向き補正済みintrinsic比。resize長辺は40〜8192world。初期サイズは開始viewport60%と長辺480world以内。
- altTextは利用者の任意の原文。空白のみは表示/読み上げ時に「画像」。取り込み時は空欄、原ファイル名から生成しない。
- BoardSnapshotの末尾`images: List<ImageElement> = emptyList()`で後方互換の呼出しを保つ。BoardStateは全collectionを同じ80操作のbefore/afterへ保存する。

## ImageElementRow / migration

schema4 `image_elements`: id TEXT PRIMARY KEY、boardId INTEGER、assetId TEXT、x/y/width/height REAL、intrinsicWidth/intrinsicHeight INTEGER、altText TEXT。新規tableのみを3→4で追加し、1→2→3→4の旧データを保持する。通常full replace、複製transaction、board delete、共有import transactionすべてにimagesを含める。

asset DB tableは作らない。immutable bytesと寸法の検証済み記述子をimage row/checkpointが参照する。collectorはDAOの全assetId unionをrootとして読む。

## 素材 / lease

- 固定のapp-private folder内に`<UUID>.img`とcopy中の`<UUID>.partial`。ユーザーURIはcopyの一時引数のみ。
- copy/header検証/fsync/finalize後の素材は変更しない。元のEXIFは端末内素材には残せるが、出力へ転写しない。
- leaseはopaque tokenとassetId集合。取り込み、queued save、表示decode、全画像出力の寿命を保護し、finally/cancelで解除する。
- owner別保護rootはBoardSessionの全現在/Undo/Redo snapshot。onCleared/discard/session追加/requestSaveで同期更新する。
- collector rootはDB + owner root + lease + 有効accepted checkpoint。collectorとfile変更は既存Store actor、decodeはpinされたfileのread-only IO。
- 同じassetを使う複製はfileを二重copyせず、elementとarrow target IDだけを再採番する。

## 取り込み要求 / checkpoint

要求はtaskToken、requestId、elementId、boardId、開始world center、開始viewport幅/高さ、選択種別を持つ。asset完成後にassetId、補正intrinsic寸法、固定world boundsを追加する。

`image-import/<taskToken>.pending`のversion1 recordは端末内JSON。元URI/filename/Intent/Bitmapは保存しない。checkpointはStore actorでatomic write + readbackし、同じactorでread/cleanupする。Bundleはtrusted taskTokenのみを保持する。

| phase | durable record / 復元 | 内容への作用 |
| --- | --- | --- |
| Idle | recordなし/terminal | なし |
| Picker | 開始要求だけ。retained Activityはpicker結果を継続。fresh ownerは結果のrequest/phaseを照合 | なし |
| Reading | Picker recordのまま。fresh ownerは再選択を案内 | なし |
| Accepted/Saving | 完成assetと固定patchを適用前に記録。復元はreceipt確認→未完了はFailedで明示retry待ち | 既存BoardSessionが一Undoとreceipt付きsaveを適用 |
| Failed（未受理） | 画像選び直し/取消 | なし |
| Failed（受理後） | 同じpatchでretry。閉じても既存未保存状態を勝手にUndoしない | 新たな要素・Undoを追加しない |
| Completed | 同じrequestのdurable receipt確認後にterminal | 保存済み内容を保持 |

receiptは既存share_import_receiptsのrequestId/boardId/elementIdを再利用し、歴史的table名は保持する。payloadは入れない。同じrequestの画像が現在削除/Undo済みでもreceiptから完了を判断する。Task明示終了/fresh taskは未保存checkpointを持ち越さず、保存済みreceiptを消さない。

## 画像資源

decode keyはassetIdとside bucket（64/128/256/512/1024/2048）。cacheは最大32MiB、bitmapのbyteCountで計量。デコードは一件ずつ、最大2048px/4MP、向き補正の同時一時領域を制限する。Composable stateはkey/revision/errorだけでbitmapを長期保持しない。

farは256、midは1024、nearは必要screen pixelsまで2048。screen外は要求しない。cache evictionは世界boundsや保存内容を変えない。placeholderにも選択/操作と固定boundsを残す。出力はbatch leaseで素材をpinし、一枚ずつ同じ向き/ratioで描く。
