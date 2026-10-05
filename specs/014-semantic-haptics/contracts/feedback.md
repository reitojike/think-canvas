# Canvas feedback contract

| 操作境界 | Feedback |
| --- | --- |
| 要素/空白long-press成立 | LongPress 1回 |
| pan/selected grip/move handle/long-press→move/gap threshold | なし |
| 正常native UPでのshape/region/arrow作成成功（接続を含む） | Confirm 1回 |
| 正常native UPでのarrow endpoint変更成功 | Confirm 1回 |
| 無変更/作成失敗/取消 | 成功feedbackなし |
| ink stroke終了、region出入り、resize、lasso | なし |
| accessibility action | 現行の無振動を維持 |

既存preview、案内、成功/失敗判定、保存と履歴authorityを維持する。

ACTION_CANCEL/FLAG_CANCELEDでは成功feedbackを通知しない。既存create等の取消確定処理の差分は[#115](https://github.com/reitojike/think-canvas/issues/115)に分離し、本featureでは保存・操作結果を変えない。
