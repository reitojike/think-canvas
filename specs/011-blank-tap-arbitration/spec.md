# 空白 canvas の single / double tap 確定

分類: `CONFIRMED_SINGLE_TAP_BEFORE_TEXT_ENTRY`。
対象 baseline: `047b0a1060e31fad184248bb24d4bfaa6c646d20`。
今回の明示的な製品判断を Spec 001 / 006 の空白 tap 入口へ適用する作業成果物。
merge 前に現行仕様を暗黙に置き換えない。

## 受け入れ条件

- 空白の first UP は位置・時刻だけを保留し、Draft、focus、IME、選択解除、保存、編集履歴を開始しない。
- platform double-tap window 終了後の confirmed single で、選択があれば解除し、なければ検索等の従来 guard を確認して first tap の world 座標に新規 Draft を1回だけ作る。focus / IME は既存 editor authority に従う。
- platform timing / scaledDoubleTapSlop 内の second DOWN は保留 single を破棄し、既存 doubleTapZoom を使う。数pxのずれを許容し、選択・内容・Room・save・Undo/Redo は不変、viewport history は1 navigation。
- slop / minimum interval 外の second tap は double としない。first single を失わず、first の選択解除後に second single を確定できる。first が editor を開始した場合は editor authority が以後の入力を所有し、duplicate Draft は作らない。
- editor/session、tool/ink、save block、STOP/dispose、gesture generation、board 切替で保留の authority が失効したら遅延操作を実行しない。
- 成立済み editor の #71 outside dismissal、#73 Back、既存編集・選択・long press・pan/pinch・auto-pan・spatial・ink・保存・検索・chrome は維持する。

## 検証

native MotionEvent のずれた double tap を修正前に RED とし、confirmed single の前後、first world 座標、selection、slop 外の連続 single、失効を回帰で確認する。
Process #36 に従って focused → lint/unit/debug/androidTest build → final-head full Pixel9/API37 GMD / source-XML exact census → current-head canonical review / unresolved thread 0 を確認する。新しい material finding は HOLD/checkpoint。

## 非対象

zoom 倍率・animation、editor UX、generic gesture coordinator、long press timing、pan/pinch、#79/#80。
