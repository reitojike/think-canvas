# データモデル: Semantic Navigation

保存 schema は変更しない。以下は画面上で計算する一時状態である。

## SemanticTier

- `NEAR`、`MID`、`FAR` のいずれか。
- 入力: 本文 14.sp を端末の文字サイズ設定に従って換算した見かけの大きさと viewport 倍率。
- 境界: 9以上は近、5以上9未満は中、5未満は遠。

## SemanticProjection

- 入力: `BoardSnapshot`、`Viewport`、`SemanticTier`、選択 ID、検索一致 ID。
- 出力: 全体の段階、まとまった名前付き囲み ID、非表示 ID、要素別の表示方針。
- 不変条件: 元の snapshot、要素 ID、世界座標、Undo/Redo 履歴を書き換えない。選択または検索一致の ID は非表示にしない。
- 入れ子の囲みは、外側がまとまった場合に内側を隠す。表示される検索一致は例外とする。

## SearchState

- `query`: 一時的な検索語。空なら検索結果なしとして強調を行わない。
- `matches`: 内容または名前が一致する text / 名前付き region の ID を世界座標の上端、左端、ID の順で並べたもの。
- `currentIndex`: 0件なら位置なし、それ以外は `matches` 内の有効な index。
- 変更時: query の変更で index を先頭へ戻す。前後移動は末尾・先頭で循環する。検索終了で一時状態を破棄する。

## ViewportTarget

- `scale`、`panX`、`panY`。各値は既存の `Viewport` と同じ単位。
- ズーム段階の目標、空白ダブルタップ、囲みフィット、検索一致への移動から計算する。
- 制約: scale は既存の 0.15–3 の範囲内。操作は board snapshot を変更しない。
