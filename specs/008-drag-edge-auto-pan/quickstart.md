# 検証手順

## 前提

#73をmerge済みcurrent mainへ取り込み、Spec007と実際のpointer/Back/preview guardを再読する。JDK25/SDK37、既存Windows GMD launcherを使用する。依存/schema/workflowは変更しない。

```powershell
./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/check-public-boundary.ps1
git diff --check
./scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.EdgeAutoPanTest#stationaryPointerPreservesOffsetStopsInCenterAndCommitsOnce'
```

## 操作と証跡

1. pure helperの四辺/角/中央、単調性・上限、小canvas、dtとdensity、倍率.15/1/3でのpointer/camera差分を検証する。
2. manual frame clockで端のpointerを固定し、固定要素のpan・対象のpointer offset保持・release前BoardState不変を確認する。
3. 通常moveとMOVE handle、multi/region/inkの相対配置、release後一回Undo/Redo/save、Room再読み込みを照合する。
4. band外/UP/CANCEL/Back/2本指/stylus/save block/再生成・STOP後に継続と古いUPの確定が残らず、次の通常操作が成功することを確認する。
5. Pixel9相当のA/B profileで右/下/角/中央復帰/dropを比較する。幅/高さを超える一回move、停止時誤確定0、指とのずれと移動量をplanに記録して採用値を決める。
6. full source censusとfresh CI XMLをidentity/count/failure/error/skippedで照合し、Spec006/007と既存native gesture/accessibilityを維持する。
7. current-head canonical review、最新base、outdatedを含むthread0、実際のmergeとlatest Issue78 ACをProcess36で収束する。物理端末の横断UXはparent81で別記録する。

