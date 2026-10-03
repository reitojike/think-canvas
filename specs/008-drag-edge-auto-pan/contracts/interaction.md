# Interaction contract

| 入力/状態 | admission | 消費/継続/終了 |
| --- | --- | --- |
| 長押し成立だけ/小さいjitter | auto-panなし | 既存選択/menu規則 |
| 成立したmove/MOVE handle | IDs/world anchorを固定 | moveのpointer入力を消費、edgeでframe pan |
| edge内で静止 | 同じactive owner | frameごとviewport/previewのみ更新 |
| 中央へ復帰 | active moveは保持 | auto-pan速度0、通常move継続 |
| UP | live owner/世代/save guard | 最後のpointerを反映、同期停止、一回commit/save |
| CANCEL/Back | owner失効 | preview取消、commitなし、古いUP拒否。Backを同時に一覧へforwardしない |
| 2本指 | move owner失効 | preview取消、既存pinch/panへhandoff、以後move commitなし |
| stylus takeover | move owner失効 | 既存stylus優先へhandoff |
| save block/STOP/disposal/recreation | 継続不可 | frame/previewを取消、再開時owner復元なし |
| pan/ink/create/lasso/gap/resize/arrow end/modal/tap/accessibility | ownerなし | 既存authorityのみ。move auto-panを開始しない |

canvasのscreen-space edge→velocity→viewport.pan→screenToWorldによるworld delta→既存translatedSelection preview→release時BoardState.moveSelection/saveSnapshotだけを使う。
