# データモデル: Canvas Foundation

## Board

| 項目 | 内容 |
|---|---|
| id | 固定の最初のボード ID。初回のみ作成する。 |
| name | 表示名。初期値は「無題のボード」。 |
| updatedAt | 最後に確定済み要素が変わった時刻。 |

Board は多数の TextElement を持つ。複数 Board の UI は対象外。

## TextElement

| 項目 | 内容 |
|---|---|
| id | 一意の識別子。 |
| boardId | 所属する Board。 |
| text | 空白のみを許さない確定済み文字列。改行は保持。 |
| kind | `BODY` または `TITLE`。 |
| color | `INK` または `VERMILION`。 |
| x, y | 世界座標。viewport から独立。 |

作成・編集・移動ごとに保存する。削除は今回の範囲外。

## セッション状態

- Viewport: scale、panX、panY。永続化しない。
- Selection: 選択された要素 ID。永続化しない。
- Draft: 入力中の文字、種別、色、位置。確定まで保存しない。
- History: 作成・編集・移動の変更前後。80 件を上限とし、再起動時に空にする。

## 不変条件

- パン・ズーム・選択は TextElement の `x, y` と Room の行を変えない。
- Undo/Redo は対象操作の前後状態を復元し、復元結果を保存する。
- 新規 Draft が空白のみなら TextElement を挿入しない。
