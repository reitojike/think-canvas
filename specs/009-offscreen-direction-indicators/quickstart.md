# Quickstart / validation

前提: 現行JDK/Android SDK、既存の標準Android CIとWindows GMD launcherを使用する。秘密/私的入力は追跡しない。

1. `./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。
2. `pwsh -File scripts/check-public-boundary.ps1`、`git diff --check`、schema差分なしを照合。
3. unitは四辺/四角、境界接触、倍率.15/1/3、density1/3、collisionと小画面、省略/重複を照合。
4. 標準launcherで`com.thinkcanvas.canvas.OffscreenIndicatorsTest#searchIndicatorReturnsToCurrentResultWithoutMutation`等のfocused methodを一つずつ実行する。
5. 実検索/単一とmulti selection→nativepan/pinch→条件付き表示→tap/action→消滅。固定要素のscreen位置からviewportを観測し、BoardSnapshot/Room/Undo/Redo/save0と選択意味を照合する。
6. query/result/selection変更、Back、editor/IME、move preview、save、recreationで古いhitと意味が残らず次のpanが成立する。
7. final headの両Android CI、fresh source/XML census、最新base/threads0、最後の依頼より新しいcanonical reviewで収束。merge後Issue76の最新11ACを逐条確認する。

## Delivery status

未実装・未検証。source/CI artifactはまだない。物理端末の横断dogfoodingは親81へ残す。

## 実装中の検証

- analyzeは10FR・4SC・9scenario・有限設計を13tasksへ対応付け、Constitution5原則に違反なし、coverage100%、CRITICAL/HIGH0。custom navigation-uxはreviewer-owned8項目を未査読のまま維持し、POの進行指示に沿って実装した。
- 純粋geometry15件を追加。初回unit119件中1件は安全な端のFloat丸めによりtouch領域が微小に外へ出る失敗。projectionとtouch anchorをsafe rectへclampして、全unit119件・lint・debug/androidTest buildが成功した。
- convergeでFR-006/007の入力寿命の直接検証不足2件をT014/T015へappendし、move preview/native CANCELとpending acknowledgement/removed targetのsame-turn stale action回帰を実装した。
- focused emulator・全CI・canonical reviewは未実行。成功と扱わない。
