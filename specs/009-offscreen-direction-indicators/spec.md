# Feature Specification: 画面外対象の方向インジケーター

**Feature Branch**: `codex/issue-76-offscreen-indicators`

**Created**: 2026-10-04

**Status**: Draft

**Input**: [Issue #76](https://github.com/reitojike/think-canvas/issues/76)。POは現在の検索結果1件と選択対象1まとまりの2対象、条件付き最大2個を承認した。

作業中のSpec009はmergeまでPRDや現行Spec001〜008を置き換えない。

## Clarifications

### Session 2026-10-04

- Q: v1の対象はどれか？ → A: 現在の検索結果1件＋選択対象。複数選択は1まとまり、必要時のみ最大2個。named region全般と新しいexplicit navigation targetは含めない。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 現在の検索結果へ戻る (Priority: P1)

検索後にキャンバスを動かして結果を見失っても、画面端の方向表示から現在の結果へ戻れる。

**Why this priority**: 明示的に探している対象へ、一操作で戻れる。

**Independent Test**: 検索結果を四辺と角の画面外へpanし、方向表示を押して結果へ戻る。検索順とボード内容は変わらない。

**Acceptance Scenarios**:

1. **Given** 現在の結果が画面内、**When** 完全に画面外へpan、**Then** 対象方向の端に1個現れる。一部でも画面と重なる間は表示しない。
2. **Given** 検索結果の方向表示、**When** タップまたはアクセシビリティ操作、**Then** 現在の結果へ視点が移り、画面内に入ると表示が消える。
3. **Given** 表示中、**When** query・現在の結果・内容変更または検索終了、**Then** 表示と操作は現在の結果へ更新し、消えた対象の操作領域を残さない。

### User Story 2 - 選択したまとまりへ戻る (Priority: P2)

選択を維持して周辺を見る間に対象を見失っても、そのまとまりへ戻れる。

**Why this priority**: 位置関係を保持したまま編集対象へ戻れる。

**Independent Test**: text/shape/region/ink/arrowの単一または複数選択を画面外へpanし、1個の表示でまとまりへ戻る。選択IDs、保存内容、履歴は同じ。

**Acceptance Scenarios**:

1. **Given** 複数選択、**When** 選択範囲全体が画面外、**Then** 外接範囲の中心方向に1個だけ表示する。
2. **Given** 異なる検索と選択対象が両方画面外、**When** 表示、**Then** 最大2個を重ならず表示し、検索、選択の順で識別できる。同じ単一要素は検索表示1個へ統合する。
3. **Given** 選択の表示、**When** 移動、**Then** 選択範囲が見える視点へ移る。ownership・配置・Undo/Redoを変えない。

### User Story 3 - 通常操作と両立する (Priority: P2)

画面端の補助表示がpan/zoom、作成、編集、移動を妨げない。

**Why this priority**: 補助表示が主要操作や入力内容を壊してはならない。

**Independent Test**: 表示外のpan/pinch、編集開始/終了、Back、移動drag、画面再生成で操作領域の寿命を照合する。

**Acceptance Scenarios**:

1. **Given** 表示中、**When** 表示外でpan/zoom、**Then** 通常操作が継続し、表示位置は現在の視点に追従する。
2. **Given** editor/modal/menu、作成tool、interaction preview、保存blockまたはIME、**When** その状態へ入る、**Then** 表示と操作領域を抑止する。終了後は現在の対象から再計算する。
3. **Given** 表示の消滅または移動、**When** 古い位置を操作、**Then** 古い表示へ移動せず、通常のキャンバス操作を行える。

### Edge Cases

- 部分可視・境界接触、巨大または疎な選択は外接範囲が画面と重なる限り表示しない。v1は部分可視対象への追加navigationを作らない。
- 同方向の2対象でもtouch領域を重ねず既存chromeを覆わない。小画面で安全に置けない場合は検索を優先し、置けない表示を省略する。
- 対象削除、空検索/結果なし、空選択、未計測canvas、無効な境界値には表示しない。
- Semantic Zoomで表現が変わっても現在の検索結果と選択の保持・表示境界の契約を使用する。
- アニメーション中の通常gestureは既存のアニメーション取消規則に従う。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: v1対象は現在の検索結果1件と選択範囲1まとまりのみ、最大2個。同じ単一要素は検索へ統合する。
- **FR-002**: 現在の表示境界を画面へ投影し、画面と交差・接触しない対象だけを、画面中心から対象中心の方向に対応する端へ示す。部分可視には表示しない。
- **FR-003**: 表示は48dp以上のtouch領域を持ち、既存chromeと他表示に重ならず、必要時のみ画面端に現れる。安全な領域不足時は検索を優先して省略する。
- **FR-004**: 検索表示は現在の結果へ、選択表示は現在の選択範囲へ、既存の視点移動を用いて移動する。検索順・query・selection ownershipを変えない。
- **FR-005**: 表示の出現・移動・操作・消滅でボード内容、座標、保存、Undo/Redoを変更しない。
- **FR-006**: pan/zoom、検索/選択/内容変更、終了/再生成で最新の対象・視点へ更新し、古い表示と操作領域を残さない。表示外のtap/pan/pinchを奪わない。
- **FR-007**: editor/modal/menu、IME、作成tool、interaction preview、保存block中は表示と操作を抑止し、成立したmoveとedge auto-panの所有権を奪わない。
- **FR-008**: Semantic Zoomの簡略化と対象保持を維持し、既存の表示境界を用いる。表示のために全要素の別探索をpanの毎frameに追加しない。
- **FR-009**: 「現在の検索結果（番号と対象名）」または「選択対象（種類または個数）」を識別できる読み上げ情報と移動actionを持つ。複数では検索、選択の安定順とし、位置だけで識別させない。
- **FR-010**: 四辺/角、部分可視、重複/衝突、pan/pinch、検索・選択変更、入力抑止、cleanup、保存・履歴不変をfocused regressionで検証する。

### Key Entities

- **Navigation Target**: 検索の現在IDまたは選択IDsと表示境界。保存対象ではない。
- **Direction Indicator**: 対象種類、方向、条件付き位置と移動action。対象と視点から導出し、独立した履歴を持たない。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 四辺・四角の画面外対象へ各1操作で戻れ、画面内・部分可視の表示は0個、総数は常に2個以下。
- **SC-002**: 移動前後で全保存要素・相対配置・選択・Undo/Redo・保存回数の差は0。
- **SC-003**: 消滅・抑止・移動後の古い領域で誤navigationは0回、次の通常pan/pinchが成功する。
- **SC-004**: 全表示を対象の意味で識別し、アクセシビリティの移動actionから同じ対象へ到達できる。

## Assumptions

- 承認した2対象以外のnamed region全般、独立target、minimap、距離ラベル、履歴、auto-layout、AIは対象外。regionが検索/選択対象になる既存操作は対象内。
- 判定はcanvas全体。配置用の安全な端は既存chromeとtouch領域を考慮する。補助表示が一部を覆うだけでは画面外と扱わない。
- 単一textは既存focus、選択まとまりは既存fitの意味で戻る。最小倍率でも巨大な選択を収められない場合は中心を示す。
- #76で機械的検証と現行headのレビューを行う。物理端末・片手操作・large font・TalkBackの横断dogfoodingは親#81の未確認項目として残す。
