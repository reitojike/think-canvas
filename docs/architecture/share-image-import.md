# 共有・画像取り込みの詳細契約

この文書は、共有テキスト取り込み（011）と画像要素・取り込み（013）の
data-model と実装上の詳細契約の正本です。利用者に見える操作の正本は
[共有取り込み](../../specs/011-share-text/spec.md) と
[画像要素](../../specs/013-image-element/spec.md) です。保存境界・schema・素材寿命の
一般論は [ボードの保存とデータ寿命](board-persistence.md) を参照します。
011/013 の data-model と contracts に残っていた現行の詳細契約は、この文書へ
移しています。移行計画や実行済みテストの記録はここに置きません。

## 共有テキスト取り込み（011）

### 要求と checkpoint

- `ShareImportRequest`: requestId/elementId は生成 UUID、text は非 blank の原文、
  destinationId は nullable、確定後 boardId は有効な Long、x/y は finite な world 座標。
  text と x/y は受信時に検査します。ID は受信時に生成し、確認時に固定します。
  外部 Intent の ID/token 指定は使いません。
  本文を切り詰めたり URL 変換したりしません。
- Task checkpoint: taskToken はアプリ生成 UUID で、現在要求または terminal を表し、
  初期 Intent の処理済みも保持します。本文は private file に置き、Bundle には
  token のみを入れます。同じ task のみ復元します。本文は取消・成功・task の
  finishing で消し、不要になった file は新規起動時に掃除します。
- `ShareImportReceipt`: requestId TEXT PK、boardId INTEGER、elementId TEXT。本文は
  持ちません。board 削除で cascade 削除しません。content transaction で書きます。
- `BoardSaveAcknowledgement`: 既存 boardId/requestId/completion を持ち、共有 requestId は
  別 metadata として扱います。正本は BoardSession の耐久成功です。

### 状態と復元

受信 → checkpoint 書き込み → Deferred → Preview/Picker → Opening destination →
Accepted checkpoint → Saving → Completed。

- Preview/Picker → Canceled: content/Undo/save は不変。terminal checkpoint では
  初期 Intent を再生しません。
- Saving → Failed → 明示 Retry → Saving: 同じ patch/receipt/ack を使い、Undo を追加しません。
- config recreation: retained owner の現在状態を引き継ぎます。
- fresh owner/task restoration: token から最新 checkpoint を読み、receipt を照合します。
  完了なら terminal、未完了の Accepted は Failed とし手動 retry を待ちます。
- explicit task close/new launch: 新 token を使い、旧要求を再開しません。
  commit 済み content は保持します。
- 完了済み要求の再適用防止: ack 前に成功していた場合、後から Undo/削除された
  場合、OS 復元の場合のいずれでも、旧要求を再適用しません。
- busy な二件目: 上書きせず再共有を案内します。空/不正/対象外は拒否し board は不変です。

restore では古い全 snapshot を適用しません。receipt がある再処理では全置換しません。
最新 Room に固定 patch を一回だけ加え、
要素 ID が既にあれば追加しません。receipt は要素の存在より優先します。

### Share Target の外部契約

- ACTION_SEND、MIME text/plain、EXTRA_TEXT の CharSequence 一件だけを受けます。
  URL も普通の本文として扱います。不正型/blank/他 MIME/SEND_MULTIPLE は拒否し
  board は不変です。新 onNewIntent は新要求とし、同 task の onCreate は
  trusted checkpoint で復元します。本文をログに出力せず、外部 network への
  送信や URL 取得はしません。
- 表示中 board → 最後に開いた有効 board → picker の順に宛先を決めます。
  確認前の content 変更はしません。全文と board 名を preview し、
  変更/取消/取り込みを選べます。picker には選択状態と明示的な board 作成があります。
- 保留中は元の操作を完了でき、preview 以降は live guard が古い Canvas action も拒否します。
  選択 board の準備済み視点中央に一件を取り込み、一 Undo/一 save とします。
- 通常 Back/IME は既存の優先順位に従います。再試行と復元時の扱いは
  「状態と復元」に従います。
- 共有 ID の完了と content は同じ Room transaction で保存します。schema 2→3 では
  既存 data を維持します。

## 画像要素と取り込み（013）

### ImageElement と保存行

