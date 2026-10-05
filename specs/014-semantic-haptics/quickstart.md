# 検証

## 自動検証

LongPressGestureTestのnative操作でLocalHapticFeedbackの種類・回数を記録し、RoomとUndo/Redoを照合する。dragとthreshold以下のjitterを区別する。

```powershell
./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
pwsh -File scripts/check-public-boundary.ps1
git diff --check
./scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.LongPressGestureTest#blankLongPressDragInsertsGapAsOneUndoableOperation'
```

full Android17 GMDとcanonical reviewはPRの現行headで確認する。

## ローカル結果（2026-10-06）

- lintDebug / testDebugUnitTest / assembleDebug / compileDebugAndroidTestKotlin成功。
- 変更前focused Windows GMD: blank long-press→gapで期待`[LongPress]`に対し`[LongPress, LongPress]`を観測（RED）。fresh exact XML 1 test / 1 failure、sourceUnchanged=true。
- 変更後の同じfocused: WINDOWS_GMD_PASS / FRESH_EXACT_XML、1 test / failure0 / error0 / skipped0、sourceUnchanged=true、manualInterventions0。保存とUndo/Redoも成功。
- public boundary / git diff --check成功。
- その後のendpoint取消テストの記述補正を含むfinal candidateはfull CIで確認する。
- endpoint focusedの初回は短い矢印の48dp端点/曲げhandleが重なり、端点変更を観測できず失敗。テストfixtureだけを12×touchSlopの長さに補正し、保存Idleをnative操作の開始条件として待つようにした。本番のhit判定は変更していない。
- 補正後の`arrowCreationAndEndpointChangesConfirmOnce`: WINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。作成と端点変更のConfirm各1回、取消0回、保存とUndo/Redoを確認。

## 実機評価（確認済み）

Pixel9a / Android17で利用者が確認し「強さ・頻度とも自然で違和感なし」と回答。対象はhead `09fd447` の [CI配布APK](https://github.com/reitojike/think-canvas/actions/runs/37335566703/artifacts/11355713554)。以降はtest fixture・文書と、native取消時の成功feedback抑制を補正した。正常操作のfeedback種類・強さ・回数は同じ。結果は [Issue #101](https://github.com/reitojike/think-canvas/issues/101#issuecomment-5998118892) に記録した。個別試行回数、端末設定、stylus使用は未記録。以下は再確認用の観点。

1. 要素/空白の長押し成立が一度伝わるか。release/menuとdrag/move/gapで重複がないか。
2. 四角・丸・囲み・矢印を作成し端点変更。成功振動が長押しと区別でき、強過ぎないか。
3. 指とstylus（利用可能な場合）で連続筆記し、stroke終了が無振動で自然か。
4. 要素を囲み内外へ運び、案内だけで分かるか。
5. 端末のtouch feedbackを無効にしても操作と案内が成立するか。

full CIとcanonical reviewが完了するまでIssueはopenに保つ。

## CIで見つかった既存差分

head09fd447の236件中4 failureはendpoint fixture、region fixture、native create取消、共有入力modal finder。endpoint補正後のhead6a9bfffは236件中3 failure: region fixture、native create取消、既存editor IME readiness（共有入力finderは成功）。既存create取消は#115へ分離し、本featureは取消の成功振動のみ抑制する。既存modal/IME failureは未変更surfaceでありfinal-head full CIで再観測する。greenを得るための同一head blind rerunはしない。

region focusedの診断では初期fit/固定sleepに依存したnative入力で成立feedback0、移動なしを観測した。テストcameraを中央に固定し、native window focusと実際のLongPressを待つfixtureへ限定補正した。旧Compositionを破棄する仮説は同じfailureで否定され、その変更は戻した。entryではLongPress1回・入場案内・Room保存が成功し、同じ成立待ちを共通held helperへ揃えた。例外時はCANCELでpointerを解放する。製品の時間・距離閾値は変更していない。

補正後regionEntryAndExitKeepGuidanceWithoutExtraFeedbackはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1 test / failures0 / errors0 / skipped0、sourceUnchanged=true、manualInterventions0。囲み入退出は各LongPress1回・追加0回、案内・Room保存・2回Undoの復元を確認した。

補正後arrowCreationFailureAndCancellationStaySilentはWINDOWS_GMD_PASS / FRESH_EXACT_XML、1/0/0/0、sourceUnchanged=true、manualInterventions0。作成失敗とACTION_CANCELの成功振動0回を確認し、既存の取消時確定結果は#115として別に観測した。

## 収束照合

FR6件、受け入れscenario7件、SC4件、planのfeedback/不変条件、Constitution I〜Vを照合した。正常releaseの成功feedback条件にpartial 1件を検出してT012へ追加し、実装とfocused取消回帰で解消した。追加すべきbuildable taskは0件。未達はT011のfinal-head full CI / canonical review / fresh base / thread0 / mergeであり、この照合だけでmerge-readyとは扱わない。.specify/extensions.ymlはなく追加hookなし。
