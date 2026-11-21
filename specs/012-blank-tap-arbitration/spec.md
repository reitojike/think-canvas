# 空白 canvas の single / double tap 確定

分類: `CONFIRMED_SINGLE_TAP_BEFORE_TEXT_ENTRY`。
**状態**: 現行仕様（Living Spec）。検証結果・未確認事項は関連 Issue / PR を参照。
Spec 001 / 006 の空白 tap 入口に適用する現行の操作契約。


## 受け入れ条件

- 空白の first UP は位置・時刻だけを保留し、Draft、focus、IME、選択解除、保存、編集履歴を開始しない。
- platform double-tap window 終了後の confirmed single で、選択があれば解除し、なければ検索等の従来 guard を確認して first tap の world 座標に新規 Draft を1回だけ作る。focus / IME は既存 editor authority に従う。
- platform timing / scaledDoubleTapSlop 内の second DOWN は保留 single を破棄し、既存 doubleTapZoom を使う。数pxのずれを許容し、選択・内容・Room・save・Undo/Redo は不変、viewport history は1 navigation。
- slop / minimum interval 外の second tap は double としない。first single を失わず、first の選択解除後に second single を確定できる。first が editor を開始した場合は editor authority が以後の入力を所有し、duplicate Draft は作らない。
- editor/session、tool/ink、save block、STOP/dispose、gesture generation、board 切替で保留の authority が失効したら遅延操作を実行しない。
- 成立済み editor の #71 outside dismissal、#73 Back、既存編集・選択・long press・pan/pinch・auto-pan・spatial・ink・保存・検索・chrome は維持する。

## 保留中の入力と取消の境界

- 空白の最初の指離し（UP）では、UP の時刻、最初の指接触（DOWN）の画面座標、その座標を UP 時点の表示変換で求めた世界座標を保留する。単独タップの確定時には、現在の表示変換で再計算せず、保留した世界座標を使う。
- 次の指接触（DOWN）が画面上の操作部・編集中の入力・ペン操作などの既存の判定を通って空白タップの判定に届き、最初の保留がまだ有効で、適格なダブルタップにならない場合は、先に最初の単独タップを確定する。次の入力は、その後の編集・選択状態で判断する。確定時には同じ保留要求か、同じ編集・選択・ボード等の状態が続いているかを再確認する。
- ポインターの DOWN を経由しない既存操作でも、視点アニメーション、保存要求、検索入力・終了、ツール展開操作、選択内容の共有の各入口では、操作を受理した時点で保留を取り消す。各操作の可否や、編集・保存・履歴・アクセシビリティの既存契約は変更しない。

## 検証

native MotionEvent のずれた double tap を修正前に RED とし、confirmed single の前後、first world 座標、selection、slop 外の連続 single、失効を回帰で確認する。
Process #36 に従って focused → lint/unit/debug/androidTest build → final-head full Pixel9/API37 GMD / source-XML exact census → current-head canonical review / unresolved thread 0 を確認する。新しい material finding は HOLD/checkpoint。

## 非対象

zoom 倍率・animation、editor UX、generic gesture coordinator、long press timing、pan/pinch、#79/#80 の新規実装。