focusedはcandidateごとに一回、失敗は原因分類とcheckpointを行いblind rerunしない。prototypeは下記planの比較でAを採用した。最終headのCI・review・deliveryは[PR87](https://github.com/reitojike/think-canvas/pull/87)と[Issue78](https://github.com/reitojike/think-canvas/issues/78)の最新証跡で判定する。物理端末の横断UXはparent81で扱う。

## 実装中の検証記録

- 先行回帰head `72c14bb`、run `20261003T153318Z-66eb78c4a561463b9816fe1aa33a55af`は対象一件だけを実行し、静止pointer中のcamera継続がない箇所で失敗した。sourceUnchanged/owned XMLともtrue、介入0、1/1/0/0。XML SHA256 `201843425C5C47C2CF91AB869FB9B434E632DEFA378E05C77F835009CCD29E9C`。
- 統合head `35f6e2f`、run `20261003T154113Z-1ca882d6b1184664bdc777c265fc28b7`は同じ操作が1/0/0/0で成功。静止pointerのoffset、中央停止、UP前内容不変、preview/commit描画一致、Room、一回save/Undo/Redoを照合した。sourceUnchanged/owned XMLともtrue、介入0。XML SHA256 `77393E2DCC22530E5CDE7D2204B16D7C7F4771DE363502A87B92ED4BFD3AFC80`。
- pure helperは19件、既存を含むunit104件が成功。15%でのFloat丸め差は0.001 world単位以下で比較する。
- 初回の先行回帰は実行中に独立unitファイルが追加されsourceUnchanged=falseとなったため証跡から除外した。後のcompile failureはテストの文字列補間と誤ったimportとして分類し、sourceを修正した。0-testやstale結果を成功証跡にしない。
- convergeは10 FR、4 SC、12操作条件、設計6項目、Constitution5原則を照合。入力倍率両端、A/B multi drop、editor/non-dragの検証残差をtasks T015〜T017へappendした。
- head `95a042a`のBack回帰は、取消と旧UP拒否の後、Compose合成入力へ切り替えた次のpan確認で失敗した。同じproduction codeで次の操作もnativeへ揃えたhead `b97fc5a`、run `20261003T160350Z-f001dd9f73c94022a5e281372ce5493a`は1/0/0/0で成功。Backと旧UPは同一UI turn、queued frame停止、Room/履歴不変、独立した次のpanの110px/35pxを照合した。sourceUnchanged/owned XMLともtrue、介入0、XML SHA256 `623AE719F4C0C6F99030C1E7DB9E8C0531C96226AD38893E0345C45D880C2E22`。
- A/B比較は`boundedProfilesCarryBeyondViewportAndStopBeforeDrop`と`boundedProfilesKeepMultiSelectionTogetherOnDrop`。結果JSONをtest storageと`EdgeAutoPanPrototype` logcatへ記録する。CI artifactの`logcat-com.thinkcanvas.canvas.EdgeAutoPanTest-<method>.txt`からmodel/density/移動量/offset/停止/saveの値を再読できる。操作成功は人間の片手操作・物理端末の快適さの判定とは区別する。
- T015〜T017の実装後にconvergeを再実行し、10 FR・4 SC・12操作条件・設計6項目・Constitution5原則と全17 taskを照合した。追加のbuildable残差は0件、tasks.mdは変更しなかった。prototypeの採否とdelivery検証は既存のT007/T013/T014で未完了として管理する。
- 下位モデルのread-only点検で、15%のFAR表示では非選択BODYの参照要素が消えることを確認し、独立した固定参照をTITLEにした。選択対象や移動契約は変えない。修正後のlint/unit104件/debug/androidTest build、公開境界186ファイル、schema/diffが成功した。
- 固定head `dbc6770`のA/B単要素比較はrun `20261003T161351Z-1bbf80cd4d6e4f10a0bdc50c4a3e08c0`で1/0/0/0。Pixel7/API37、canvas 1080×2201px、density 2.625、180 frame保持の右/下/角6行で、Aのpanは各軸2593px、Bは3938px。offset誤差はA 0px、B最大1px、中央停止drift 0、save各1回、preview/commit・Room・Undo/Redoを照合。sourceUnchanged/owned XML true、介入0、XML SHA256 `66F566919F3E49496CCC6ED9E41C1BD097783842B89D067E8A62D1F096233784`。採用判断はPixel9の比較結果も読んで行う。
- 同じheadのnative pinch→15%/300%→移動はrun `20261003T161847Z-4221313acd2c450f9529032c5ad2cbd6`で1/0/0/0。pointer offset、中央停止、preview/commit一致、一回save・Room・Undo/Redoが成功。sourceUnchanged/owned XML true、介入0、XML SHA256 `DB4CCD1FB38A4321F2AFFD1D856EEE8F703A58273AC664093B3B6CFAA5D691CE`。
- [CI 37136065497](https://github.com/reitojike/think-canvas/actions/runs/37136065497)、head `dbc6770`はlint/unit/build/public成功、Pixel9 fresh XMLは128/1/0/0、missing/extra/duplicate=0、XML SHA256 `DE3A740522AF8B10D01C671A358876CD0554F1A85C5D18C882987C9FA82E5076`。既存110件、A/B比較、倍率両端、他の停止/保存回帰は成功。唯一の失敗はstylus切替のfixtureがfinger DOWN後にMotionEvent sourceをTOUCHSCREENからSTYLUSへ変え、InputDispatcherが`Canceling stream: last source was TOUCHSCREEN`/`dropping inconsistent event`として後続を捨てたため。sourceは開始時のTOUCHSCREENに固定し、追加pointerのtoolTypeだけをSTYLUSにするbounded補正とした。production・保存・座標の変更はない。修正後focusedとfinal-head CIが揃うまでfull greenと扱わない。
- source固定head `ff50a41`のstylus focused、run `20261003T163417Z-f5239bd7981041adab5674e1c2ebeef9`は1/1/0/0。owner停止、text無移動、一回stroke保存は成功し、最後のRoom整数時間の完全一致だけが253→252msで失敗した。sourceUnchanged/owned XML true、介入0、XML SHA256 `ED2479E10A12B2BB76B076FBB25271D5C5F48D3CAECE3DCDC74B289A69B712ED`。[Ink公式ソース](https://github.com/google/ink/blob/main/ink/strokes/input/stroke_input_batch.cc)はbatchのelapsed timeをFloat秒で保持する。ConvergeのMEDIUM/unrequested残差1件をT018へappendし、短い線の経過時間だけ1ms以内、world座標・点数・種類・ID・開始終了時刻・単調性・他content・一回save/Undoは厳密に検証する。既存Spec003のworld配置・保存を維持し、保存codecやschemaを広げないbounded補正2回目とした。final-head全件green/reviewは最新PR証跡で判定する。
- 補正head `add459f`の同focused、run `20261003T164000Z-3dc50ea61f3845708fe696a155faa1cd`は1/1/0/0。比較用の空Inkモデルcopyが非空のinit制約に違反した担当者のfixture誤りとして分類した。sourceUnchanged/owned XML true、介入0、XML SHA256 `AB10BEACD69A67E598C299FF93CDDFBC2CD9F36D43497EAA1708A205FFF617FA`。補正3回目前に[finite family checkpoint](https://github.com/reitojike/think-canvas/pull/87#issuecomment-5971200766)と[scope再固定](https://github.com/reitojike/think-canvas/pull/87#issuecomment-5971210412)を行い、T019のedge稼働中CANCEL/次のpanと、T020の直接metadata比較だけに追加1 roundを限定した。production/codec/schema/製品scopeは同じ。旧head ff50a41のCI37137335067は置換のためcancelled、成功証跡に使わない。

## Merge後のdelivery確認（2026-10-04）

[PR87](https://github.com/reitojike/think-canvas/pull/87)はmerge SHA `d33ed2e7b6a5242c5821169c642e169a882c1b52`でmerge済み。final head `0d3b8cd82ef12ba76e1f9790fd5f4d9db83d935b`とmerge treeは同じ。両CI [37138090347](https://github.com/reitojike/think-canvas/actions/runs/37138090347)成功、unit104、fresh instrumentation128/0/0/0、missing/extra/duplicate/skipped0。XML SHA256 `2F21941440513959E542F4CF9DCE44D05CC1244862B742D63907D8E34ACE655B`。最後の依頼より新しい[current-head canonical clean](https://github.com/reitojike/think-canvas/pull/87#issuecomment-5971482270)、base最新/thread0を照合した。

[Issue78最新12ACのclosure](https://github.com/reitojike/think-canvas/issues/78#issuecomment-5971536596)で全条件達成を逐条確認し、COMPLETED close済み。T013/T014をこの完了事実で同期した。物理端末の横断dogfoodingと親81の完了は別途未確認。
