# 一時表示と状態遷移

pickupHintはnullableな案内文字列。永続化しない。nullは準備案内なし。通常guidanceと独立し、UIでpickupを優先する。

| 対象 | 成立案内 | release | slop超過 |
| --- | --- | --- | --- |
| 通常要素 | ドラッグで移動、離すとメニュー | menu | move |
| 未選択＋既存選択 | ドラッグで移動、離すと選択に追加 | selection追加 | 対象move |
| 選択集合 | ドラッグでまとめて移動、離すとメニュー | menu | 集合move |
| blank | ドラッグして余白を作る | no-op | gap |

既存movePreviewがmoving IDsを供給する。画像のtemporary枠をIDsから導き、selection/Roomは変更しない。

全familyでmoving IDsは表示だけを導く。textもframe/shadow/dimmingの強調とactual selectedIdsを分ける。stateDescription、select/edit label、move gripはactual selectionだけから導き、pickupだけで選択済みと伝えない。
成立→heldは保持。slop超過/UP/cancel/Back/guard失効/pinch/stylus切替でnull。pointerInput keyに入れない。Backは失効前のpickup有無を捕捉し、cueを消す。previewをpriority判定まで保持し、previewを持たないblankも取消stageで消費する。
