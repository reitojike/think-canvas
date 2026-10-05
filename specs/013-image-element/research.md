# 調査と判断: ローカル画像要素

## authorityと有限surface

base be73549はPR98まで含み、main CI37244462566成功。Spec010/011はmerge済みなので、Draft headerが残っていても現行merge済み挙動のauthorityとして扱う。下位モデルの独立した保存/描画read-only調査は、既存BoardSnapshot、serialized Store、BoardSession save/ack、geometry集計、Canvasの型列挙、SharePlanとrendererを統合点として確認した。新board writer、別geometry、別Undoは採用しない。

## 標準picker

**Decision**: PickVisualMedia(ImageOnly)とOpenDocumentの画像MIME。写真選択は一枚、取消は変更なし。
**Rationale**: [Android公式photo picker](https://developer.android.com/training/data-storage/shared/photo-picker)は選んだ素材だけへのアクセスと、利用不可時のOpenDocument fallbackを提供する。端末gallery全体の権限を追加しない。
**Alternative**: 独自gallery/media権限はIssueの具体的必要性がなく不採用。

## 素材ownershipとcleanup

**Decision**: immutable originalをapp-privateへcopy、UUIDのみ保存、複製で共有。CanvasStore actorのDB/履歴/lease/checkpoint rootでcollectorを順序付ける。
**Rationale**: [Android公式app-specific storage](https://developer.android.com/training/data-storage/app-specific)に沿い、元URIの移動/permission喪失に依存しない。Undo80履歴と複製を壊さず、孤立素材を回収できる。
**Alternative**: 外部URIの永続permissionだけをauthorityにする方式は元素材消失に弱い。要素削除直後の無条件file削除はUndo/duplicateを壊す。無期限GCなしはIssueのorphan cleanupを満たさない。

## decode/EXIF

**Decision**: BitmapFactoryのbounds/sampleとExifInterface1.4.2でAPI26以上に同じ8向き補正。software bitmap・32MiB cache・一並列・一枚4MP。静止JPEG/PNG/WebPをv1で保証。
**Rationale**: [platform ExifInterface](https://developer.android.com/reference/android/media/ExifInterface)はAndroidX版を推奨。[公式release](https://developer.android.com/jetpack/androidx/releases/exifinterface)で最新stable1.4.2を確認。BitmapFactory＋同じorientation関数なら表示と既存software Canvas出力が一致する。
**Alternative**: [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)のtarget/allocatorはAPI28以上。API26用に別decode/向き実装を増やすより現行baselineを一つのdecoderで扱う。外部loaderの追加は不要。

## PNG受理前の検証

**Decision**: PNGは実sample decodeの前に固定bufferでCRCとzlib streamの完了、scanline量/filterを確認する。全解像度rasterは確保しない。
**Rationale**: native回帰で、壊れた圧縮payloadでもBitmapFactoryが部分bitmapを返すことを観測した。[PNG標準のIDAT](https://www.w3.org/TR/png-3/#11IDAT)に従い、任意境界の分割と空chunkを連結して扱い、zlib終了後のunused bytesはCRC検証を続けながら無視する。Adam7の空passも数えない。有効な素材の誤拒否をunit/native双方で確認する。
**Alternative**: 全解像度decodeによる検証は100MP sourceの資源上限と両立しない。表示decoderを別実装へ置換せず、受理時だけ有限stream検証を補う。

## layer/geometry

**Decision**: imageを既存marker等の下に描画。hitは既存要素優先、imageを後ろにする。bounds中心包含、四角の矢印接続、aspect resize、gap中心移動。
**Rationale**: 画像へpen/marker注釈を置け、既存text/shapeがopaque画像で隠れない。独自layer UIやcropを増やさず、Issueの一般規則第一候補を満たす。

## picker restoration/receipt

**Decision**: request/element/task UUIDとaccepted app-private checkpoint、既存no-payload receiptのatomic保存を画像へ拡張。receipt済みは古いresult/Undo後も再適用しない。未保存復元は明示retry。外部URIをBundle/checkpointへ保存しない。
**Rationale**: 既存のBoardSession/Store save/ackとPR98のfile IO排他を維持。pickerの取消と保存成功を区別する。

## 代替テキスト

**Decision**: PO A。任意手動説明、空欄「画像」、menu editと一Undo/save、キャプションなし。
**Rationale**: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)のcontentDescription/custom actionsを使い、既存選択・移動・削除と操作可否を揃える。説明の自動生成は範囲外。

すべての技術選択を解決済み。追加PO質問なし。実機確認は完成の別gateとして維持する。
