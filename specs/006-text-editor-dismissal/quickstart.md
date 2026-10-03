# 検証手順

JDK 25、Android SDK API 37 と emulator を用意する。

```powershell
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/run-windows-gmd.ps1 -ExpectedTestCount 87
pwsh -File scripts/check-public-boundary.ps1
git diff --check
```

Windows の全 instrumentation は repository-owned launcher で検証する。単一ケースを絞るときは `-Test com.thinkcanvas.canvas.TextEditorDismissalTest#emptyDraftOutsideTapClosesWithoutCreatingAndNextTapStartsDraft` を指定する。CI の required GMD は現行 workflow の Pixel9 API 37 に従う。

focused test で新規 exact empty discard / non-empty Done commit、既存 edit の Done commit、explicit Done/Cancel、toolbar と field 内、次の blank tap、旧 chrome、非タップ、recreation/lifecycle/IME、running/failed retry の内容・件数・focus/IME を照合する。保存先は seed した test board。通常 CI の全 GMD と既存保存テストも成功が必要。

実機では新規/既存の外側確定・終了と「やめる」「完了」、TalkBack による toolbar 操作、文字倍率・IME・表示倍率変更後の入力欄内操作を照合する。

補正後の count は source census から再取得し、fresh XML と全 Class#method identities を照合する。failure/error/skipped、missing/extra/duplicate はすべて0が必要。local blocker／CI failure は raw XML/log を #74 family と分類し、blind retry・timeout延長・#74 correction を行わない。物理端末の項目は known unverified として PR に残し、この補正を止める gate には追加しない。
