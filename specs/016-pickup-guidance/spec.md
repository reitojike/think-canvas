# Feature Specification: 長押し成立をpickupとして理解できる案内

**Feature Branch**: `codex/issue-103-pickup-guidance`
**Created**: 2026-10-06
**Status**: Draft
**Input**: [Issue #103](https://github.com/reitojike/think-canvas/issues/103)。現行release/menu・drag/move/gapを維持し、成立時の案内と表示を明確にする。

## Clarifications

### Session 2026-10-06

- Q: 長押しの製品意味はどの案を採るか？ → A: pickup-firstの推奨案を採り、原則Androidの標準的な挙動に合わせる。

## User Scenarios & Testing

### User Story 1 - 長押し後の選択を理解する (Priority: P1)

利用者は要素を長押しすると、移動の準備ができた表示と、動かす/離すと何が起きるかの案内を見る。

**Why this priority**: 振動しただけで次の操作が分からない状態を減らす。
**Independent Test**: 長押し成立時の枠・案内・振動を観測し、releaseとdragで案内どおりの結果を確認する。

**Acceptance Scenarios**:

1. **Given** 要素、**When** 長押しが成立する、**Then** pickupの枠と「ドラッグで移動、離すとメニュー」を示し、成立振動は一回。
2. **Given** 成立した長押し、**When** 距離閾値以下で離す、**Then** menuを開き、準備案内を消す。
3. **Given** 成立した長押し、**When** 距離閾値を越え動かす、**Then** moveへ渡し、menu用の準備案内を消す。移動・保存・Undoは従来通り。

### User Story 2 - 対象ごとの意味を理解する (Priority: P2)

画像を含む全要素にpickup表示を出す。集合や選択追加、空白の余白操作も、そのrelease/dragの結果を案内する。

**Independent Test**: image、multi-selection追加/集合、blankの成立表示と結果を照合する。
**Why this priority**: 対象によって「持ち上げた」が見えなくなる差をなくす。

**Acceptance Scenarios**:

1. **Given** 未選択画像、**When** 長押し成立、**Then** temporary pickup枠を出す。永続selectionや画像内容・座標は変えない。
2. **Given** 既存選択と未選択対象、**When** 長押し成立、**Then** releaseが選択追加であることを案内し、dragは従来の対象移動。
3. **Given** 選択済み集合、**When** その対象を長押し、**Then** 集合移動とrelease/menuを案内する。
4. **Given** blank、**When** 長押し成立、**Then** 従来の余白案内を維持し、releaseはno-op、dragはgap。

### User Story 3 - 古い準備状態を残さない (Priority: P1)

利用者は取消や別操作をすると、古いpickup表示と案内が残らない。TalkBackでは既存context actionsを使える。

**Independent Test**: native cancel/Back/pinch/次の操作で準備cueと旧pointerが消えること、既存保存・操作を照合する。
**Why this priority**: 案内がすでに終わった操作を指示し続けない。

**Acceptance Scenarios**:

1. **Given** pickup準備、**When** release/cancel/Back/別操作、**Then** 準備案内を消し、古いpointerを継続しない。
2. **Given** selected grip/handle、stylus/ink、pan/pinch、**When** 既存操作を行う、**Then** context pickup案内を混ぜない。
3. **Given** haptic無効またはTalkBack、**When** 操作する、**Then** 表示・案内と既存actionsで意味を理解できる。

### Edge Cases

- 長く保持しても準備案内が途中で通常の通知期限により消えない。
- 成立後のdrag中に「離すとmenu」と案内しない。通常のregion/作成案内を失わない。
- multi-selection追加と集合移動を区別し、selectionそのものをpreviewから書き換えない。
- imageのloading/placeholderでもpickupを示す。resource/Bitmapの寿命を変更しない。

## Requirements

### Functional Requirements

- **FR-001**: 要素の成立時にpickupとrelease/dragの結果を案内する。標準timeout/touchSlopとLongPress一回を維持する。
- **FR-002**: imageを含む既存element familyのtemporary pickup表示を揃え、通常/selected描画を維持する。
- **FR-003**: blank、集合移動、選択追加、明示grip/handle、stylus/ink、TalkBackの意味を有限に分ける。
- **FR-004**: cueはheld中保持し、slop admission、release、取消、Back、admissionで消す。既存owner/generation guardを維持する。
- **FR-005**: Board/Room/save/Undo/geometry、pan/pinch/条件付きfling、画像resource寿命、menu機能を変更しない。
- **FR-006**: native focused/full回帰と実機の理解しやすさ・誤操作を検証する。実機未達ならIssueは完了にしない。

### Key Entities

- pickup案内: gesture寿命の一時表示。永続化しない。selectionや内容とは別のpresentation。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 要素/blank/集合/追加selectionの案内がrelease/dragの結果と一致し、LongPressは一回。
- **SC-002**: release/slop超過/取消/Back後、準備案内の残留0、stale pointerによる追加操作0。
- **SC-003**: imageのpickup表示で通常selection/内容/保存が変わらない。既存操作とUndo回帰成功。
- **SC-004**: Pixel9a/Android17で理解しやすさと誤操作の評価を対象APKとともに記録する。

## Assumptions

- 利用者はpickup-firstを選択済み。Android標準のcontext action成立時menuとの差は、自由配置canvasのrelease-vs-drag契約を守る限定例外とする。
- 標準platform timeout/slop/haptic/accessibilityを出発点とし、generic gesture frameworkを作らない。
- #102のmerge後mainを基準に実装する。実機評価とIssue完了はmerge gateと別に記録する。
