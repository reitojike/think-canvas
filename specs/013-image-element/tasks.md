# 作業一覧: ローカル画像要素

**Input**: Spec013、plan/research/data-model/contracts/quickstart。testsは保存/geometry/lifecycle/resourcesの意味上不変条件に必要。

## Phase 1: Setup

- [x] T001 `specs/013-image-element/` の仕様・PO A・技術方式を確定し、checklistとread-only analyzeで20FR/7SCのcoverageを確認する。
- [x] T002 `app/build.gradle.kts` にExifInterface1.4.2を固定追加し、`local.properties` と標準runtimeのignored設定を用意する。依存/workflowの無関係な変更をしない。

## Phase 2: Foundation

- [x] T003 `app/src/test/java/com/thinkcanvas/canvas/ImageElementTest.kt` と `app/src/test/java/com/thinkcanvas/image/ImageDecodePolicyTest.kt` に有限/正寸法、canonical UUID、intrinsic1〜65535/100MP以下、aspect/2048px/4MPの意味上境界を追加する。（FR004/012/013/019）
- [x] T004 `app/src/main/java/com/thinkcanvas/canvas/ImageElement.kt` と `app/src/main/java/com/thinkcanvas/canvas/SpatialElement.kt` に検証済みimage modelと末尾default imagesを実装する。x/y/width/heightとx+width/y+heightは有限、寸法は正、id/assetIdはcanonical UUID。（FR004）
- [x] T005 `app/src/main/java/com/thinkcanvas/canvas/BoardState.kt` のsnapshot/restore/record、delete/move、retainedImageAssetIdsへimagesを統合し、画像接続矢印も同じUndo単位で扱う。（FR006/008/010）
- [x] T006 `app/src/main/java/com/thinkcanvas/data/CanvasDatabase.kt` と `app/schemas/` にimage row・3→4 migrationとread/replace/duplicate/delete/receiptの全transaction統合を追加する。既存4collection/receiptを保持。（FR009/010/017）
- [x] T007 `app/src/test/java/com/thinkcanvas/data/CanvasDatabaseTest.kt` に旧データを保つ1/2/3→4、画像付きreceiptのatomic rollback/同要求no replayを追加する。（FR003/009/017）
- [x] T008 `app/src/main/java/com/thinkcanvas/image/ImageAssetStore.kt` と `app/src/main/java/com/thinkcanvas/data/CanvasStore.kt` にUUID素材copy64MiB以内・static JPEG/PNG/WebP header検証・fsync/finalize/readback・lease/collectorを実装する。全file mutationは既存actor。（FR002/010/018/019）
- [x] T009 `app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt` のsession作成/requestSave/discard/onClearedへowner別現在/80履歴rootを同期反映し、`app/src/main/java/com/thinkcanvas/data/CanvasStore.kt` のqueued snapshot/checkpoint/leaseと保存済みasset rootでGCを保護する。（FR008/010）
- [x] T010 `app/src/androidTest/java/com/thinkcanvas/image/ImageAssetStoreTest.kt` にcopy故障/上限/無効UUID/shared ref/lease/Undo roots/staging orphanとcheckpoint競合のnative回帰を追加する。（FR002/010/019）

## Phase 3: US1 取り込む

**独立検証**: 写真/ファイル追加、取消/失敗、再生成、保存後再表示。viewport中央と元要素不変。

- [x] T011 [US1] `app/src/main/java/com/thinkcanvas/image/ImageDecodePolicy.kt` と `app/src/main/java/com/thinkcanvas/image/ImageAssetStore.kt` にsource100MP/65535px、sample4MP/2048px、EXIF8向き、透明alphaの同じbounded decoderを実装する。（FR004/012/013/019）
- [x] T012 [US1] `app/src/main/java/com/thinkcanvas/image/ImageImportViewModel.kt` と `app/src/main/java/com/thinkcanvas/image/ImageImportCheckpoint.kt` にtrusted task token、Picker/Reading/Accepted/Failed/Completed、固定request/board/viewport/patchとactor内atomic checkpointを実装する。元URI/filenameをBundleへ保存しない。（FR003/009/016/019）
- [x] T013 [US1] `app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt` に画像取り込みの既存receipt/save ack/一Undoを追加し、receipt済み、同owner再入、明示retry、task取消を既存save ownerへ通す。（FR003/006/009）
- [x] T014 [US1] `app/src/main/java/com/thinkcanvas/MainActivity.kt` と `app/src/main/java/com/thinkcanvas/canvas/CanvasControls.kt` の既存tool入口へ写真/ファイルpickerを配線し、開始viewport60%/長辺480world以内の初期geometry、最新Page/guard、text共有Deferred、再生成/token/receiptを照合する。（FR001/003/005/016/018）
- [x] T015 [US1] `app/src/main/java/com/thinkcanvas/image/ImageResources.kt` と `app/src/main/java/com/thinkcanvas/canvas/ImageElements.kt` にkey/revisionだけをComposeへ渡す32MiB cache・一decode・far256/mid1024/near最大2048/画面外skipと固定bounds placeholderを実装する。（FR012/019）
- [x] T016 [US1] `app/src/androidTest/java/com/thinkcanvas/image/ImageImportInteractionTest.kt` に実ContentResolver/Roomでwarm追加/取消/old result/rotation/fresh owner/receipt後Undo/save failure/manual retryを追加する。（FR001〜005/009/016/019）

