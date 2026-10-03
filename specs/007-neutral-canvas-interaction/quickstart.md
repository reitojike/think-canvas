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
