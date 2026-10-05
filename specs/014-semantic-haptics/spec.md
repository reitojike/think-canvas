# Feature Specification: 操作の意味に合わせたハプティクス

**Feature Branch**: `codex/issue-101-semantic-haptics`
**Created**: 2026-10-06
**Status**: Draft
**Input**: [Issue #101](https://github.com/reitojike/think-canvas/issues/101)

## User Scenarios & Testing

### User Story 1 - 長押しの成立を知る (Priority: P1)

利用者は長押し成立を一度の振動と既存の表示から理解し、離すとmenu、動かすとmove/gapへ進む。

**Why this priority**: 成立とdrag開始を同じ意味の振動で二重に知らせない。
**Independent Test**: 要素と空白で長押しrelease/dragを行い、振動の回数と既存の結果を確認する。

**Acceptance Scenarios**:

1. **Given** 要素または空白、**When** 長押しが成立する、**Then** 長押しの振動を一度発生し、既存のpreview/案内を維持する。
2. **Given** 成立した長押し、**When** drag thresholdを越えmove/gapへ進む、**Then** 追加の振動はなく、releaseで従来の一操作を確定する。
3. **Given** 成立した要素長押し、**When** threshold以下でreleaseする、**Then** 従来のmenu/追加選択を維持する。

### User Story 2 - 確定の意味を区別する (Priority: P2)

利用者は図形・囲み・矢印の作成と矢印端点変更の成功だけを成功の振動として受け取る。通常の筆記や囲み通過では繰り返し振動しない。

**Why this priority**: 筆記の頻度が操作確認の振動に埋もれないようにする。
**Independent Test**: 各作成、端点変更、touch/stylus筆記と囲み通過で振動と保存結果を照合する。

**Acceptance Scenarios**:

1. **Given** 作成ツール、**When** 図形・囲み・矢印作成が成功する、**Then** 成功の振動を一度発生する。接続を伴う矢印でも一度だけとする。
2. **Given** 選択した矢印、**When** 端点変更を確定し内容が変わる、**Then** 成功の振動を一度発生する。無変更では発生しない。
3. **Given** touchまたはstylus、**When** 通常strokeを終了する、**Then** 振動しない。保存とUndo/Redoは従来通り。
4. **Given** 要素移動、**When** 囲みに出入りする、**Then** 既存の案内を維持し振動は追加しない。

### Edge Cases

- 作成失敗、drag取消、pinch移行、Backによる取消で成功振動を発生させない。
- haptic無効・非対応端末でも既存の表示・操作・保存が成立する。
- selected move grip、resize、pan/pinch、lasso、accessibility actionには新しい振動を追加しない。
- stylusは既存のink優先を維持し、touchと同じ筆記無振動規則を使う。

## Requirements

### Functional Requirements

- **FR-001**: 長押し成立を一度だけ知らせ、既存の時間・距離判定と表示を維持する。
- **FR-002**: 通常dragと長押し後のdrag threshold成立は無振動とする。
- **FR-003**: 図形・囲み・矢印作成と矢印端点変更が成功した場合、長押しとは別の標準的な成功feedbackを一度だけ使う。
- **FR-004**: 通常stroke終了と囲み出入りは無振動とする。囲みの案内は維持する。
- **FR-005**: Board/Room/save/Undo/Redo/geometry、gesture admissionとaccessibility semanticsを変更しない。
- **FR-006**: focused regressionで振動の種類・回数と操作結果を観測する。実機評価は機種・OS・対象build・強さ・頻度・違和感を記録し、未評価のときIssueを完了にしない。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 要素/空白の長押しrelease/dragはいずれも長押し振動1回、drag追加0回。
- **SC-002**: 作成/端点変更成功は成功振動1回、無変更/取消0回。
- **SC-003**: touch/stylusの通常stroke終了と囲み出入りの追加振動は0回。
- **SC-004**: 変更対象操作の既存保存とUndo/Redo回帰が成功する。

## Assumptions

- Issue #101の方向に従い通常ink/regionの振動は省く。実機評価で調整が必要なら別途根拠を記録する。
- このfeatureのmerge後はSpec 002 interaction contractの振動規則だけを置き換える。menu/move/gapの意味は#103の判断まで変えない。
- 依存関係を上げず、端末差と標準APIのfallbackはplatformに任せる。
- Task Contractは検証・レビュー収束後のmergeまで。実機未評価なら#101はopenで残す。
