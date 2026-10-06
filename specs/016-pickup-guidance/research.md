# 調査と判断

## 長押し

Decision: 選択済みpickup-firstで、成立案内、release/menuとdrag/move/gapを維持。
Rationale: 移動準備と結果を明示し、精密配置の契約を守る。
Alternatives: context-firstは既存契約を変える。振動だけでは次の操作が分からない。
根拠: [Android tap/press](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press)。menu時点の限定例外はplanに記録。

## 寿命

Decision: 通常guidanceとは別の一時pickup channel。held中保持し、slop admission/終了/取消/Back/guard失効で消す。
Rationale: 通常guidanceは1800msで消え、region/作成結果にも使う。
Alternatives: 通知期限の一律延長は結果通知も変えるので採らない。
BackはmovePreviewで段階判定するため、invalidatePointerContinuationではcueだけ消す。

## 画像とaccessibility

Decision: moving IDsに限りtintと太いsolid枠。Bitmap/keys/selection/resize handlesは維持。
Rationale: 他familyはmoving表示があり、画像だけ未選択pickupで変化がない。
Alternatives: persistent selection変更はrelease/add契約を壊す。
pickup容器だけpolite live region、既存custom actionsを維持。[Android semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)に従いフレーム変化を読み上げない。
