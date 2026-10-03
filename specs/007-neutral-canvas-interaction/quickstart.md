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
- convergeはFR/SC/USの24項目、設計5項目、Constitution5原則を照合し、追加のbuildable gapは0。検証・deliveryのT009/T011は、完了を推定せずpendingを維持する。
- final candidateのfocused、full CI XML identity、canonical review、base/thread、実際のmergeとIssue AC判定は[PR #86](https://github.com/reitojike/think-canvas/pull/86)と[Issue #73](https://github.com/reitojike/think-canvas/issues/73)の最新証跡で確認する。古いheadの成功は最終gateに使わない。
