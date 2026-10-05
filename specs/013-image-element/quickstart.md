# 検証手順: ローカル画像要素

## 前提

現行JDK/SDK/Gradleと[PR前検証](../../docs/runbooks/pre-pr-verification.md)、[標準Windows GMD](../../docs/runbooks/windows-android-gmd.md)を使う。新しいrunner/workflowは作らない。test候補を固定し、同時Gradle/GMDは一つ。生成schema4を追跡版と照合する。

## unitとfocused native

| 観測する結果 | 検証surface |
| --- | --- |
| 正/有限geometry、ratio resize、world中心・領域包含・gap・arrow、80Undo/Redo | ImageElement/SpatialGeometry/BoardState/ContentHistoryFocus unit |
| 全旧familyとreceiptの3→4・1→4保存、atomic image receipt、複製/delete | CanvasDatabase unit、実Room native |
| コピーcancel/failure/容量/未知形式、UUID path、partial回収、shared asset/Undo/lease roots | ImageAssetStore unit可能部分とnative filesystem/Room |
| EXIF8向きの色quadrant、PNG透明、巨大寸法/sample4MP、cache32MiB/一decode | ImageDecodePolicy unit、ImageAssetStore/Resources native |
| photo/file一枚、picker取消、遅延/二重result、rotation/fresh owner、receipt後Undo、save/manual retry | ImageImportInteraction native（実ContentResolver/Store/Room） |
| 説明Draft/空欄/取消/Back破棄確認、一Undo、select/move/resize/delete | Composeとnative accessibility action |
| ordinary drag/pinch、region/multiselect/arrow/auto-pan、stale image callback、text共有Deferred | Canvas既存回帰とimage native |
| image入りthumbnail/board/selection PNG、同じgeometry/向き、元metadataなし、missing asset失敗 | SharePlan unit、renderer pixels native |

一つのfocused成功を全件成功として扱わない。full required CIは現行headとfresh XMLのsource census一致、失敗/error/skip/欠落/extra/重複0を確認する。dirty reviewやhead変更では旧証跡を再利用しない。

## 代表実機（Issue80自身の完了条件）

[実機配布手順](../../docs/runbooks/device-verification.md)に従ってdebug artifact/APKを用意する。端末機種・Android版・対象commit・画面/文字サイズと結果をIssue80へ記録する。

1. 写真pickerとファイルpickerから縦横写真・透明PNGを追加、取消、不正/非対応素材の失敗から戻る。
2. Pixel9a相当compact viewportで画像を移動、縦横比resize、囲み/multiselect、矢印接続、Undo/Redo、再起動を確認。
3. 高解像度画像を複数置き、遠景/中景/近景、画面外、一覧thumbnail、board/selection出力を確認。
4. board複製、片方/要素削除、Undoで残すべき素材が消えないことを確認。
5. TalkBackとlarge fontで追加、任意説明、空欄説明、select/move/resize/delete、Back破棄確認を完了する。
6. editor/IME/保存失敗/共有previewの間に画像追加・古いactionが割り込まず、元の操作を保つことを確認。

未接続/未確認ならT033とIssue80の代表実機ACを未完了とする。実機結果なしでIssue80をcloseせず、親81の横断UX確認も別に残す。

## delivery

全FR/ACをconverge、公開境界とdiffを確認しPRへ。現行headのCI→GitHub top-level canonical review→最新base/threads0でmerge-readyを判定する。mergeはユーザー許可済み。Issue closeは最新16ACを個別に確認し実機証跡を含む達成条件だけをチェックする。

## ローカル検証記録（2026-10-05）

- 基本検証: `lintDebug` はerror 0、unit 189件はfailure/error/skip 0、debug APKとandroidTest APKのbuild成功。Room schema4は生成版と追跡版が一致。公開情報境界と`git diff --check`も成功。
- 標準Windows GMD（Pixel 7 / API37）の全件run `20261005T034027Z-95c6f78fea84417c81b2bce9529968a8` はsource census227件と一致し、226成功・1失敗・error/skip 0。移動後に未選択の画像をresizeしようとしていたテストの前提を補正した。このrunは最終説明callback補正より前なので、最終候補の全件成功証跡には使わない。
- 移動後の明示tap選択、resize、通常dragのpanを補正後にfocused検証。run `20261005T041046Z-41aaf02f08524ba182fb78de10e053d6` は対象1件成功。
- 説明の取消、IME hide-only Back、変更済み破棄確認、確認中の古い入力/完了/取消拒否、最新Draft確定、空欄、Undo、再生成をfocused検証。run `20261005T041507Z-6a2cfe7b8cd047899c9817f0a8b39356` は対象1件成功。
- 以上のGMD結果はいずれもowned XMLのfreshness/対象一致と実行前後のsource不変を確認した。PNGの細分/空IDAT/末尾padding、破損拒否、未確定空白tapと画像editorの競合は全件run内で成功。
- 現行PR headのrequired CIではPixel 9 / API37の全227件を改めて照合する。CI・canonical review・base・thread・mergeの最終証跡はPR/Issueへ記録する。代表実機は未確認で、T033とIssue80/81の該当条件を未完了に保つ。
