# データモデル: Spatial Organization

**保存形式の更新**: #3 実装時は二表の v1 から四表の v2 へ migration しました。Issue #26 では四表を新しい schema v1 とし、DB ファイルを `thinkcanvas.db` に変更します。旧内部版の DB は読み込みません。

## Board と TextElement

`boards` と `text_elements` を現行 schema v2 に含める。文字の中心判定には、表示倍率・端末文字サイズから独立した論理境界を使う。ボードの名前と更新時刻を空間要素の保存で失わない。

### TextElement の canonical model logical bounds

`TextElement.x/y` は world-space の左上配置 / draw anchor であり、中心ではない。model contract version 1 では `text`, `kind`, `x`, `y` から `MODEL_LOGICAL_BOUNDS` を純粋に導出し、その中心を region membership、gap scope/side などの model-space 判定に使う。`RENDERED_BOUNDS` は表示専用 authority であり、この導出や model membership に使わない。

| Kind | 1 code point の幅 | 行高 | 行あたりの wrap 上限 | 最大行幅 |
|---|---:|---:|---:|---:|
| `BODY` | 10 world units | 20 world units | 16 cells | 160 world units |
| `TITLE` | 11 world units | 22 world units | 15 cells | 165 world units |

導出規則:

1. CRLF と CR を LF に正規化し、LF で分割する。明示された空行と末尾の空行を保持する。
2. 改行以外の Unicode code point を各 1 cell と数える。UTF-16 code unit、grapheme、glyph 幅は使わない。
3. 各明示行を kind 固有の上限で greedy に折り返す。空行も論理行を 1 行占める。
4. padding は 0。幅は最大 wrapped row の cell 数と 1 の大きい方に cell 幅を掛け、高さは論理行数と 1 の大きい方に行高を掛ける。
5. `MODEL_LOGICAL_BOUNDS = [x, y, x + logicalWidth, y + logicalHeight]`、`MODEL_LOGICAL_CENTER = center(MODEL_LOGICAL_BOUNDS)` とする。

この v1 の `logicalWidth`, `logicalHeight`, bounds、center、version はいずれも persisted field ではない。既存 Room v2 row を含むすべての行が同じ `text/kind/x/y` から導出できるため、migration、backfill、Room version 更新、schema 更新は行わない。create は同じ導出を使い、text/kind edit は `x/y` を保ったまま bounds/center を再導出してよい。color edit は論理 geometry を変えない。move は `x/y` と導出 bounds/center を同一 world-space delta で移動する。Undo/Redo、duplicate、save/reload は既存の model facts を保持し、同じ geometry を再導出する。

model contract version 1 は per-row の version field ではなく固定された repository contract である。現在の行に version が保存されないため、将来 derivation を変えて既存の membership semantics を変える場合は、別途 product/data-semantics と必要な versioning/migration を実装前に決定する。

## SpatialElement

四角、丸、破線囲みを一つの形で扱う。

| 項目 | 内容 |
|---|---|
| id | ボード内で一意な ID。文字と矢印の ID とも重複しない。 |
| boardId | 所属ボード。#3 では最初のボード。 |
| kind | `RECTANGLE`、`ELLIPSE`、`REGION`。 |
| x, y | 世界座標での左上。 |
| width, height | 世界座標での幅と高さ。正の有限値で、操作時の最小値は試作で 40×30。 |
| color | 四角・丸の `INK` または `VERMILION`。囲みには使わない。 |
| name | 囲みの任意の名前。四角・丸には使わない。 |

囲みの中身は保存しない。要素の論理的な中心が囲みの内部にあるかで導く。入れ子の移動では開始時の集合を固定し、同じ ID を一度だけ動かす。重なる囲みの余白操作では、開始点を含むうち面積が最小の囲みを対象にする。

## ArrowElement

| 項目 | 内容 |
|---|---|
| id, boardId | 一意な ID と所属ボード。 |
| from, to | 向きを持つ始点と終点。それぞれ `Free` または `Attached`。 |
| bend | 直線ならなし。曲線なら両端を結ぶ線の中点から垂直方向の世界座標上の距離。 |

`Free` は世界座標の `(x, y)` を持つ。`Attached` は接続先 ID と、その要素の論理境界内の相対位置 `(u, v)` を持つ。相対位置は 0〜1 の有限値で、現在の形状に追従する。表示端は接続先の外周と相手端の方向から算出し、表示用の余白は保存しない。始点・終点を交換するときは `bend` の符号も反転し、曲線の見た目を保つ。

接続先は文字、四角、丸、囲みのいずれか。対象が削除されたら、その対象に接続する矢印を同じ確定操作で削除する。接続先の移動では `Attached` の表示端が動き、`Free` の座標は維持する。

## BoardSnapshot と操作履歴

`BoardSnapshot` は文字・図形/囲み・矢印の確定済み集合を持つ。選択集合、gesture preview、viewport は含めない。一回の確定操作について変更前後を履歴に記録し、80 件まで保持する。複数要素の移動、余白挿入、削除は一つの履歴項目で戻す。保存は snapshot 全体を一つの transaction で反映する。

## Room schema v1

`boards`・`text_elements`・`spatial_elements`・`arrow_elements` の四表を `com.thinkcanvas.data.CanvasDatabase` の schema v1 として export する。矢印の接続先が複数の表を指すため、アプリ側で ID の存在と削除時の整合を検証する。`thinkcanvas.db` で新規開始し、旧内部版の DB からは移行しない。

## 不変条件

- パン・ズーム・選択・gesture preview は保存された要素を変更しない。
- 囲みの移動対象は開始時の中心位置から決め、入れ子や重なりで重複させない。
- 囲みのサイズ変更で内側の要素の世界座標を変えない。
- 接続先の移動・サイズ変更で自由端の世界座標を変えない。
- Undo/Redo と保存は、変更対象が複数でも一つの確定状態として扱う。
