# 実装計画: ローカル画像要素

**Branch**: `codex/issue-80-image-element` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)
**Input**: Issue80、任意代替テキストA。base be73549（PR98 merge後CI37244462566成功）。

## Summary

ImageElementをBoardSnapshotの第五のcollectionとし、既存geometry・選択・BoardStateの編集履歴・BoardSessionのsave ack・CanvasStoreのserialized actorを拡張する。不変のapp-private素材は複製時に共有し、保存/履歴/進行処理の参照を保護して回収する。標準pickerで一枚を選び、開始board/位置と安定request identityで照合して一回だけ適用する。表示・出力は同じ向き補正とbounded decodeを使う。

## Technical Context

- **Language/Version**: 現行Kotlin/Java17、JDK25、Gradle9.8.0。既存build設定を保持する。
- **Primary Dependencies**: 現行Compose/Activity1.13.0/Room3 3.0.3/Ink。新規はAndroidX ExifInterface1.4.2（公式最新stable、API26でも同じ8向き補正を行うため）。画像loaderや独自pickerライブラリを増やさない。
- **Storage**: Room schema3→4にimage_elementsを追加。素材はfilesDir内のUUID専用folder、immutable original bytes。外部URI/絶対path/ファイル名を保存しない。
- **Testing**: unit（model/geometry/history/plan/migration/receipt）、native（実Room/素材/EXIF/picker callback/recreation/guard/export）、標準Windows GMDと現行GitHub CI、代表実機。
- **Platform/Type**: Android API26〜37の単一Activity Compose app。singleTask/text共有のManifestは変更しない。
- **Performance**: copy64MiB/source以内、source寸法各65535以内・100MP以内、sampled decode一枚最大2048px/4MP・ARGB8888。process cache32MiB、decode並列1、素材rotate一時領域最大16MiB追加。出力の既存4096px/16MP制限を維持し、画像は一枚ずつ描画/一時bitmap解放。cache外のbitmapを各Compose nodeに保持しない。
- **Scope**: 静止JPEG/PNG/WebP。GIF/animated WebP/APNG/未知形式は明示拒否する。形式判定はMIME申告だけでなくcopy済みheaderとdecoderの結果を確認。HEIF等は本v1の保証対象に含めず、読取り不可を明示する。

## Constitution Check

| 原則 | 判定 |
| --- | --- |
| I authority | Issue80とPO Aをspecへ反映。Spec010/011はmainへmerge済みであり、残るDraft headerだけでauthorityを否定しない。初期画像非スコープは本機能に限って拡張。 |
| II standard | PickVisualMedia/ImageOnlyとOpenDocument/image MIME。既存44/48dp controlとsemantics。ExifInterfaceはplatform版の公式推奨置換。 |
| III world配置 | model boundsを一つのauthorityとし、自動layoutなし。画像は縦横比resize、gapは中心に基づく移動で歪めない。 |
| IV local | app-private copy、upload/URL取得/OCR/AIなし。再描画PNGは元EXIFをコピーしない。 |
| V spec先行 | specify/clarify完了後に設計・checklist/tasks/analyzeを実行し、コードはその後。 |

設計後も全5原則に違反なし。新workflowや検証機構は追加しない。

## Project Structure

```text
specs/013-image-element/{spec,plan,research,data-model,quickstart,tasks}.md
specs/013-image-element/contracts/image-lifecycle.md
app/src/main/java/com/thinkcanvas/canvas/{ImageElement,ImageElements,ImageDescriptionDialog}.kt
app/src/main/java/com/thinkcanvas/image/{ImageAssetStore,ImageDecodePolicy,ImageResources,ImageImportViewModel}.kt
app/src/main/java/com/thinkcanvas/{MainActivity,BoardSessionViewModel}.kt
app/src/main/java/com/thinkcanvas/data/{CanvasDatabase,CanvasStore}.kt
app/src/main/java/com/thinkcanvas/canvas/{SpatialElement,BoardState,SpatialGeometry,RenderedGeometry,SemanticNavigation,ContentHistoryFocus,CanvasScreen}.kt
app/src/main/java/com/thinkcanvas/board/{BoardDuplication,SharePlan,BoardImageRenderer,BoardThumbnail}.kt
app/src/test/java/com/thinkcanvas/{canvas,image,data,board}/
app/src/androidTest/java/com/thinkcanvas/image/
```

