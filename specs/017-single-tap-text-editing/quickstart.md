# 検証手順

## ローカル

既存JDK/SDKで以下を実行する。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.thinkcanvas.canvas.TextPrimaryActionTest
.\gradlew.bat :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
pwsh -File scripts/check-public-boundary.ps1
git diff --check
```

Android runtimeの回帰は既存の通常PR smokeを使い、現在のmanifest/receiptとfresh XMLを照合する。既存のWindows GMD手順が環境制約で完走できない場合、制約とCIで確認した範囲を分けて記録し、新たな診断基盤を作らない。

## 実機確認

1. 未選択・単独選択の本文/見出しを1回tapし、同じ内容を編集できること。取消・完了と再起動後の保存内容も確認する。
2. Aのeditor外側のBをtapし、Aの確定/終了だけでBを編集しないこと。次の独立B tapで編集すること。
3. 長押しメニューの選択からgrip・削除・共有へ到達し、長押しdragとlassoも使えること。
4. 複数選択中はtext tapで追加/解除してeditorを開かず、1件以下になれば次のtapが通常編集となること。
5. 検索移動だけではeditorを開かず、通常時の対象tapで編集すること。近/中/遠とhidden textを確認する。
6. 支援技術のprimary名が編集/追加/解除に一致し、明示選択とmoveへ到達できること。
7. blank tap/double tap、stylus/ink、保存guard、外側確定取消、Undo/Redoの既存契約を確認する。

実機・TalkBack等の未確認範囲は結果へ明記する。Draft PRで止め、mergeしない。
