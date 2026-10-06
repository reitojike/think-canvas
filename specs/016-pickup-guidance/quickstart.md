# 検証手順

[PR前手順](../../docs/runbooks/pre-pr-verification.md)と[実機確認](../../docs/runbooks/device-verification.md)に従う。
1. lintDebug/testDebugUnitTest/assembleDebug/compileDebugAndroidTestKotlin。
2. `scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.LongPressGestureTest#対象method'`でnative focusedを一件ずつ。他Gradleと重ねない。
3. held、release/menu、drag、長時間保持、選択追加/集合、blank、画像枠とselection不変、取消/Backを照合。
4. fresh sourceでfull Android回帰、保存/Undo、pinch/stylus、region結果通知。
5. CI headのAPKをPixel 9a/Android17へ配布し、理解しやすさと誤操作を記録。

実機未達ならmerge gateと分け、Issue openを維持。[PR後手順](../../docs/runbooks/post-pr-convergence.md)でCI/review/threadを確認。

## 実装時の検証記録

- 変更前の要素release native focusedは成立案内待ちで失敗し、差分を観測した（fresh XML 1件、sourceUnchanged=true）。
- 変更後の画像pickup native focusedは1件成功、失敗/skip0、sourceUnchanged=true。未選択画像の枠をpixelで観測し、selection/resize handle/画像モデル/Roomが不変であることを確認した。
- lintDebug/testDebugUnitTest/assembleDebug/compileDebugAndroidTestKotlin、公開境界、diff check成功。
- CanvasScreenの最終instruction offsetは64925。JVM上限65535の範囲内。
- 全Android回帰、対象head CI/review、実機評価の最終証拠はPRとIssueへ記録する。
- Windows Android17 fullは251件成功、failures/errors/skipped=0、sourceUnchanged=true、fresh exact XML。長押し16件、fling8件と既存保存/Undo/pinch/stylus/Back/画像resourceの回帰を含む。