既存folderとauthorityを延長する。image packageは素材/読込み/要求だけで、第二のboard writerを持たない。

## 有限な統合surfaceと設計

### 1 model/geometry/history

ImageElement(id,assetId,x,y,width,height,intrinsicWidth,intrinsicHeight,altText)は有限座標/正寸法/UUID素材IDを検証。回転編集なし。BoardSnapshot.imagesを末尾defaultで追加し、既存呼出しの位置引数を保持する。boundsOf/centerOf/translatedSelection/withGap/lasso/projection/rendered bounds/arrow targets/contentUndoFocusへ第五collectionを通す。

layerはimage→marker→shape→arrow→region label→text→pen。画像への注釈が全て見えるよう既存layerの下へ追加する。hitは既存pen/text/spatial/markerを先にし、その後image。shapeと同じ右下resize handleを使い、左上固定でintrinsic比を保つ。長辺40〜8192world、非有限操作を拒否。generic move/delete/region/gap/edge auto-panを再利用する。

BoardStateのsnapshot/restore/record80履歴へimagesを含める。retainedImageAssetIdsは現在と両履歴のbefore/afterのunionを返す。説明編集も一record。別画像履歴は作らない。

### 2 persistence/素材参照寿命

image_elementsにelement/board/asset/geometry/intrinsics/altTextを保存し、既存replaceAll/createDuplicate/delete/readとschema4へ含める。migrationは追加CREATEのみ、旧4collectionとshare receiptを保護。

素材copy、finalize、metadata照合、leaseとcollectorはCanvasStoreの既存IO actorから呼ぶ。UUIDの固定basenameのみ解決し、任意pathを受けない。copyのstaging fileをflush/fsync→atomic rename→header validateした後だけleaseしたdescriptorを返す。cancel/errorのstagingと未参照finalを回収する。

collectorのroot = 全保存image rowsのassetIds + owner別に公表された全BoardState/Undo/Redo assetIds + 取り込み/queued-save/描画出力lease。保護root更新はMainのrecompositionに依存せず、BoardSession requestSave/session creation/discard/onClearedで同期更新する。store submitしたsnapshotもjob完了までpinする。owner token別に管理して別VMの保護を上書きしない。collector自体は失敗しても保存成功を失敗へ変更せず、次のroot更新/起動で再回収する。新process開始時のstaging orphan sweepはactor順序でimportより前に行う。

複製は新element ID/arrow target ID、同じasset ID。全参照がなくなった時だけ素材を削除。既存普通saveのcoalescing/ack/retryは変更しない。

### 3 picker/再生成/既存操作保護

既存ツールmenuに「画像を追加」、選択肢「写真から」「ファイルから」。neutral/IME終了/save Idle/外部modalなしで開始。開始board、視点中心、表示範囲、UUID request/elementを固定する。初期長辺は開始viewportの60%以内かつ480world以内、intrinsic比で中央に置く。

ImageImportViewModelはpending要求だけを持ち、retained VMでrotationを継続する。trusted Bundleにはrequest token/開始board/位置/phaseだけを入れる。外部Intentをstateの初期値にしない。picker callbackは同じ要求とphaseを確認、nullは取消。read中は外部blockで元操作/別shareを保留。read完了後に最新board owner・live guardを再確認する。

既存share_import_receiptsのno-payload request/board/element記録を画像取り込みにも拡張する（歴史的table名を維持）。replaceAllForShareの受理要素確認をtextまたはimageとし、既存transaction/receipt authorityを再利用。BoardSession.requestImageImportは既存enqueueSave/receipt/ackを使い、画像専用writerを増やさない。durable receiptがあればUndo後でも古いpicker結果を再適用しない。

