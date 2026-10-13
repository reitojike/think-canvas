# ExitのUI契約

| 操作 | 結果 / consume |
| --- | --- |
| IME表示Back | IMEだけ閉じ入力保持 |
| editor Back、変更なし | 無保存close、そのBackを消費 |
| editor Back、変更あり | 同sessionの確認、そのBackを消費 |
| 確認Continue/Back/outside | 確認だけclose、入力保持、forwardなし |
| 確認Discard | live guard/session一致後に未確定変更だけ破棄 |
| text outside | Spec 006のfinalize-only |
| region outside short touch tap | changed nameはDone相当pathで一度commitしてclose、unchangedは無保存close。同じreleaseだけを消費しselection/create/tool/panを抑止 |
| region outside drag/long-press/multi-touch/CANCEL/stylus | commitなし。既存canvas routerへ引き渡す |
| region outside save blocked | tapを二重配送せず、save/ack guardを維持してdraftを開いたままにする |
| spatial/lasso完了 | tool解除、selection保持、通常tapなし |
| ink stroke | stroke確定、mode保持 |
| tool Back/明示exit | preview/pointer continuation取消、古いUPでcommitなし |
| menu/search/tools/selection Back | 有限順で一段階だけ終了 |
| 通常Back | 既存board navigation |

Running/Failed/pending ackでは破棄・重複commit・自動retry・board移動なし。標準attachment dialog/外部share/guideは既存authority保持。

Backによる終了受理時は、preview前のDOWNも同期に失効させる。main/Initial outside textの両pointer loopはDOWNの世代とlive世代を照合し、古いMOVE/UPで選択・編集・内容確定を復活させない。既存priorityを選ぶ前にpreview/選択を消さない。
