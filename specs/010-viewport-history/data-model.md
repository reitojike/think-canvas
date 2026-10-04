# データモデル

## ViewportFocus / ViewportHistory
`ViewportFocus(centerX: Float, centerY: Float, scale: Float)`はfinite中心と0.15..3倍率。
`ViewportHistory(capacity: Int = 80)`はcameraの`viewportState`、initialized、size/density、back/forward、最後の検索groupを持つ。

- `resize(width, height, density = 1f)`: 正のfinite測定だけ受理。初期化後は旧中心を新sizeへ写す。entryなし。
- `initialize(viewport)`: 測定後の初回fit、entryなし。
- `focus(): ViewportFocus?`: 実cameraから取得。未初期化/無効ならnull。
- `record(origin: ViewportFocus?, group: Any? = null): Boolean`: 現cameraと異なるoriginをbackへ追加。同じnon-null groupはまとめ、view forwardだけclear。上限超過で古いentryを落とす。
- `back()/forward(): Viewport?`: 実現在focusを対向stackへ移しdestinationを返す。UIがanimationする。自己記録なし。
- `canBack/canForward`: Compose観測可能。

Session所有、`viewportHistoryFor(boardId, initial)`で取得。camera Stateは既存writer専用、内容/保存参照なし。

## 編集変更表示
`affectedHistoryIds(before, after): Set<String>`で全family差分と変更接続先のarrowを求める。
`historyDisplayBounds(ids, beforeGeometry, afterGeometry)`は存在するafter bounds、消失IDはbefore boundsを使う。
UI要求はbefore/expectedAfter snapshotとbefore geometryを持ち、一致しないsnapshotで棄却、一度消費。複数対象は一回fit。
接続先変更から得たarrow候補は `affectedHistoryDisplayIds` で実測before/after arrowRenderGeometryを比較する。モデル自体も表示も変わらないarrowを変更範囲から除く。要求はbefore/after snapshotを保持し、同じrepresentation条件で照合する。

## 不変条件
内容履歴/Redo/座標/query/selection/保存をViewportHistoryから変更できない。save state/ack authorityは既存owner。DB列/serialization追加なし。
