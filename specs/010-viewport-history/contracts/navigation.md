# Navigation contract

| family | 境界 |
| --- | --- |
| 検索入力auto-focus | 連続入力group、完了/次操作の到達点 |
| 検索前次/囲みfit/cycle/double tap/indicator | animation一回 |
| manual pan/pinch | 初manual更新→正常完了一回。速いpanの継続はSpec015に従い停止時に一回 |
| 編集Undo/Redo表示 | 差分表示animation一回 |
| view back/forward | stack移動、自己記録なし |
| 初期fit/frame/move auto-pan/cancel/resize/dispose | 追加なし |

「前の視点へ戻る」「次の視点へ進む」独立Button、48dp、方向別disabled、倍率付近に条件表示。編集Undo/Redoとsystem Back維持。guardでhiddenになったboundsはnative入力を奪わない。
render boundsとcanvas intersection＋semantic可視性で変更位置を確認する。部分可視なら維持、複数の一つでも画面外なら全変更範囲へfit。消失対象はbefore位置、無関係な要素は含めない。
検証はsnapshot equality、canRedo、save数、world中心/scale、native一gesture、再生成、stale action/hidden chromeを直接観測する。

Spec015のpan＋flingはcompleted panの継続であり、自然停止/割り込み停止で同じoriginを一度記録する。resize/dispose自体の履歴ではない。低速panとpinch、cancel、frame、move auto-panの規則は維持する。