- `ImageElement` は `id: String`、`assetId: String`、`x/y/width/height: Float`、
  `intrinsicWidth/intrinsicHeight: Int`、`altText: String = ""` を持ちます。
  id と assetId は canonical UUID で、assetId を filename や URI/絶対 path と解釈しません。
- x/y/width/height は有限、width/height は正、x+width/y+height も有限です。
  intrinsic 寸法は 1〜65535、積は 100,000,000 以下です。width/height の比は
  向き補正済み intrinsic 比とし、resize の長辺は 40〜8192 world、初期サイズは
  開始 viewport の 60% と長辺 480 world 以内とします。
- altText は利用者の任意の原文です。空白のみは表示/読み上げ時に「画像」とし、
  取り込み時は空欄で、原ファイル名から生成しません。
- `BoardSnapshot` は末尾の `images: List<ImageElement> = emptyList()` で後方互換の
  呼出しを保ちます。BoardState は全 collection を同じ 80 操作の before/after へ保存します。
- schema 4 の `image_elements`: id TEXT PRIMARY KEY、boardId INTEGER、assetId TEXT、
  x/y/width/height REAL、intrinsicWidth/intrinsicHeight INTEGER、altText TEXT。
  3→4 は新規 table の追加のみで、1→2→3→4 の旧 data を保持します。通常の full replace、
  複製 transaction、board delete、共有 import transaction のすべてに images を含めます。
- asset DB table は作りません。immutable bytes と寸法の検証済み記述子を
  image row/checkpoint が参照し、collector は DAO の全 assetId union を root として読みます。

### 素材と lease

- 固定の app-private folder 内に `<UUID>.img` と copy 中の `<UUID>.partial` を置きます。
  ユーザー URI は copy の一時引数としてだけ使います。copy/header 検証/fsync/finalize 後の
  素材は変更しません。元の EXIF は端末内素材には残せますが、出力へ転写しません。
- lease は opaque token と assetId 集合で、取り込み、queued save、表示 decode、
  全画像出力の寿命を保護し、finally/cancel で解除します。
- owner 別保護 root は BoardSession の全現在/Undo/Redo snapshot で、
  onCleared/discard/session 追加/requestSave で同期更新します。
- collector root は DB + owner root + lease + 有効な accepted checkpoint です。
  collector と file 変更は既存 Store actor、decode は pin された file の read-only IO です。
- 同じ asset を使う複製は file を二重 copy せず、element と arrow target ID だけを再採番します。

### 取り込み要求と checkpoint の phase

要求は taskToken、requestId、elementId、boardId、開始 world center、開始 viewport 幅/高さ、
選択種別を持ちます。asset 完成後に assetId、補正 intrinsic 寸法、固定 world bounds を追加します。
`image-import/<taskToken>.pending` の version 1 record は端末内 JSON で、
元 URI/filename/Intent/Bitmap は保存しません。checkpoint は Store actor で
atomic write + readback し、同じ actor で read/cleanup します。Bundle は trusted taskToken のみ保持します。

| phase | durable record / 復元 | 内容への作用 |
| --- | --- | --- |
| Idle | record なし/terminal | なし |
| Picker | 開始要求だけ。retained Activity は picker 結果を継続。fresh owner は結果の request/phase を照合 | なし |
| Reading | Picker record のまま。fresh owner は再選択を案内 | なし |
| Accepted/Saving | 完成 asset と固定 patch を適用前に記録。復元は receipt 確認 → 未完了は Failed で明示 retry 待ち | 既存 BoardSession が一 Undo と receipt 付き save を適用 |
| Failed（未受理） | 画像選び直し/取消 | なし |
| Failed（受理後） | 同じ patch で retry。閉じても既存未保存状態を勝手に Undo しない | 新たな要素・Undo を追加しない |
| Completed | 同じ request の durable receipt 確認後に terminal | 保存済み内容を保持 |

receipt は既存 `share_import_receipts` の requestId/boardId/elementId を再利用し、
歴史的 table 名は保持します。payload は入れません。同じ request の画像が現在
削除/Undo 済みでも receipt から完了を判断します。Task の明示終了/fresh task は
未保存 checkpoint を持ち越さず、保存済み receipt を消しません。

### 取り込み手順の契約

