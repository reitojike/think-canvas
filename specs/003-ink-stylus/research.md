# 調査: Ink & Stylus

## 採用する API

- [Jetpack Ink のリリース](https://developer.android.com/jetpack/androidx/releases/ink): 2026-09-27 時点の最新 stable は 1.0.0。alpha 版は採用しない。
- [Ink のモジュール](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-modules): `ink-strokes` の入力/Stroke、`ink-brush` の Brush、`ink-rendering` の CanvasStrokeRenderer、`ink-storage` の入力列の符号化を利用する。
- [状態と保存](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-state-preservation): Brush の種類と入力列を別に保存し、再構築する。world→screen の変換を表示時に適用する。
- [CanvasStrokeRenderer の API](https://developer.android.com/reference/kotlin/androidx/ink/rendering/android/canvas/CanvasStrokeRenderer): `draw` に渡す行列は stroke→screen の描画品質の判断に使われ、canvas に適用されない。表示座標への変換は canvas に一度だけ適用する。
- [線の入力](https://developer.android.com/reference/kotlin/androidx/ink/strokes/InProgressStroke): 入力時の連続点は単調な時刻と同じ tool type が必要。重複・時刻逆転の端末入力を除外する。
- Ink 1.0.0 の入力列符号化はホスト JVM の CI で native library を読み込めなかった。Room schema の移行は JVM で、Ink 入力列の再読込は Android エミュレーターの計装テストで検証する。

## 保留した選択肢

- `InProgressStrokes` を画面全体へ重ねる方式: 既存のテキスト/図形操作、1本指描画、2本指 nav、スタイラス優先を同時に調停する必要がある。既存の gesture 処理を一箇所に維持し、Ink のデータ/描画 API を直接使う方が判定を明確にできる。
- SVG/Compose Path のみで保存する方式: Ink 採用方針と入力列の再利用に合わない。
- 既存要素テーブルの全再作成: 既存データを守るため避ける。
