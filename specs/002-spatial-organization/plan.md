# 実装計画: Spatial Organization

**Branch**: `002-spatial-organization` | **日付**: 2026-09-26 | **仕様**: [spec.md](spec.md)

## 概要

既存の単一ボードへ四角・丸・囲み・矢印を追加し、複数選択と余白挿入を扱う。位置と接続は世界座標で保持し、Room schema v2 へ追加する。図形と矢印の幾何計算を UI から分け、gesture 中の preview は正常な確定時だけ保存する。

## 技術コンテキスト

- **言語と環境**: Android/Kotlin、Jetpack Compose、JVM 17、現在の `compileSdk 36`。プラグインとライブラリの版数は root/app の Gradle 設定を基準にする。
- **主な依存**: 既存の Compose と Room 3 を継続する。schema migration の検証に `room3-testing` を追加する。新しい描画ライブラリは導入しない。
- **保存**: Room 3 の `boards`・`text_elements` を維持し、v2 で `spatial_elements`・`arrow_elements` を追加する。確定済み snapshot を単一 transaction で保存する。
- **検証**: JUnit で包含、移動、矢印接続、余白、Undo/Redo、v1→v2 migration と再オープンを検証する。GitHub CI では lint、単体テスト、デバッグビルド、公開情報境界を実行する。
- **対象**: Android API 26 以上の単一ボード。文字要素と既存のパン・ズーム・編集を保持する。
- **性能目標**: 100 要素を含むボードで、gesture 中に Room 書き込みを行わず、パン・ズームと移動 preview が連続して追従する。大規模ボードの上限は別途実機で評価する。
- **制約**: オフライン、端末内保存、80 操作のセッション内履歴。PRD と HTML モックは公開リポジトリへ追加しない。

## Constitution 照合

| 原則 | 判定と対応 |
|---|---|
| PRD の authority | §4〜9、§14、§16〜17 を仕様へ反映した。HTML モックは対象の見た目と状態遷移の参考に限定する。非公開ファイルは追加しない。 |
| Standard-first | ボタン・メニュー・文字入力・semantics は Compose の標準手段を使う。要素起点パン、長押し移動、余白、図形ツールの優先順位が重なるため、キャンバスの gesture 調停だけ単一の `pointerInput` を使う。48dp 以上の新しい操作つまみと代替操作を設ける。 |
| 空間配置 | 世界座標だけを保存する。囲みの所属は論理中心から導き、明示された移動・余白以外で位置を変えない。吸着と自動整列はしない。 |
| ローカル優先 | Room の migration と transaction で既存文字と新要素を保持する。基本操作にネットワーク・アカウント・AI を使わない。 |
| 仕様先行 | 本仕様の clarify、plan、checklist、tasks、analyze の後に実装する。 |

設計後もすべての原則を満たす。独自の pointer input は空間操作の複合判定に限り、支援技術向けの actions と視覚フィードバックを併用する。

## 設計

### 幾何と要素の責務

- `SpatialElement` は四角・丸・囲みの位置・サイズ・色/名前を持つ。`ArrowElement` の各端点は自由点か接続先 ID と相対アンカーを持つ。接続先の表示位置はその時点の形状から求める。
- `SpatialGeometry` は中心判定、入れ子/重なりの移動対象、線近傍 hit-test、点の囲み判定、矢印外周交点、曲がり、余白の対象と伸長を扱う。表示倍率は保存される計算へ混ぜず、画面上の hit-test 幅と外周余白の換算時だけ使う。
- 囲み移動と余白挿入の対象集合は gesture 開始時の snapshot で決め、ID で重複除去する。接続先だけを動かした場合、矢印の自由端は変えない。複数選択で矢印自体が含まれる場合は自由端も同じ差分で動かす。
- `BoardState` は文字・図形・矢印の確定済み snapshot と 80 件の Undo/Redo を持つ。複数要素の変更を一つの履歴項目として適用し、失敗した操作や表示だけの変更は記録しない。

### 保存と移行

- v1 schema を保持し、Room 3 の自動 migration で v2 の二表を追加する。v1 の既存行を削除・再作成しない。
- `CanvasStore` の読込と保存を三種類の要素に拡張する。テキスト、図形、矢印の置換とボードの更新時刻を一つの transaction で扱い、ボード名を既定値で上書きしない。
- 接続先は文字表と図形表の両方を参照する。DB の単一外部キーで表せないため、削除と保存前に参照を検証する。対象削除時は接続矢印を同じ履歴・transaction で削除する。
- `room3-testing` で v1 の既存ボード名と文字を入れた DB を v2 へ移し、行と座標の保持、新表の空状態、schema 検証を行う。v2 の全種を保存・再オープンするテストも行う。

### 画面と gesture

- `CanvasScreen` の入力を `tap`、`pan`、`zoom`、`move`、`resize`、`arrow-end`、`arrow-bend`、`lasso`、`gap`、`create` の状態に分ける。down 時の判定順は [操作契約](contracts/interaction.md) に従う。子 UI が消費した event を尊重し、引き受けた操作だけを消費する。
- 通常 drag は #2 のパンを維持する。要素長押し後の drag は移動、動かず release したらメニュー。空白長押し後の drag は余白。図形・矢印・囲み選択ツールでは一筆の drag をそれぞれの作成操作へ渡す。2 指入力や cancel 時は preview を捨てる。
- 図形・矢印は Canvas 描画と個別の透明な semantics 対象を組み合わせる。選択枠、操作つまみ、ツール、案内、余白帯は HTML モックの色と配置を基準にする。新しい操作つまみとメニューのボタンは 48dp 以上のタッチ領域を確保する。
- `LocalHapticFeedback` を使い、長押し成立、囲みへの移動、矢印接続、余白方向の確定を短く知らせる。振動なしでも色・枠・案内で状態が分かる。アクセシビリティ actions で選択、移動、サイズ変更、接続解除、向き反転、削除と余白挿入の代替経路を用意する。

## 構成

```text
specs/002-spatial-organization/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/interaction.md
├── checklists/
└── tasks.md

app/src/main/java/com/reitojike/thinkcanvas/
├── canvas/
│   ├── BoardState.kt          # 確定状態と履歴
│   ├── SpatialElement.kt      # 図形と矢印の型
│   ├── SpatialGeometry.kt     # 世界座標の幾何と余白
│   ├── CanvasScreen.kt        # gesture の調停と画面接続
│   ├── SpatialElements.kt    # 図形・矢印・選択表示と semantics
│   └── CanvasControls.kt     # ツール、案内、操作メニュー
└── data/
    ├── CanvasDatabase.kt     # schema v2 と DAO
    └── CanvasStore.kt        # 一貫した snapshot の読込・保存

app/src/test/java/com/reitojike/thinkcanvas/
├── canvas/SpatialGeometryTest.kt
├── canvas/BoardStateTest.kt
├── data/CanvasDatabaseTest.kt
└── data/CanvasMigrationTest.kt
```

既存の単一 `app` module を維持する。幾何計算と描画・gesture を分けるのは、保存される配置の不変条件を UI なしで検証するため。新しい基盤や汎用 framework は作らない。
