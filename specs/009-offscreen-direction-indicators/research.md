# Research: 画面外対象の方向表示

## 対象と視点authority

Decision: search currentMatchとselectionのrendered geometryだけを再利用する。検索は既存focusMatch、selectionは既存fit/animation。
Rationale: BoardSnapshot/Undo/selection ownershipに新たな変更経路を作らない。selected/searchは現行SemanticProjectionのkeep対象。
Alternatives: named region全般・独立target・minimapはPO承認scope外。model boundsだけではtext/ink/arrowの実表示にずれが出る。

## touchと読み上げ

Decision: 48dp circleと標準clickable Button role、意味によるdescription、search→selection traversalIndex。
Rationale: [Android標準のtouch/semantics](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)と[traversal order](https://developer.android.com/develop/ui/compose/accessibility/traversal)に従う。位置による既定sortだけでは対象方向の変化で順序が入れ替わる。
Alternatives: 小さい視覚だけのarrowでは自動touch拡張がchromeと重なるため、登録境界と同じ48dpを明示する。独自gesture recognizerは作らない。

## 配置と寿命

Decision: 実chromeから安全な上/下端を導出し、同edgeの有限candidateを距離順に評価する。最新derived rectをpointer admissionに使う。
Rationale: static magic toolbar寸法やonGloballyPositionedの古いboundsだけに依存すると、large fontや条件付き表示でhitが残る。marker自身をobstacleに入れると配置が振動する。
Alternatives: 全画面transparent overlayのpointer handlerはcanvas gestureを奪う。全要素探索は不要。

未決の技術調査なし。新依存・schema・CI変更なし。
