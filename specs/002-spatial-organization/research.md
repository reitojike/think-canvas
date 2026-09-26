# 調査と判断: Spatial Organization

## 既存データの保持

**判断**: Room 3 の schema v1 を残し、v2 に図形・囲み共通の表と矢印の表を追加する。既存の `boards` と `text_elements` は作り直さず、単純な表追加を `@AutoMigration(from = 1, to = 2)` で移行する。v1 と v2 の schema を保管し、v1 の実データを入れた migration test を行う。

**理由**: 文字とボードのデータ損失を避け、#2 の保存契約をそのまま維持できる。複数種の要素は一つの transaction で保存する。矢印の接続先は複数の表を参照できるため、参照の整合と接続先削除時の矢印削除をアプリ側でも確認する。

**比較**: 全要素を新しい統合表へ移し替える案は、既存文字の移行が大きくなる。破壊的 migration は採用しない。

**資料**: [Room migration](https://developer.android.com/training/data-storage/room/migrating-db-versions)、[Room 3 MigrationTestHelper](https://developer.android.com/reference/kotlin/androidx/room3/testing/MigrationTestHelper)

## 要素の位置と接続

**判断**: 図形と囲みは世界座標の矩形を持つ。包含は表示倍率と文字サイズから独立した論理境界の中心で計算し、親子 ID を保存しない。矢印の端点は自由点または接続先 ID と要素内の相対位置を持つ。描画上の外周までの補正は保存せず、現在の要素形状から計算する。

**理由**: 囲みのサイズ・位置変更後も中身を再評価でき、表示方法によって保存データを変えない。接続先の移動には端点だけを追従させ、自由端は保持できる。

**比較**: 囲みの親子 ID を保存すると、位置変更と所属情報が食い違う。描画済みの端点座標を保存すると、接続先のサイズ変更後に矢印が離れる。

## 操作履歴と保存

**判断**: 確定した一連の操作について、変更前後のボード状態を一つの履歴項目にする。ドラッグ中は表示用 preview だけを更新し、正常に指を離した時点で履歴へ追加し保存する。履歴は既存の 80 件上限を継承する。

**理由**: 囲み移動、複数選択、余白挿入、接続先削除は複数要素に及ぶ。操作単位の Undo/Redo と一貫した保存を優先する。

**比較**: 要素ごとの履歴では一度の操作を何度も Undo する必要がある。gesture 中の逐次保存は中断時の部分状態を残す。

## Gesture とアクセシビリティ

**判断**: 既存のキャンバス全体の `pointerInput` を明示的な gesture 状態に整理する。down 時に UI、操作つまみ、線近傍の要素、空白の順に判定する。通常の drag はパン、要素の長押し後 drag は移動、空白の長押し後 drag は余白挿入とする。ツール中の drag はツール操作へ渡し、2 指入力への遷移時は preview を破棄してパン・ズームにする。新しい操作つまみは 48dp 以上のタッチ領域と個別の semantics を持つ。

**理由**: #2 の要素起点パンと長押し移動を保ちながら、#3 の多数の操作の優先順位を一箇所で決められる。標準の `clickable` と semantics はボタン・メニューに使い、複合 gesture の調停だけを低水準入力に限定する。

**比較**: `transformable` と要素ごとの `draggable` を混在させると、要素起点パンと長押し移動の優先順位が分散する。全 pointer event の無条件消費は子 UI と競合する。

**資料**: [Compose gesture](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)、[multi-touch](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)、[semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)、[タッチ領域](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

## 試作値と表示

**判断**: PRD の寸法、長押し時間、当たり判定距離は初期値として扱う。視覚部品は HTML モックの色、線、ツール配置、選択枠、端点、斜線帯に合わせる。端末差は実機で確認し、調整した値と理由を spec または plan に記録する。

**理由**: PRD は数値を試作値と明記している。固定値を製品の不変条件にせず、操作の意味と表示の一貫性を守る。
