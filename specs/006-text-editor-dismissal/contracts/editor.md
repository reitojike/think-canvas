# UI 契約

外側 gesture の target と保存状態から、許可される操作を決める。

| 状態と target | 結果 |
| --- | --- |
| Editing、field 内 | 標準テキスト入力の操作 |
| Editing、editor toolbar | 種類・色・やめる・完了の既存操作 |
| Editing、外側の短い単一 tap | cancel のみ、gesture を消費 |
| Editing、外側の drag/long press/multitouch/cancel | 入力を保持、他 action へ転送しない |
| Running/Failed/pending ack、外側 | 入力と保存要求を保持 |
| Activity 再生成・一時 focus loss | draft と保存 continuation を保持 |
| Idle、次の blank tap | 既存の選択解除/新規入力規則 |

「完了」だけが確定を要求する。明示「やめる」は outside cancel と同じ guard、focus/IME cleanup を使う。system back とボード移動の入口は変更しない。
