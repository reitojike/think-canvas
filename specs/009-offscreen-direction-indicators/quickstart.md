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

source実装とfocused検証は完了。最終headのfull CI/canonical review/merge/closureは未完了。物理端末の横断dogfoodingは親81へ残す。

## 実装中の検証

- analyzeは10FR・4SC・9scenario・有限設計を13tasksへ対応付け、Constitution5原則に違反なし、coverage100%、CRITICAL/HIGH0。custom navigation-uxはreviewer-owned8項目を未査読のまま維持し、POの進行指示に沿って実装した。
- 純粋geometry15件を追加。初回unit119件中1件は安全な端のFloat丸めによりtouch領域が微小に外へ出る失敗。projectionとtouch anchorをsafe rectへclampして、全unit119件・lint・debug/androidTest buildが成功した。
- convergeでFR-006/007の入力寿命の直接検証不足2件をT014/T015へappendし、move preview/native CANCELとpending acknowledgement/removed targetのsame-turn stale action回帰を実装した。
- このbuild時点ではfocused emulator・全CI・canonical reviewは未実行。成功と扱わない。
- 固定head `c8a9948a432703f32681711a93233082624e18cb`の検索focusedは標準launcher run `20261003T223614Z-1745275f267244568647bfb89d700693`で成功。実検索/current result、native pan、48dp edge表示、実タップ→同じ結果へfocus/消滅、Board/Room/history/save0を照合。新しいowned XML1/0/0/0、sourceUnchanged true、介入0。証跡hashはPRの検証コメントへ記録する。
- 下位モデルの有限read-only点検で、パレット展開中も明示抑止する条件をT016へappendし、guardとBackで閉じた後の復帰回帰を追加した。新しい対象familyや保存pathは作らない。
- head `f2271b4e321872b3f40908ea7be792b222e32a20`の検索/選択統合・2表示・衝突・semantics順はrun `20261003T223937Z-b12902be034942b1bdae5c93f5cee90e`で1/0/0/0。same ID→search1個、別ID→2個、traversalIndex0/1とaction label、selection ownership・Board/Room/history/save0を照合。XML SHA256 `2E54BD50B048AB2D136B703A51E4FB0A3B3105F172C9CBD93779FD19C18862D8`、sourceUnchanged/ownershipPassed true、介入0。
- 同headのmove preview/native CANCELはrun `20261003T224301Z-8e3f644412d54055bbf11cd60fded71f`で1/0/0/0。90 frameのedge auto-pan中にmarker0、CANCELでowner失効後に1個復帰、navigation後消滅、Board/Room/history/save0を照合。owned/sourceUnchanged true、介入0。XML hashはPRの検証コメントへ記録する。
- T016実装後のconvergeは10FR・4SC・9scenario・有限設計・Constitution5原則と全16tasksを照合し、新しいbuildable残差0。tasks.mdはbyte-identical（SHA256 `8458ACE97640DEF6A3E2DDD8563A73ADC21AB979B683689DEA3C6F1633D4F8DE`）で空phaseを作らなかった。T012/T013の最終検証/deliveryは未完了として保持する。
