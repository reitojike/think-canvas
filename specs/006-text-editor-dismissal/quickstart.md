# 検証手順

JDK 25、Android SDK API 37 と emulator を用意する。

```powershell
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
pwsh -File scripts/run-windows-gmd.ps1 -ExpectedTestCount 79
pwsh -File scripts/check-public-boundary.ps1
git diff --check
```

Windows の全 instrumentation は repository-owned launcher で検証する。単一ケースを絞るときは `-Test com.thinkcanvas.canvas.TextEditorDismissalTest#emptyDraftOutsideTapClosesWithoutCreatingAndNextTapStartsDraft` を指定する。CI の required GMD は現行 workflow の Pixel9 API 37 に従う。

focused test で新規 empty/non-empty cancel、既存編集 cancel、toolbar と field 内、次の blank tap、旧 chrome、非タップ、recreation/lifecycle/IME、running/failed retry の内容・件数・focus/IME を照合する。保存先は seed した test board。通常 CI の全 GMD と既存保存テストも成功が必要。

実機では新規/既存の外側取り消しと「やめる」「完了」、TalkBack による toolbar 操作、文字倍率・IME・表示倍率変更後の入力欄内操作を照合する。
