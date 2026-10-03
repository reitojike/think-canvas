# ExitのUI契約

| 操作 | 結果 / consume |
| --- | --- |
| IME表示Back | IMEだけ閉じ入力保持 |
| editor Back、変更なし | 無保存close、そのBackを消費 |
| editor Back、変更あり | 同sessionの確認、そのBackを消費 |
| 確認Continue/Back/outside | 確認だけclose、入力保持、forwardなし |
| 確認Discard | live guard/session一致後に未確定変更だけ破棄 |
| text outside | Spec 006のfinalize-only |
| region outside | 既存canvas admission維持、自動renameなし |
| spatial/lasso完了 | tool解除、selection保持、通常tapなし |
| ink stroke | stroke確定、mode保持 |
| tool Back/明示exit | preview/pointer continuation取消、古いUPでcommitなし |
| menu/search/tools/selection Back | 有限順で一段階だけ終了 |
| 通常Back | 既存board navigation |

Running/Failed/pending ackでは破棄・重複commit・自動retry・board移動なし。標準attachment dialog/外部share/guideは既存authority保持。
