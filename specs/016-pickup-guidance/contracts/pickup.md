# Pickup UI契約

標準timeout後にLongPress一回、temporary pickup表示と[data-model](../data-model.md)の案内。held中は通知期限で消えない。polite live regionはpickupだけ。
通常guidance、選択数、tool案内よりpickupを優先。drag admission/終了/取消/別gestureで消す。region出入り/作成結果の案内を保持。
画像はmoving IDsでtintとsolid枠、通常selection semantics/resize handle/asset lifecycleを維持。
selected grip/handle、ink/stylus、pan/pinchにpickup context案内を付けない。
menu/actions/save/Undoとnative cancellationの製品挙動を変えない。独立Issue #115を取り込まない。

## 有限interaction family

| Surface | 成立と次の操作 | 非影響 |
| --- | --- | --- |
| text/shape/region/arrow/ink/imageの本体 | pickup → release/menu または drag/move | 既存hit、保存、Undo |
| blank | 余白案内 → release/no-op または drag/gap | touchSlop |
| 既存選択＋未選択本体 | pickup → release/選択追加 または drag/対象move | 選択追加時に内容を保存しない |
| 選択集合本体 | 集合pickup → release/menu または drag/集合move | 相対配置 |
| selected move grip / resize / arrow handle | 既存明示操作、context pickup案内なし | handle hit、確定、Undo |
| ink tool / stylus | 既存描画、context pickup案内なし | stroke、複数指競合 |
| pan/pinch/Back | pickup終了と既存owner/generation guard | viewportと段階的Back |
| TalkBack context actions | 既存custom actions、pickup提示だけpolite通知 | gesture以外の操作手段 |

片手操作・accidental move/menu・案内の理解しやすさは対象APKの実機評価で確認する。
