# UI contract

| 状態・操作 | 結果 | primary操作名 |
| --- | --- | --- |
| 通常text tap / primary action | 同じ要素を編集 | 編集 |
| 複数選択・未選択text tap | 集合へ追加、編集なし | 選択に追加 |
| 複数選択・選択済text tap | 集合から解除、編集なし | 選択から外す |
| menuの明示選択 | 対象を単独選択、editorなし | 選択 |
| 検索結果への移動 | viewport移動のみ | 既存の検索操作 |
| editor A外側のB tap | Aの確定/終了のみ | 既存終了契約 |
| その後の独立したB tap | 通常/複数選択の分岐に従う | 上記 |

非表示・保存guard・active editor等の所有者を迂回しない。既存の明示編集/取消、長押しドラッグ、lasso、blank、inkのcontractは参照specを維持する。
