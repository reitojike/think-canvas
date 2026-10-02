# UI 契約

| 状態と target | 結果 |
| --- | --- |
| Editing、field 内 | 標準テキスト入力の操作 |
| Editing、editor toolbar | 種類・色・やめる・完了の既存操作 |
| Editing、新規かつ最新 text が exact empty、外側の短い単一 tap | 無保存で破棄し終了、gesture を消費 |
| Editing、それ以外、外側の短い単一 tap | 既存 commitDraft() で validation・確定・保存・ack を処理、gesture を消費 |
| Editing、外側の drag/long press/multitouch/cancel | 入力を保持、他 action へ転送しない |
| Running/Failed/pending ack、外側 | 入力と同じ要求を保持、重複 commit・自動 retry なし |
| Activity 再生成・一時 focus loss | draft と保存 continuation を保持 |
| Idle、次の blank tap | 既存の選択解除/新規入力規則 |

「完了」は explicit commit、「やめる」は無保存の explicit cancel。外側終了は空の新規入力の破棄または既存 Done の確定。空白文字と既存編集の空文字は current Done の validation に従う。DOWN/UP 間の更新後も同じ sessionId の最新内容を用いる。system back とボード移動の入口は維持する。