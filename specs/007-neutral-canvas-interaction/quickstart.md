# 検証手順

JDK25 / SDK37を用意しrootで実行する。

```powershell
./gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/check-public-boundary.ps1
git diff --check
./scripts/run-windows-gmd.ps1 -Test 'com.thinkcanvas.canvas.NeutralInteractionTest#changedNewTextContinueDialogBackAndOutsideKeepDraft'
```

focusedは1回。host blockerなら分類し、CIの独立Pixel9/API37 full XMLを照合してblind rerunしない。

1. 未入力new/未変更existingはIMEを閉じBackでeditorのみ終了。
2. changed text/nameは確認→継続/Back/outsideで保持→破棄でboard/履歴/保存を維持。空白/kind/color/元へ戻した入力も照合。
3. 確認/region編集中の再生成で自動finalizeなし。
4. Running/Failed/pending ackのBackで要求保持、retry/成功時closeを確認。
5. 全spatial作成、lasso、連続ink、tool再生成、preview途中Back、次の独立操作を確認。
6. full source censusとfresh CI XMLのidentity/count/failure/error/skippedを照合。Spec 006全21 identities維持。
7. 物理端末のcompact/large font/TalkBack/IME製品差/片手操作は#81 dogfoodingで記録。emulatorで実機項目を達成としない。

## 実行記録と収束の証跡

- ローカルlint、unit **85/0/0/0**、debug/androidTest APK build、公開境界、schema差分なし、diff checkは成功。
- source censusは107 identities（新規20、Spec 006の21を維持）。初回[CI 37123356008](https://github.com/reitojike/think-canvas/actions/runs/37123356008)は基本検証成功、GMD **107/12/0/0**、missing/extra/duplicate=0。新しいBack契約に対応するテスト、IME終了同期、region操作node、stroke間の保存待ちを補正。
- Windows focusedの初回と補正#1は失敗を観測し、blind rerunなし。[family checkpoint](https://github.com/reitojike/think-canvas/pull/86#issuecomment-5969326255)で補正#2の有限scopeを固定した。
- 最初のconvergeはFR/SC/USの24項目、設計5項目、Constitution5原則を照合した。その後[canonical P1](https://github.com/reitojike/think-canvas/pull/86#discussion_r4173360329)からT012を追加し、recomposition前の古いUPを同期世代guardで拒否する不足を補正した。検証・deliveryのT009/T011は、最終headの完了を推定せずpendingを維持する。
- BackとUPを同じUI turnでdispatchする回帰は、修正前`5d75867`でstrokeの意図しない確定を再現（Windows focused **1/1/0/0**）。修正後`cd67124`は **1/0/0/0**、ownershipPassed/sourceUnchanged=true。修正後run `20261003T133837Z-d85fb528f36f40259688287af67eb071`、XML SHA256 `504D7AC162772C7F74872EA6B32FB63931E5C79E03F95DA55C077EE5BD428864`。古いheadのfull CI成功・reviewは最終gateへ流用しない。
- final candidateのfocused、full CI XML identity、canonical review、base/thread、実際のmergeとIssue AC判定は[PR #86](https://github.com/reitojike/think-canvas/pull/86)と[Issue #73](https://github.com/reitojike/think-canvas/issues/73)の最新証跡で確認する。古いheadの成功は最終gateに使わない。
- [CI 37127124106](https://github.com/reitojike/think-canvas/actions/runs/37127124106)は **107/1/0/0**、missing/extra/duplicate=0。確認再生成後のBackでIMEだけが閉じる不足を[focus family checkpoint](https://github.com/reitojike/think-canvas/pull/86#issuecomment-5969869397)で分類しT013を追加した。text/regionの自動focusを確認中は抑制し、確認解除時だけ復帰させる。
- 確認再生成後の背後textがFocused=trueになる回帰は修正前`172efd3`で **1/1/0/0**。修正後`8b90fa6`はIMEを強制hideせず一回のBackで確認close・入力保持に成功し **1/0/0/0**。Windows run `20261003T140323Z-b97ecdd76ef6400c94f48babf05f3d51`、ownershipPassed/sourceUnchanged=true、manualInterventions=0、XML SHA256 `62DA0CFA868D08A66F590F5AE1D3B9E500FA8A07ADC5884D6B4DFF690C97AB2C`。regionの同じ回帰とfinal-head full CI・review・deliveryはPRの最新証跡で確認する。
- [CI 37128507063](https://github.com/reitojike/think-canvas/actions/runs/37128507063)は **107/1/0/0**、missing/extra/duplicate=0。text/region確認再生成・同期古いUPは成功、Continue直後のIME hide fixtureがnative接続/showに先行して失敗。[readiness family checkpoint](https://github.com/reitojike/think-canvas/pull/86#issuecomment-5970067630)でT014を追加し、entry/resumeのeditor/window focus・active input connection・IME表示を待ってから次のhideを行う。production/schema/保存/geometry変更なし。
- [final reviewのP1](https://github.com/reitojike/think-canvas/pull/86#discussion_r4173581974)はpreview前のDOWN→Back→MOVE/UPで入力継続が残る不足。[Back continuation checkpoint](https://github.com/reitojike/think-canvas/pull/86#issuecomment-5970229053)からT015を追加し、全受理Backの同期世代失効をpreview cleanupと分離、Initial passのoutside text loopにもDOWN/event照合を追加した。標準確認dialogのdismiss/Continueにも同じ失効を接続。priority判定前にpreviewを消さず、save拒否は維持する。
- 修正前`2311c46`のpreview前selected textは編集の意図しない再オープンを実測（1/1/0/0、XML SHA256 `4F38165A136400E63E2BFB07079E4A0B28A0579F526C8398C10F04FFCE0BFB94`）。修正後`fccc12a`のtext tap/grip/shape MOVE/resizeは1/0/0/0、run `20261003T145013Z-c5a74e4237734b5589d036117e92cf1c`、XML `1A0A19DC81DB3B48B215C546A3F891BAC9BDAC52BA202B8581DB062DB230D3E5`。同headのoutside DOWN→Back確認保持は1/0/0/0、run `20261003T145410Z-7e52448e4a41456a9b4ef6f39601c98e`、XML `18B7453263830DD3E9350971CBA562065B359E0DDFADCB5CE2597FE174F08CFF`。menu/search/expandedのpending tap取消も1/0/0/0、run `20261003T145705Z-cd80406a505a466d973bb26405f432c1`、XML `5A7667796A12C0FABC7C1538D498806D741B0EE8E5C978A6AE8CE5E10963F534`。全runでownershipPassed/sourceUnchanged=true、manualInterventions=0。Board/Room/Undo不変と次の通常操作を確認した。
- 現在のsource censusは110 identities（新規23、Spec006の21を維持）。旧headの107 greenとreviewは使わず、final candidateの両CIとfresh110 XML、最後の依頼より新しいcanonical review、base/thread、merge/最新ACはPR/Issueへ記録する。T009/T011はfinal deliveryの証跡で判定する。