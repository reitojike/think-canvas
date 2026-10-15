# 設計判断

- Decision: 既存の選択集合の2件以上を複数選択として扱う。
  Rationale: 現在のtap/long-press/lassoの状態境界を使い、持続する別modeを増やさない。
  Alternatives: 新しい複数選択modeは承認範囲に不要。
- Decision: primary actionのpure判定をpointerとsemanticsで共用する。
  Rationale: labelと動作のずれ、重なった別テキストの取り違えを防ぐ。
  Alternatives: 2か所で別々の分岐や、支援技術のtargetを座標へ戻す経路は採らない。
- Decision: 明示menu選択と既存custom選択actionを保持する。
  Rationale: 通常tapが編集へ変わっても、移動・共有と複数選択へ到達できる。
- 新しいframework・依存版・保存形式を選ぶ必要はない。既存標準実装を使う。
