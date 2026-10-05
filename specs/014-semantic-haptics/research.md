# Read-only censusと標準APIの照合

開始main: `5190c2a588bce5685b74d87e108b9df31a54a684`。全`performHapticFeedback`はCanvasScreenに6か所。他の画面の標準部品内feedbackは対象外。

| 元の行/操作 | 意味/重複 | 決定 |
| --- | --- | --- |
| 1429 長押し成立 | menu/move/gap準備。LongPressが適切 | KEEP、一度 |
| 1460 ink commit | 新group成立でLongPress、touch/stylus共通 | REMOVE、通常筆記に不要 |
| 1509 arrow作成/attach | 成功でLongPress、shape/regionではなし | Confirmをcreated共通成功へ移す。一度 |
| 1561 region enter/leave | move commit時。長押し振動に追加 | REMOVE、案内維持 |
| 1584 arrow endpoint変更 | 変更成功でLongPress | Confirmへ変更 |
| 1663 gap threshold | 長押し成立直後に同種振動が重複 | REMOVE、preview維持 |

selected grip、move handleのdrag admission、resize、pan/pinch、lassoに明示feedbackなし。accessibility actionsのmove/resize/端点解除/接続editor/削除/余白もnative pointer経路を通らず明示feedbackなし。追加しない。stylusはink優先で長押しtimerを通らない。

## 標準と判断

Decision: 長押しはLongPress、作成/端点変更成功はConfirm。[Compose HapticFeedbackType](https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType)のsemanticを採用する。Confirm/GestureThresholdActivateは1.8導入で現行UI1.12.1で利用可能。dependency更新なし。

Alternative: gapでGestureThresholdActivate。drag閾値のsemanticは合うが、既にLongPressとpreviewがあるため重複削減を優先し無振動。regionでConfirmを使う案も案内と長押しfeedbackがあるため採用しない。

Decision: ink/regionは無振動。[Haptics principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)の意味に合う定数・過剰feedback回避からの製品上の推論。強さや違和感は実機評価が必要。

[AndroidX Compat](https://developer.android.com/reference/androidx/core/view/HapticFeedbackConstantsCompat)と[Android constants](https://developer.android.com/reference/android/view/HapticFeedbackConstants)を照合。標準ViewCompat経路はConfirmをAPI30未満でVIRTUAL_KEY、thresholdをAPI34未満でCONTEXT_CLICKにfallback。独自分岐なし。

[Android推奨実装](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)に従い端末設定を尊重し、権限・pattern・amplitude制御を追加しない。Spec Kit research agentによる公式資料と現行cacheのread-only確認を含む。
