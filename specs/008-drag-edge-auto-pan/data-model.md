# 一時状態と不変条件

## Move session

UI-local。一つのgestureのidentity、generation、開始時IDs、DOWNのworld anchor、latest screen pointer、active/cancelledを持つ。pointer/frameはUI threadで更新し、saved instance/Roomへ保存しない。

状態: 未成立→move成立→active（中央停止/edge進行）→release確定、または取消。終了は同期にownerを失効させ、frameと古いUPを拒否する。取消後に同ownerを復元しない。

## Edge profileとframe

profileはband dp・最大速度dp/sec・曲線を持つ。bandをcanvas幅/高さにclampし中央停止帯を保つ。pointer proximity/速度/dtはfinite。dtの大きな飛びはclampする。frameはviewport/previewだけを変更する。

## World delta

Viewport.screenToWorld(latestPointer) − downWorldAnchor。pointer/frame/releaseで共有し、camera分を別加算しない。全IDへ同じ差分を渡し、包含と接続規則は既存translatedSelectionへ委ねる。

## 保存と履歴

BoardSnapshot、BoardState、BoardSessionViewModel、Room schemaは既存のまま。releaseは一回のmove commit/Undo/save、cancelは0回。viewport movementは内容変更・content Undoではない。
