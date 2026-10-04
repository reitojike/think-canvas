# 検証手順

## Issue93 merge後のPR92再gate（2026-10-04）

native pan oracleの独立follow-upは[PR95](https://github.com/reitojike/think-canvas/pull/95)のmerge `77f7dcde436f2f8c31fe7e4f99aa9ec5b4311297`と[Issue93の最新6AC closure](https://github.com/reitojike/think-canvas/issues/93#issuecomment-5977070810)で完了した。このmerge済みmainをPR92へ統合する。IME productionと3つの追加native回帰はhead02be1a3から変更しない。旧CI143/1/0/0を成功証跡へ転用せず、統合後の新headでsource143の両CI/fresh XML/canonical/base/threadを再確認する。T017/T020〜T022とIssue91 closureはそのdeliveryが確定するまでpendingとする。

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
T009/T011は[post-merge closure](https://github.com/reitojike/think-canvas/issues/73#issuecomment-5970533783)で完了。merge d56c098のtreeは検証head c4b5df7と一致し、両CI37131698617成功・fresh110/0/0/0・canonical clean・thread0・最新17 ACを確認済み。物理端末の横断UXは親81で継続する。

## Issue91 IME window-owner復帰

PR90のfull CIで既存Continue/outside復帰のtimeoutを確認し、editor/IMEの独立responsibilityとして[family checkpoint](https://github.com/reitojike/think-canvas/pull/90#issuecomment-5975452167)から[Issue91](https://github.com/reitojike/think-canvas/issues/91)へ分離した。text/search/region focus効果はmain cb8aa72と同一で、PO authorityは変更しない。

main productionに新規native回帰のみ追加した標準focused run20261004T014050Z-0aaa7ca777744acf99d557fcee444cfeは、別Dialogがwindow focusを持つ間に背後editorがFocused=trueとなり1/1/0/0。owned/source unchanged、介入0、XML SHA-256 `EB4B4528253EF9924A776A8361B18A99182F978009A1D5EBB437ABBD61F688CE`。既存trigger内のone-shot window待機とframe適用をT016で追加し、Backで隠したIMEをwindow往復だけで再表示しない回帰も維持する。dialog hide/Backのfixtureはdefault Activity rootを避け明示dialog rootを使う。

更新headのfocused、基本検証、全体141 identities（Spec006/007/008/009を保持）とcanonical review、merge/最新7 ACはIssue91のPR/closureで確定する。未検証の成功を推定せず、deliveryのT017はpendingのまま記録する。

最初の修正8cda579はnative window-ownerと既存Continue/確認Back/outsideの標準focusedが各1/0/0/0、owned/sourceUnchanged=true、介入0、lint/unit119/0/0/0/build/公開境界も成功。一方[full CI37169491939](https://github.com/reitojike/think-canvas/actions/runs/37169491939)は141/1/0/0（missing/extra/duplicate0）、outside resumeだけIME readiness timeout。XML SHA256 `69108FC1D91C75101DDBAF65B70DA876975F0621645BC0242645EBEC3ABE4D0A`。[接続readiness checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5975731094)で同じ三focus効果にnative active input connection/acceptingTextのone-shot待機をT018として追加した。showを反復せず、window帰還だけのIME非表示維持を再検証する。更新headの全体成功・canonical・merge・ACは推定しない。

T018の既存継続focusedは1/0/0/0（run20261004T022211Z-ef6fa9abca6b483eb35932bb1bba54b7、XML `4517CB2B4A0076465462AA43B348E6F8126A2884DC89327735D0419F386AE7AB`）、window/Back後のIME保持focusedも1/0/0/0（run20261004T022542Z-3e032de9177f4de89bb59d4c2ae2ea1f、XML `5D121C842946FA01260A013A5DA4F2A1F1A9066C484549C000FF01D09344E12D`）。新規pending終了のnative回帰は再composition前にFocused=trueを再現して1/1/0/0（run20261004T023012Z-a6384d4cec974f7cb97e9abbf5b5e2af、XML `1421EF5CE3B6D1DC19D93E5697F941356FCA35CF92D675AD731C961910203F53`）。全run owned/sourceUnchanged=true、介入0。[第二bounded checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5975822659)からT019のlive session/search/確認照合を追加した。source censusは142 identitiesとなる。更新headのfocused/full CI/canonical/merge/ACは別途確定する。

T019の修正後は同じnative終了回帰に同位置session置換を含め1/0/0/0（run20261004T023728Z-1257621c5f634d418bc4781bba816d91、XML `FBBD4D502A76AD810644A72FCF31DB720B4F74EC18C7165FE03B0CC52898C23E`、owned/sourceUnchanged=true、介入0）。再composition前の旧field非focus、終了後のIME非表示、新composition後の新session入力接続/IME表示、Board/Room/Undo不変を確認。lint/unit119/0/0/0/debug/androidTest build、公開境界198 files、diff/schema不変も成功。buildable gapなし、delivery T017は最終head全体142件とCI/canonical/merge/最新ACの確定までpending。

[CI37171896715](https://github.com/reitojike/think-canvas/actions/runs/37171896715)は142/5/0/0、missing/extra/duplicate0、XML `9711141230879DF7A7C13C310E84857974793D9F74EBB55527AD19C226F989CE`。Neutralのwindow/継続2件はIME非表示、保存Running/Failed再生成2件はidle待機timeout、EdgeAutoPanの次pan1件は座標不一致だった。Edgeの原因は未確定で、source/testsを変更せず、IME補正で直ったとは扱わない。失敗CIをblind rerunせず、入力ownerの有限checkpointからT020〜T022を追加した。

第三roundのwindow/Back非表示focusedは1/0/0/0（XML `746C0678DF9659D12EE4565571E17B2EF423847AF42D39CCC5068C703330F364`）。Running再生成はidle待機を通過したがreadonly editorのFocused=falseで1/1/0/0（XML `B65C544359A28645E5B536DE14EEA15952860B48523BE29143F5839BFD0A842D`）。第四checkpointでfocusとIMEのadmissionを分離し、既存focus条件を維持した。

第四roundのRunning/Failed再生成は各1/0/0/0（run032335/032648、XML `C56D593316F86DFC2F7DF1B72567E14CADAA7AAC85E1662CBE8394461C3A3979` / `7ED0C6903D2EA03AC5FC4A57811B09EDD10BA4F4A8B6307057006CFCD5502930`）。既存outside-DOWN→確認→Continueも1/0/0/0（run033020、XML `380F2036FD3FBC4DB0C95CD51DB1474509434AA52608CDA21AB2AD90BA1F81F1`）。focus/draft/ack/保存要求/Room/Undo保持と保存成功後closeを検証した。

第五roundのnative回帰は、確認受理後・再composition前の検索Focused=trueを修正前1/1/0/0で再現（run20261004T034241Z-c04838e624894df09165eaea4bf2a251、XML `11D7A4DF454E4F84F96E856800327B3B208CCBEE76B74C269BE3063E38C3E3AB`）。修正後は確認中の非focus、確認Backから編集復帰、別window所有中のIME優先Back取消とwindow帰還後の非表示、draft/Board/Room/Undo不変を1/0/0/0で確認（run20261004T034628Z-d08f44f37d694ba8a19e2e399b52d39b、XML `045731DC47205FE871A4E76FE9B384B9FB68D96EBE66313B48F0B4FAD1410AE1`）。全focusedはfresh exact owned XML、ownershipPassed/sourceUnchanged=true、manualInterventions=0。source censusは143 identities。最終headの両CI/full census/canonical/base/thread/merge/最新ACはPR92/Issue91のdelivery証跡で確定する。

第五roundの同じproduction sourceで、既存pending終了・同位置session置換回帰も1/0/0/0（run20261004T035055Z-a83db39241c0418d9739085371459a13、XML `33191A073CB38B13DE593D602DB5B063101D7E7A38E77E39A1A84487F549C53D`）。owned/sourceUnchanged=true、介入0。再composition前の旧field非focus、終了後非表示、新sessionへの正常接続を維持する。read-only再convergeでは三owner/全hide箇所/live save・確認・session guardを有限照合しbuildable残差0。T017/T020〜T022の最終deliveryは未達のまま保持する。

第五roundの最終候補はlint、unit119/0/0/0、debug/androidTest APK build、公開境界198候補、diff check、schema不変を確認。native source censusは143 identities、duplicate0。base main cb8aa72にbehind0。これをfinal candidateとしてfreezeし、同じheadの両CIとfresh full XML/canonical reviewを最終gateへ使う。CI成功やmerge/AC完了はここでは推定しない。
