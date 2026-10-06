# 手動panの継続contract

| 境界 | 挙動 |
| --- | --- |
| 低速pan/停止してrelease | 即停止、正常UPで表示履歴一回 |
| 速い単一指panの正常UP | 同方向の標準decay、停止時までoriginを保持 |
| CANCEL/FLAG_CANCELED、pinch、ink、move/gap、handle | pan flingなし |
| 新DOWN（chrome/editor含む）/Back/別操作 | 旧generationを同期無効化、途中停止位置を履歴に一回 |
| 自然停止 | 一回だけoriginを記録 |
| stale frame | viewportを書き換えない |
| save/editor/tool/source/lifecycle変化 | guardがcamera代入より先に拒否し停止 |

pan＋flingは一つの表示履歴境界。frameはcontent/save/編集Undoへ作用しない。速度閾値と上限はplanのscreen密度基準、減衰は標準に任せる。