process復元はreceiptを先に照合。未完了readは再選択を案内し自動外部URI読込み/追加しない。accepted patchは適用前にUUID task tokenに対応するapp-private checkpointへ、開始board/安定ID/assetId/固定geometry/intrinsicsを耐久記録する。CanvasStoreの同じactorがfile lease→checkpoint書込み→readbackを順序付ける。collectorは有効checkpointのasset rootも保持する。trusted Bundleはtask tokenのみを渡す。復元accepted未保存は保存Failed/同じpatchの明示retryとし、自動適用しない。新taskでは旧checkpointを取り込まず回収する。accepted保存失敗の閉じる操作は適用済み内容を勝手にUndoせず、既存boardの未保存/明示retryを保持する。task明示終了は未保存要求をcancelし、保存済みreceipt/contentは保持する。古いresult/callbackは別boardへ適用しない。

### 4 decode/display/output

copy済みfileをBitmapFactory bounds decodeとAndroidX ExifInterfaceで検証し、受理前に256pxへsampleした実decodeも行う。BitmapFactoryが不完全PNGを部分bitmapとして返す場合に備え、PNGは固定32KiB bufferでchunk CRC・zlib完了・期待scanline量・filterを検証する。Adam7とpacked bit depth、任意境界の連続IDAT、空IDAT、標準が許す末尾paddingを扱い、source大のrasterは確保しない。1〜8の回転/反転を同じ関数で表示/出力へ適用。sample power-of-twoと最終targetサイズで一枚4MP以下、software/premultiplied透明を維持。OOM/decode failureを失敗値へ変換し内部pathをmessageへ出さない。

ImageResourcesはcache key(assetId,target bucket)とbitmapを最大32MiBで保持、decode一件ずつ。Compose stateはkey/revision/失敗のみでbitmapを所有しない。far最大256、mid最大1024、near表示pixelに応じ最大2048。画面外は要求しない。世界bounds・選択/接続はdecode成功やtierで変えない。missing/failedは同じboundsにplaceholderを描く。選択された画像は既存keep-visible契約を使う。

BoardImageRendererへasset readerを渡し、planShareの含むIDs/region expansion/boundsにimageを追加。board/selection outputは全asset batch leaseを先に取り、一枚ずつbounded decode・描画・一時bitmap解放。bitmap準備失敗では全体出力を失敗にし、欠けたPNGを成功として配布しない。BoardThumbnailは同じbounded resourcesの256pxを使い、既存遠景用文字簡略表現を維持する。PNGは既存ImageDeliveryで再encodeし、素材EXIF/alt text/内部pathを付加しない。

### 5 任意説明/アクセシビリティ

画像menuに「代替テキストを編集」。Material dialogのDraftは元内容を保持し、確定で一Undo/save。空欄確定を許可し、変更済みBackは既存破棄確認、未変更は閉じる。新captionなし。image semanticsは説明または「画像」、選択状態とselect/add/remove/move/resize/delete/edit action。利用不可と古いcallbackのlive guardを確認。IME/説明modalを既存neutral readinessへ参加させ、text共有が途中にpreviewを開かない。

### 6 delivery/実機

全20FRとSpecの17story acceptance scenarioをtasksへ対応し、unitとfocused native、全CIのfresh censusを確認後にcanonical review。merge-readyならユーザー指示に従ってmergeするが、Issue80本文の16checkbox ACは別に個別判定し、代表実機確認がない場合はopenを維持する。親81もchild checkboxを早期に完了扱いしない。

## 検証境界

[quickstart.md](quickstart.md)に旧board migration、同asset複製/削除/Undo/GC、8向き/透明/高解像度、picker/receipt/再生成/save failure、古いcallback、出力pixels、説明Draft/読み上げと実機記録を対応する。test oracleはworld配置・Room実データ・native状態・画像pixelsとcache/decode上限で、implementationをなぞるassertにしない。