## Phase 4: US2 整理とUndo

**独立検証**: 画像・他要素の複数選択、囲み/矢印、aspect resize/delete/Undo、複製片方削除。

- [x] T017 [US2] `app/src/test/java/com/thinkcanvas/canvas/SpatialGeometryTest.kt` と `app/src/test/java/com/thinkcanvas/canvas/BoardStateTest.kt` にimageの包含/gap/arrow/resize/80Undoと削除矢印復元の回帰を追加する。（FR006〜008）
- [x] T018 [US2] `app/src/main/java/com/thinkcanvas/canvas/SpatialGeometry.kt` と `app/src/main/java/com/thinkcanvas/canvas/RenderedGeometry.kt` へimage bounds/center/translation/region/gap/lasso/矩形attachmentを通す。gapはimageを伸張せず中心で移動する。（FR006/007）
- [x] T019 [US2] `app/src/main/java/com/thinkcanvas/canvas/SemanticNavigation.kt` と `app/src/main/java/com/thinkcanvas/canvas/ContentHistoryFocus.kt` へvisible/selected keep/image changed target/Undo focusを統合する。（FR007/008/012）
- [x] T020 [US2] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` と `app/src/main/java/com/thinkcanvas/canvas/ImageElements.kt` にhit/selected IDs/move/右下resize handle/edge pan/menu/deleteとlive guardを統合する。resize長辺40〜8192world、左上固定・intrinsic比。（FR006/007/016）
- [x] T021 [US2] `app/src/main/java/com/thinkcanvas/board/BoardDuplication.kt` と `app/src/main/java/com/thinkcanvas/data/CanvasStore.kt` でimage element/arrow targetを再採番しimmutable asset共有・片方deleteを保護する。（FR010）
- [x] T022 [US2] `app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` にregion/multiselect/arrow/resize/Undo/duplicate/deleteと古いimage callbackのnative regressionを追加する。（FR006〜010/016）

## Phase 5: US3 見渡す・出力

**独立検証**: 高解像度/透明/8向き、thumbnail、board/selection PNG、missing asset失敗。

- [x] T023 [US3] `app/src/test/java/com/thinkcanvas/board/SharePlanTest.kt` と `app/src/test/java/com/thinkcanvas/board/BoardGeometryTest.kt` にimage-only/content bounds/selected region画像/矢印と既存4096px/16MP制限の回帰を追加する。（FR007/011）
- [x] T024 [US3] `app/src/main/java/com/thinkcanvas/board/SharePlan.kt` と `app/src/main/java/com/thinkcanvas/board/BoardImageRenderer.kt` にimageを既存対象集合/geometry/layerへ統合し、全素材batch lease、一枚ずつbounded decode/解放、missing asset全体失敗を実装する。（FR011/012/013/019）
- [x] T025 [US3] `app/src/main/java/com/thinkcanvas/board/BoardThumbnail.kt` と `app/src/main/java/com/thinkcanvas/MainActivity.kt` に同じ256px resources/向きとoutput asset readerを配線し、既存ImageDeliveryのPNG/copy/shareを維持する。（FR011/013/018）
- [x] T026 [US3] `app/src/androidTest/java/com/thinkcanvas/image/ImageRenderingTest.kt` に8向きのquadrant/alpha/高解像度sample/cache上限、thumbnail/board/selection pixelsとmetadata非転写を追加する。（FR011〜013/018/019）

## Phase 6: US4 代替テキスト/読み上げ

**独立検証**: 任意説明/空欄/取消/破棄確認、一Undo/reopen、native accessibility actions。

- [x] T027 [US4] `app/src/main/java/com/thinkcanvas/canvas/ImageDescriptionDialog.kt` と `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` に説明Draft/完了/取消/変更済みBack破棄確認/空欄消去、一Undo/saveとneutral readinessを追加する。caption/OCR/AIなし。（FR014/015/016/018）
- [x] T028 [US4] `app/src/main/java/com/thinkcanvas/canvas/ImageElements.kt` と `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` に説明または「画像」、選択状態、select/add/remove/move/resize/delete/edit actionsとlive利用可否を追加する。（FR015/016）
- [x] T029 [US4] `app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` に説明取消/空欄/Undo/recreate、native選択/移動/resize/削除、IME/editor/text共有保護を追加する。（FR014〜016）

## Phase 7: 検証・収束・delivery

- [x] T030 `specs/013-image-element/tasks.md` をconvergeし、20FR/7SC/全story/有限surfaceの未実装残差を追記・解消する。（FR020）
- [x] T031 `specs/013-image-element/quickstart.md` にlint/unit/build/schema4/公開境界、focused/native full censusの実測を記録する。標準GMDと現行CIを使う。（FR017/020）
- [ ] T032 `specs/013-image-element/quickstart.md` とPRへcurrent head CI/canonical/最新base/thread0/merge証跡を記録する。merge-readyなら許可済みmerge。（FR020）
- [ ] T033 `specs/013-image-element/quickstart.md` とIssue80へ代表実機の機種/OS/head/compact/largefont/TalkBack/主要経路の実測を記録する。未確認なら未完了を維持。（FR015/020、SC006）
- [ ] T034 `specs/013-image-element/quickstart.md` とIssue80/81の最新16ACを意味上判定し、達成checkboxだけ更新する。実機未確認ならIssue80と親はcloseしない。

## 依存・実行方針

T001→T002→T003〜T010のfoundation→US1→US2→US3→US4→収束/検証/delivery。US1と各storyは上記独立scenarioで意味上検証する。foundationの保存とgeometryは1PR内でreview/rollbackできる範囲。少なくともUS1の表示/保存まで未完成のfoundationだけを完成機能として公開しない。

read-only保存/描画調査は異なるsurfaceなので下位モデルで並列化済み。sourceへの書込みは共有ファイル競合を避けrootが順番に行う。unit/nativeの意味上テストは対応実装前に定義し、同時Gradle/GMDなし。検証中はsource/docs/Gitをfreezeする。

## Phase 8: Convergence

- [x] T035 `app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` でダイアログのIME表示を観測してからBackを送り、hide-onlyと変更済み破棄確認を分けて検証する。FR-016、US4/AC3のnative待機条件を補う。（partial）
- [x] T036 `app/src/androidTest/java/com/thinkcanvas/image/ImageRenderingTest.kt` で圧縮streamを細分IDAT・空IDAT・末尾paddingへ再包装した有効PNGを実取り込みし、元pixelsと一致することを検証する。FR-019、US1/AC5の破損拒否と有効素材受理を両立する。（partial）
- [x] T037 `app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` で未確定の空白single-tap中に既存image accessibility actionから説明を開き、遅延したtapが画像editorや既存内容へ二重actionを起こさないことを検証する。FR-016、planの既存neutral契約を補う。（partial）

## Phase 9: Convergence

- [x] T038 `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` の説明editor callbackで最新Draftを確定対象にし、破棄確認中の古い入力/完了/取消を拒否する。`app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` で同sessionの古い完了による説明巻戻しと確認の迂回を検証する。FR-016、US4/AC3、planのlive guardを補う。（partial）
- [x] T039 `app/src/androidTest/java/com/thinkcanvas/image/ImageCanvasInteractionTest.kt` のnative resizeを、既存一般規則に従って長押し移動後の明示tap選択から開始する。選択しない移動とselected handleの前提を区別する。US2/AC1、T022の意味上検証を補う。（partial）

## Phase 10: Convergence

- [x] T040 `app/src/main/java/com/thinkcanvas/image/ImageImportViewModel.kt` と `app/src/main/java/com/thinkcanvas/MainActivity.kt` で未適用のaccepted要求の追加先が消失した場合にcheckpointを終了し、選び直せる失敗状態へ戻す。`app/src/androidTest/java/com/thinkcanvas/image/ImageImportInteractionTest.kt` でfresh owner復元後の追加先削除・再試行・素材回収・新しい取り込みを実Main/Roomで検証する。FR-003/010/019、US1/AC5と追加先削除のEdge Caseを補う。（partial、HIGH）

## Phase 11: Convergence

- [x] T041 `app/src/androidTest/java/com/thinkcanvas/canvas/ViewportHistoryInteractionTest.kt` のeditor終了後の次panについて、native IME表示/非表示、Draft終了、world cameraと描画座標の一致を準備条件として観測する。panの90/25pxと2px許容差、内容/保存不変を維持し、camera変化自体も照合する。FR-016/017、SC-007と既存Spec010の独立gestureを補う。（partial）
