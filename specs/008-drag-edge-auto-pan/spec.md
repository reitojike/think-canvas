# 機能仕様: ドラッグ中のedge auto-pan

**Feature Branch**: `codex/issue-78-edge-auto-pan`

**Created**: 2026-10-03

**Status**: 仕様案（#73のmerge後に現行pointer契約を再確認して実装する）

**Input**: Issue #78 / Roadmap #81。既存の要素移動中に画面端でviewportを自動panし、指を離さず遠くへ運べるようにする。

## User Scenarios & Testing

### User Story 1 - 指を離さず要素を運ぶ (Priority: P1)

利用者は既存の長押しドラッグまたは移動ハンドルで要素を運び、そのまま画面端で指を止める。画面が対象方向へ進み、要素は指との位置関係を保って移動する。画面中央へ戻ると自動panだけが止まり、通常の移動を続けられる。

**優先理由**: drop、pan、再選択の繰り返しを減らし、狭い画面から遠くへ運べるようにする。

**独立した検証**: 一つの文字要素を、一回のドラッグで表示領域の幅を超える距離へ運ぶ。途中でpointerを動かさず保持しても画面が継続して動き、中央へ戻ると止まることを確認する。

**Acceptance Scenarios**:

1. **前提** 要素移動のドラッグが成立している、**操作** 指を表示領域の右端付近へ動かして保持、**結果** 右側の空間へ画面が継続panし、対象と指の関係を保つ。
2. **前提** 上下左右の端で移動中、**操作** 端へさらに近づく、**結果** 同方向の速度が単調に増え、設定された上限を超えない。角では両方向へ進む。
3. **前提** 自動pan中、**操作** 指を端の範囲から中央へ戻す、**結果** 次の表示更新までに自動panを止め、通常のドラッグを継続する。
4. **前提** 自動pan中、**操作** 指を離す、**結果** 自動panを止め、最後に見えていた位置へ一度だけ移動を確定する。

### User Story 2 - まとまりを一回の移動として保存する (Priority: P1)

利用者は複数選択や囲みの中身をまとめて運べる。自動panが何回起きても相対配置が変わらず、一回のUndoで内容だけを戻せる。

**優先理由**: 画面の移動によって利用者の空間配置や保存・履歴を壊さない。

**独立した検証**: 文字・図形・inkを複数選択し、囲みを含む移動を自動panと組み合わせる。確定、Undo、Redo、再読み込みの位置と相対差分を確認する。

**Acceptance Scenarios**:

1. **前提** 複数選択、**操作** 一回のedge auto-panドラッグ、**結果** 全対象が同じ世界座標差分で動き、相対配置を保つ。
2. **前提** 要素を含む囲み、**操作** 囲みを自動panで運ぶ、**結果** 開始時の包含・入れ子・接続矢印の既存移動規則を守り、途中で別の要素を巻き込まない。
3. **前提** 一回の移動が確定した、**操作** Undo、Redo、再起動、**結果** 一回の履歴・保存単位を維持し、viewport自体は内容Undoに含めない。

### User Story 3 - 取消と他の操作を安全に続ける (Priority: P2)

利用者はドラッグを取り消したり、2本指のviewport操作へ切り替えたりできる。通常のpan・描画・アクセシビリティの移動操作は既存のまま使える。

**独立した検証**: edge移動中にBack、pointer cancel、2本指への切替、画面再生成を行う。自動panと未確定移動が残らず、次の独立した操作が受理されることを確認する。

**Acceptance Scenarios**:

1. **前提** 未確定のedge移動、**操作** Backまたはpointer cancel、**結果** 内容を確定せず自動panとpreviewを止める。古いpointerのUPで確定しない。
2. **前提** edge移動中、**操作** 2本指へ切替、**結果** 未確定移動とauto-panを取り消し、既存のpinch/panへ移る。
3. **前提** edge移動中、**操作** Activity再生成またはボード画面の終了、**結果** 移動内容を自動確定せず、以前のauto-panが再開・残留しない。
4. **前提** 通常pan、ink、stylus、tool作成、resize、矢印端点、lasso、余白挿入、modal、単純tap、**操作** 端付近へ入力、**結果** moveのauto-panは発動しない。
5. **前提** TalkBack等の既存移動操作、**操作** 移動action、**結果** 既存の移動・選択経路を維持し、pointer固有の新しい操作を必須にしない。

### Edge Cases

