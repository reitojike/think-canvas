# 検証

## 自動

FlingGestureTestのnative入力で低速の即停止、高速の継続、pause/cancel、各割り込み、表示履歴とcontent/Room/save/Undo不変を照合する。実際のwindow focusとframeを観測して入力を開始する。

基本検証はlintDebug/testDebugUnitTest/assembleDebug/compileDebugAndroidTestKotlin、scripts/check-public-boundary.ps1、git diff --check。Windows focusedはrepository-owned run-windows-gmd.ps1の単一Class#methodで実行する。全Android17 GMDとreviewはPR現行headを使用する。

## Pixel9a / Android17比較（未実施）

現行即停止APKと条件付き慣性APKの対象headを記録する。短距離をゆっくり合わせてrelease、長距離を払う、途中に触れ直す、pinch、Back、ツール/編集、片手操作を比較する。慣性の強さ、狙った位置での停止、誤操作、採否をIssue #102へ記録する。未確認ならIssueをopenに保つ。

## ローカル観測

変更前focused fastPanContinuesAndUsesOneHistoryBoundaryはrelease後のcameraが同じでRED。fresh exact XML1件/1 failure、sourceUnchanged=true。新規統合はCanvasScreen method size上限でcompile failureとなり、planのbounded checkpointに従って新規処理・zoom描画leaf・共有停止境界を分離した。補正後のcompileDebugKotlin/compileDebugAndroidTestKotlinは成功（27秒）。

変更後fastPanContinuesAndUsesOneHistoryBoundaryはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。払い後の継続移動、一回のback/forward復元、内容/Room/save/編集Undo不変を確認した。後続でFoundation標準の1x touch physicsと描画leaf抽出を追加し、現行全CIで再検証する。

slowPanAndPausedReleaseStopExactlyはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。低速と停止後releaseの追加移動0、内容/Room/save/編集Undo不変を確認した。

toolAndEditorAdmissionRejectQueuedFramesは手順補正後WINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。native tool touchと可視位置editor admissionの同期停止、queued frame拒否、content/Room/save/編集Undo不変を確認した。並行実行した初回ownership failureは合格に用いない。基本lint/unit/build/androidTest compileは独立に成功（1分46秒）。

## 収束照合

FR7件、受け入れscenario9件、SC4件、planの速度/停止/履歴/標準設定、Constitution I〜Vを照合した。追加すべきbuildable gapは0。実機比較（T011）と現行headのfull CI/review/merge（T012）は外部完了条件として未達のまま記録する。custom checklistはreviewer-ownedのまま保持。extension hookは存在しない。

disabledSystemAnimationsKeepStandardTouchDecayはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。system animation0でも複数frameで移動して自然停止し、内容/保存/編集Undo不変。共有停止境界・zoom/history描画leaf分離後のCanvasScreen最終offsetは64574で上限内。最終CIは全247 instrumentationを検証する。

初回head25e10cfの全CIは247件中2 failure（newTouchのnative時刻順序/cleanup、既存handoff後helperの無条件即停止期待）。PRへ入力family checkpointを記録し、test-only correctionへ限定した。補正後newTouchStopsAndNextSlowPanOwnsCameraはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。同期停止、一回のhistory往復、次の低速pan、内容/保存不変を確認した。

既存secondFingerCancelsMoveAndHandsOffToPinchWithoutSavingも停止後UPを明示した補正でWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。move取消→pinch handoff、次の精密pan、内容/Room/保存不変を確認した。今回の補正はtest-onlyでAPK本体はhead25e10cfと同じ。
