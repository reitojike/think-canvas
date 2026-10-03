# 検証手順

## 前提

#73をmerge済みcurrent mainへ取り込み、Spec007と実際のpointer/Back/preview guardを再読する。JDK25/SDK37、既存Windows GMD launcherを使用する。依存/schema/workflowは変更しない。

```powershell
./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/check-public-boundary.ps1
git diff --check
./scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.EdgeAutoPanTest#stationaryEdgePointerKeepsMoveAnchoredAndCommitsOnce'
```

## 操作と証跡

1. pure helperの四辺/角/中央、単調性・上限、小canvas、dtとdensity、倍率.15/1/3でのpointer/camera差分を検証する。
2. manual frame clockで端のpointerを固定し、固定要素のpan・対象のpointer offset保持・release前BoardState不変を確認する。
3. 通常moveとMOVE handle、multi/region/inkの相対配置、release後一回Undo/Redo/save、Room再読み込みを照合する。
4. band外/UP/CANCEL/Back/2本指/stylus/save block/再生成・STOP後に継続と古いUPの確定が残らず、次の通常操作が成功することを確認する。
5. Pixel9相当のA/B profileで右/下/角/中央復帰/dropを比較する。幅/高さを超える一回move、停止時誤確定0、指とのずれと移動量をplanに記録して採用値を決める。
6. full source censusとfresh CI XMLをidentity/count/failure/error/skippedで照合し、Spec006/007と既存native gesture/accessibilityを維持する。
7. current-head canonical review、最新base、outdatedを含むthread0、実際のmergeとlatest Issue78 ACをProcess36で収束する。物理端末の横断UXはparent81で別記録する。

focusedはcandidateごとに一回、失敗は原因分類とcheckpointを行いblind rerunしない。検証は未実施。結果を確定したheadに紐付けて追記する。
