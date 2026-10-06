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
- 初回candidateのWindows Android17 fullとGitHub CI head `3b2e7b9` は251件成功、failures/errors/skipped=0。WindowsはsourceUnchanged=true、fresh exact XML。長押し16件、fling8件と既存保存/Undo/pinch/stylus/Back/画像resourceの回帰を含む。
- 初回canonical reviewのblank pickup/Back指摘をT013へ追加した。選択あり/なしのblank held→Back→UPを取消stageで消費し、次のBackで選択解除、さらに次でlistへ進むことを検証する。補正後の全CIは252件を対象とし、最終証拠はPRへ記録する。
- T013補正後のblank pickup/Back native focusedは1件成功、failures/errors/skipped=0、sourceUnchanged=true、fresh exact XML。選択あり/なしの取消、後続Backの選択解除/list移動を観測。基本検証も再成功。
- bc9e386のCIは同一headの37411214336で252件成功、37411215013でOffscreenIndicatorsTestの次pan停止位置1件失敗。成功結果だけを採らず、有限checkpoint後にT014で停止位置を期待する2 helperの入力を静止後releaseへ揃えた。production codeとfling専用traceは変更しない。
- T014後のOffscreenIndicatorsTest.recreationDoesNotRestoreStaleTargetsOrHitRegionsとViewportHistoryInteractionTest.staleViewActionRejectsSaveEditorToolAndNextGestureUsesCurrentStateのnative focusedは各1件成功、failures/errors/skipped=0、sourceUnchanged=true、fresh exact XML。新しいheadの全252件CIとcanonical reviewはPRへ記録する。
- 3b507c6のCI37414252313は252件中、ViewportHistoryInteractionTestのsearch IME cleanup後のquery field保持1件が失敗。追加補正前にIME fixture familyの有限checkpointを行い、T015でsearch cleanupだけを既存Espresso.closeSoftKeyboardへ揃えた。actual Backは既存IME/取消cycleとpickup Back testsに残し、productionは変更しない。
- T015後のvisibleAndOffscreenUndoRedoFocusWithoutExtraContentHistoryOrSaveはnative focused1件成功、failures/errors/skipped=0、sourceUnchanged=true、fresh exact XML。query/IME/内容/save/historyのassertを維持した。最終headの全252件CIとcanonical reviewはPRへ記録する。
- a2a6e7dのCI37417439745は252件成功、failures/errors/skipped=0。canonical reviewのtext pickup/selection semantics指摘に対し、全familyの有限checkpoint後にT016でtextだけの表示とactual selectionを分けた。frame/shadow/dimmingは維持し、未選択pickupのsemanticsとgripをselectionに昇格させない。
- T016後のpickupExplainsSelectionAdditionAndGroupMovementとelementLongPressDragMovesAndUndoRedoApplyはnative focused各1件成功、failures/errors/skipped=0、sourceUnchanged=true、fresh exact XML。未選択state/select label/gripなし、release追加後の選択/grip、集合/移動/保存/Undo/Redoを観測した。新headの全CIとcanonical reviewはPRへ記録する。
