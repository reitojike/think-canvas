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
