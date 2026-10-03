# Interaction contract

| 入力 | 観測される結果 | 内容/選択/保存/履歴 |
| --- | --- | --- |
| current matchまたは選択groupが完全offscreen | safe edgeに最大2表示 | 不変 |
| 一部可視/接触 | その表示0 | 不変 |
| 同じ単一target | search1表示へ統合 | 不変 |
| tap / semantics onClick | current search focusまたはselection fit +既存animation | 不変 |
| 表示外pan/pinch | 既存gesture、derived marker更新 | 不変 |
| editor/modal/tool/preview/save/IME | marker/hit抑止 | 既存authorityに委ねる |
| 消滅/移動/対象変更 | 古いhit無効、現行targetだけ | 不変 |

48dp、安定key/traversal order、対象種別/名前または個数と移動action。小画面で空きがない場合はsearch優先で省略する。
