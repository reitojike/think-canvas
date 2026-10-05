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

## 実機評価（未実施）

対象端末はPixel9a / Android17。対象APK/headを記録し、以下を各5回程度確認する。

1. 要素/空白の長押し成立が一度伝わるか。release/menuとdrag/move/gapで重複がないか。
2. 四角・丸・囲み・矢印を作成し端点変更。成功振動が長押しと区別でき、強過ぎないか。
3. 指とstylus（利用可能な場合）で連続筆記し、stroke終了が無振動で自然か。
4. 要素を囲み内外へ運び、案内だけで分かるか。
5. 端末のtouch feedbackを無効にしても操作と案内が成立するか。

結果の強さ・頻度・違和感をIssue #101へ記録。未評価を成功と扱わずIssueをopenに保つ。
