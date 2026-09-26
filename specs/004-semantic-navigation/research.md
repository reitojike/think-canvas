# 技術調査: Semantic Navigation

## 文字サイズと段階判定

- **判断**: 本文 14.sp の見かけの大きさを端末の現在の `Density` で dp 相当に変換し、viewport 倍率を掛けて9・5の境界と比べる。
- **理由**: Android 14 以降の文字サイズ設定には非線形の拡大があり、`14 × fontScale` は実際の見え方を再現しない。物理 pixel をそのまま境界値に使うと高密度端末だけ近景へ偏る。
- **検討した代替**: 固定の倍率境界は文字サイズ設定に追従しない。物理 pixel 比較は画面密度に依存する。
- **根拠**: [Android 14 の非線形文字サイズ](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)、[Compose FontScaling](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/FontScaling)、[Density](https://developer.android.com/reference/kotlin/androidx/compose/ui/unit/Density)

## 表示判定と接続要素

- **判断**: 保存用 `BoardSnapshot` は維持し、表示可否と簡略化の状態だけを別に計算する。図形、矢印、手書き、文字の描画と hit test は同じ判定を使う。
- **理由**: 表示用に要素を削ると、隠れた接続先の座標が失われる可能性がある。表示と hit test の判定が別々だと、見えない要素を選べてしまう。
- **検討した代替**: 表示用に snapshot の list を破壊的にフィルターする方法は、矢印の端点計算や保存との差を増やす。

## 視点のアニメーション

- **判断**: pan と scale を共通の進行度で約340ms補間し、利用者の直接 gesture で進行中のアニメーションを中断する。Compose のアニメーション時間設定を使う。
- **理由**: 3値を別々に動かすと対象からずれる。標準の Compose animation は端末のアニメーション時間設定に従う。
- **検討した代替**: 独自の時間ループは reduced motion の尊重と中断処理を難しくする。
- **根拠**: [Animatable](https://developer.android.com/reference/kotlin/androidx/compose/animation/core/Animatable)、[MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)

## 検索とアクセシビリティ

- **判断**: 現在の board をローカルで走査し、検索欄は表示時にフォーカス、単一行、IME の検索アクションを設定する。前後・閉じる操作にラベルを付け、件数を読み上げられるようにする。
- **理由**: 現在の規模では外部索引や保存 schema が不要。隠した要素は semantics と hit test からも除外する。
- **検討した代替**: Room に検索索引を追加すると現行の短い文字・囲み名に対して migration と同期の負担が増える。
- **根拠**: [Compose の入力欄](https://developer.android.com/develop/ui/compose/text/user-input)、[フォーカス](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior)、[semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
