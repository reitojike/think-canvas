# 設計判断

## 取り消しと forwarding

- **Decision**: 入力欄と編集 toolbar 以外の canvas tap は dismiss-only。
- **Rationale**: 通常 chrome は draft 表示時に非表示。既存 canvas admission は draft 中の操作を拒否するため、その境界を維持して二重動作を防げる。
- **Alternatives**: dismiss+target action は1操作で選択・編集・変更を起こし、#71 が避ける accidental action を増やす。focus loss cancel は lifecycle と IME の変化を区別できない。

## 再生成と保存 continuation

- **Decision**: ボードごとの TextEditorSession を既存 ViewModel.Session に保持し、draft、pending acknowledgement、pendingNewElementId を同じ寿命にする。
- **Rationale**: 現在の remember は再生成で消える。draft のみ復元すると確定済み新規要素を再作成する危険がある。保存中は同じ acknowledgement を再 await する。
- **Alternatives**: rememberSaveable は Deferred と非同期 Page 復元の調停が別途必要。BoardState に載せる案は transient editor state と snapshot/history の責務が混ざる。

## Android 標準との関係

Compose は親が Initial pass で子より先に gesture を検査し、consumption を通知できる。外側 gesture に限定し、入力欄と toolbar は標準 API の処理に渡す。既存 canvas handler は consumed down も受けるため、draft の外側の admission guard を double tap より先に置く。入力欄内の最初の空白位置の既存 double tap は維持する。

一次資料: [Compose gesture と event propagation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)、[ViewModel と構成変更](https://developer.android.com/topic/libraries/architecture/viewmodel)。
