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

focusedはcandidateごとに一回、失敗は原因分類とcheckpointを行いblind rerunしない。最終headのCI・reviewとprototypeは未収束。物理端末の横断UXはparent81で扱う。

## 実装中の検証記録

- 先行回帰head `72c14bb`、run `20261003T153318Z-66eb78c4a561463b9816fe1aa33a55af`は対象一件だけを実行し、静止pointer中のcamera継続がない箇所で失敗した。sourceUnchanged/owned XMLともtrue、介入0、1/1/0/0。XML SHA256 `201843425C5C47C2CF91AB869FB9B434E632DEFA378E05C77F835009CCD29E9C`。
- 統合head `35f6e2f`、run `20261003T154113Z-1ca882d6b1184664bdc777c265fc28b7`は同じ操作が1/0/0/0で成功。静止pointerのoffset、中央停止、UP前内容不変、preview/commit描画一致、Room、一回save/Undo/Redoを照合した。sourceUnchanged/owned XMLともtrue、介入0。XML SHA256 `77393E2DCC22530E5CDE7D2204B16D7C7F4771DE363502A87B92ED4BFD3AFC80`。
- pure helperは19件、既存を含むunit104件が成功。15%でのFloat丸め差は0.001 world単位以下で比較する。
- 初回の先行回帰は実行中に独立unitファイルが追加されsourceUnchanged=falseとなったため証跡から除外した。後のcompile failureはテストの文字列補間と誤ったimportとして分類し、sourceを修正した。0-testやstale結果を成功証跡にしない。
- convergeは10 FR、4 SC、12操作条件、設計6項目、Constitution5原則を照合。入力倍率両端、A/B multi drop、editor/non-dragの検証残差をtasks T015〜T017へappendした。
