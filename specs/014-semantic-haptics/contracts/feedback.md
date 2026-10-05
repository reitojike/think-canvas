# Canvas feedback contract

| 操作境界 | Feedback |
| --- | --- |
| 要素/空白long-press成立 | LongPress 1回 |
| pan/selected grip/move handle/long-press→move/gap threshold | なし |
| shape/region/arrow作成成功（接続を含む） | Confirm 1回 |
| native arrow endpoint変更成功 | Confirm 1回 |
| 無変更/作成失敗/取消 | 成功feedbackなし |
| ink stroke終了、region出入り、resize、lasso | なし |
| accessibility action | 現行の無振動を維持 |

既存preview、案内、成功/失敗判定、保存と履歴authorityを維持する。