1. 開始時に最新の neutral/IME/save Idle/modal なしと board の同一性を確認します。
2. request/element UUID と開始 board/viewport を固定し、Picker checkpoint 成功後に
   標準 picker を一回 launch します。
3. null result は terminal とし、Board/Undo/save は不変です。古い phase、二重 result、
   別 owner の callback は拒否します。
4. result の素材を既存 Store actor で bounded copy/header 検証し、lease を保持した
   accepted patch を atomic checkpoint へ記録します。
5. current request、開始 board の存在、既存 owner、live guard、durable receipt を
   再確認します。別 board へ silent mutation しません。
6. receipt 済みなら terminal。未保存なら既存 BoardSession の一 Undo/receipt/save ack で
   適用し、同じ identity を retry に使います。
7. save failure は同じ request の明示 retry とします。fresh owner 復元は自動再適用せず、
   receipt を先に照合して未完了を Failed として示します。
8. 明示 task 終了は未保存要求を取り消し、成功済み Room 内容と receipt を保持します。
   新 task は古い Inbox として検索しません。ここでいう Inbox は、別 task の
   未保存要求を後から検索して再開するための保留箱のことです。本機能では
   そのような保留箱を持ちません。

画像追加 pending/説明 modal は既存の外部 block/neutral readiness へ参加します。
text 共有はその操作を黙って終了せず Deferred とし、開始元操作が通常完了してから preview を開きます。

### 編集・geometry・代替テキスト

- 一つの BoardSnapshot、既存 ID selection、world bounds と 80 編集履歴を使います。
- selected image の resize は左上固定、右下 handle、intrinsic 比です。a11y の
  拡大/縮小 action は同じ model 関数へ通します。
- region 包含は画像 bounds の中心、region move は開始集合を一度ずつ動かします。
  gap は image を歪めず中心で移動します。
- arrow attachment は画像 ID と相対 u/v、同じ四角の外周計算を使います。画像削除では
  接続矢印も同じ Undo 単位へ含めます。
- 描画 layer の順序は image → marker → shape → arrow → region label → text → pen で、
  image が最下層です。hit は既存要素を先に判定し、image は最後に判定します。選択・[表示段階（SemanticTier）](../../specs/004-semantic-navigation/data-model.md#semantictier)・cache 状態は geometry を変更しません。
  stale callback の拒否は見た目の enabled だけに依存せず、callback 内の live guard を使います。
- 代替テキスト: menu「代替テキストを編集」→ Draft → 完了/取消。空欄を確定できます。
  変更済み Back は破棄確認、未変更は閉じます。一つの Undo/save とし、読み上げは
  説明または「画像」とします。caption は描かず、ファイル名・asset path・OCR/AI を
  authority にしません。

### 素材の保存・表示・出力の上限

- 素材 finalize が成功するまで image metadata を保存しません。Board save は既存 actor
  transaction です。DB/history/queued save/import/checkpoint/export のいずれかの参照が
  ある素材を消しません。複製は immutable 素材を共有します。collector は同じ actor で
  全 root を照合し、失敗時は保存結果を偽らず後で再試行します。
- decode key は assetId と side bucket（64/128/256/512/1024/2048）です。cache は最大
  32MiB で bitmap の byteCount で計量します。デコードは一件ずつ、最大 2048px/4MP とし、
  向き補正の同時一時領域を制限します。Composable state は key/revision/error だけで
  bitmap を長期保持しません。
- far は 256、mid は 1024、near は必要 screen pixels まで 2048 とします。screen 外は
  要求しません。cache eviction は世界 bounds や保存内容を変えません。placeholder にも
  選択/操作と固定 bounds を残します。出力は batch lease で素材を pin し、一枚ずつ
  同じ向き/ratio で描きます。
- 静止 JPEG/PNG/WebP だけを保証し、不正/animated/上限超過は追加 0 件とします。
  copy 64MiB・source 100MP/65535px・decode 4MP/2048px・cache 32MiB/並列 1 を上限にします。
  8 種類の EXIF 向き、透明 alpha を表示/thumbnail/output で揃えます。
- 出力は既存 SharePlan の対象集合/region 拡張/4096px・16MP 上限と ImageDelivery を使います。
  全素材を pin し一枚ずつ描画します。欠けた asset の PNG は成功扱いせず、元 EXIF/
  位置情報/alt text/内部 path を PNG へ埋め込みません。