- 長押し成立だけでは開始しない。既存の移動drag成立条件を満たしてから受理する。
- 角では縦横の速度を別々に決め、表示領域より外側へ指が出ても速度上限を維持する。
- 小さい表示領域では左右・上下のedge bandを重複させず、中央の停止範囲を保つ。
- 停止・再開、倍率15〜300%、密度の違い、長い表示間隔でも位置を二重加算しない。
- save Running/Failed/pendingの既存操作制限を守る。自動panがretryや別のsave要求を発生させない。
- 取消時は最後のviewportを保ち、内容だけを未移動へ戻す。自動panをviewport履歴や慣性移動へ広げない。

## Requirements

### Functional Requirements

- **FR-001**: v1は既存のelement/multi-selection moveとMOVE handleの2つのdrag familyに限定する。文字、図形/囲み、ink、既存選択に含まれる矢印は既存の内容移動規則で扱う。resize・矢印端点・lasso・余白挿入には適用しない。
- **FR-002**: 移動drag成立後、指が表示領域のedge bandに入ると、その方向の空間へviewportを継続panする。指が静止していても保持中は継続する。
- **FR-003**: 端へ近いほど速度を上げる。band外は0、速度は有限上限を持ち、角では縦横両方向へ進む。band幅・速度・曲線の具体値はbounded UX prototypeで比較してplanへ記録し、未検証の数値を固定した製品仕様にしない。
- **FR-004**: 対象は指との世界座標の関係を保って移動する。viewport差分とpointer差分を二重加算せず、previewと最終確定を同じ位置計算に従わせる。
- **FR-005**: 複数選択の相対配置、開始時の囲み包含、接続・自由矢印、inkの既存移動規則を維持する。
- **FR-006**: pointer releaseで最後の位置を一度だけ既存move commitへ渡し、一回のmoveを一回のUndo/saveとして確定する。viewportは内容・Undo・保存要素へ加えない。
- **FR-007**: band外、release、cancel、Back、2本指handoff、保存制限、画面終了・再生成でauto-panを停止する。取消後の古いUPから移動を確定せず、previewや継続処理を残さない。
- **FR-008**: 通常pan/pinch、ink/stylus、tap、選択だけ、modal/editor、対象外tool/handleには発動しない。既存のpointer優先順とconsume/handoffを維持する。
- **FR-009**: 既存のアクセシビリティ移動を置き換えず、常設chromeや独自gestureを追加しない。
- **FR-010**: 決定的な座標・状態遷移とPixel9相当のbounded UXを検証する。物理端末でのみ判断できる横断的な片手操作・TalkBack・IME製品差は親#81で区別して記録する。

### Key Entities

- **移動session**: 開始時の対象集合、世界座標のdrag anchor、現在の指、成立・取消状態。内容の保存対象にしない。
- **viewport**: 表示倍率とpan。内容の位置計算は既存の世界座標変換に従う。
- **edge band/速度**: 表示領域と指の位置から決まる一時的な移動条件。system barsを含む端末全体ではなくcanvasの表示領域を使う。

## Success Criteria

### Measurable Outcomes

- **SC-001**: Pixel9相当で一つの対象を表示領域の幅または高さを超える距離へ運ぶ際、drop→pan→再選択を0回にし、一回の連続dragで完了する。
- **SC-002**: 全対象familyと15〜300%の検証倍率で、最終配置がpreviewと一致し、multi-selectionの相対配置誤差・二重移動・重複saveを0件にする。
- **SC-003**: 各停止条件後にauto-pan・未確定内容・古いUPによる確定の残留を0件にし、次のtap/pan/selectionを受理する。
- **SC-004**: normal pan/pinch/ink/stylusと既存accessibility moveの回帰を0件にし、対象moveのUndo/Redoを各一回で再現する。

## Assumptions

- Issue #78の既存move拡張だけを扱い、新規要素種別や移動開始gestureを作らない。矢印の端点変更は対象外だが既存の選択移動に含まれる矢印は除外しない。
- 取消時にviewportを巻き戻さないのは既存pointer cancel/2本指handoffの規則を維持するため。内容Undoとviewport historyは別機能とする。
- Issue #73と同じpointer owner・preview終了に触れるため、実装前に#73 merge済みのcurrent code/spec・review証跡を再読する。本仕様案はWIPであり、PRDや現行Spec 001〜006を置き換えない。
- band幅・速度・曲線はplan/prototypeの調整値であり、製品の新しいconcept/settingsにしない。数値の比較で製品判断が必要になった場合はPOへエスカレーションし、dependentな実装を停止する。
