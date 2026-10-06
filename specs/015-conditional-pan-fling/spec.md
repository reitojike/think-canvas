# Feature Specification: 払いだけに反応する手動panの慣性移動

**Feature Branch**: `codex/issue-102-conditional-fling`
**Created**: 2026-10-06
**Status**: Draft
**Input**: [Issue #102](https://github.com/reitojike/think-canvas/issues/102)。大きなボードを速い払いで移動でき、ゆっくりの位置合わせは離した位置で止める。

## Clarifications

### Session 2026-10-06

- Q: 現行panの使いやすさと慣性の必要性は？ → A: 大きいボードでは即停止への不満が強まりそうなので、払いには慣性を採用する。ゆっくりの移動はその位置で止める。

## User Scenarios & Testing

### User Story 1 - 精密操作と長距離移動を両立する (Priority: P1)

利用者はゆっくり移動して正確に止め、速く払うと離した後も弱い慣性で移動する。

**Why this priority**: 大きなボードを少ない操作で探索しながら位置合わせを失わない。
**Independent Test**: 同じ表示位置から遅いdragと速い払いを実行し、離した後の表示位置を比較する。

**Acceptance Scenarios**:

1. **Given** 操作可能なcanvas、**When** ゆっくりpanを終える、**Then** 指を離した位置で止まり、その後の移動は0。
2. **Given** 操作可能なcanvas、**When** 速い払いを正常に終える、**Then** 同じ向きの弱い慣性移動が続き、減速して止まる。
3. **Given** panの取消またはpinch、要素移動、筆記、余白挿入、**When** 指を離す、**Then** 手動pan用の慣性移動を開始しない。

### User Story 2 - 次の操作をすぐ行う (Priority: P1)

利用者が慣性中に触れ直す、pinch、Back、編集やツールを開始すると、その操作がすぐ表示位置を所有する。

**Why this priority**: 動き続ける画面が精密操作や別操作を妨げない。
**Independent Test**: 慣性中に各割り込みを行い、古い移動がその後の画面を変えないことを観測する。

**Acceptance Scenarios**:

1. **Given** 慣性中、**When** 新しいtouch/Back/編集/ツールを開始する、**Then** その時点で古い移動を停止し、停止後の古いframeが表示位置を更新しない。
2. **Given** 慣性中、**When** pinchする、**Then** 旧慣性を停止し、指の操作だけでpan/zoomする。
3. **Given** 慣性中、**When** 画面離脱・再作成・操作不可状態へ移る、**Then** 古い移動を再開しない。

### User Story 3 - 履歴と内容を分ける (Priority: P2)

利用者はpanと続く慣性を表示位置の一操作として戻せる。保存内容や編集Undoには影響しない。

**Independent Test**: 通常停止と割り込み停止の両方で表示履歴を一回戻し、開始位置を復元する。内容と保存回数を前後で照合する。
**Why this priority**: 表示履歴を大量に積まず、思考の配置を守る。

**Acceptance Scenarios**:

1. **Given** completed pan＋慣性、**When** 停止後に表示履歴を戻す、**Then** 一回でpan開始前へ戻り、forwardで停止位置へ戻る。
2. **Given** 慣性を新操作で止める、**When** 表示履歴を戻す、**Then** 完了済みpan＋慣性の途中停止位置を一つの境界として扱う。
3. **Given** pan＋慣性、**When** 停止する、**Then** Board/Room/save/編集Undo/Redoに変更が0。

### Edge Cases

- 低速、停止してからrelease、取消、追加指、stylus、editor/tool、対象のない画面では慣性を開始しない。
- 再touchはcanvasの空白・要素・chromeのいずれでも停止する。
- edge auto-pan、要素drag、zoom、TalkBackの既存操作へ慣性を追加しない。
- 新たなviewport animation、表示履歴復元、サイズ変更と競合しない。

## Requirements

### Functional Requirements

- **FR-001**: 遅い手動panはrelease位置で止まり、速い正常な払いだけに弱い減速移動を追加する。
- **FR-002**: 慣性を開始できるのは操作可能な単一指手動panの正常releaseだけとする。
- **FR-003**: 新touch/pinch/Back/editor/tool、画面離脱、操作不可、別の表示移動で旧慣性を同期停止する。
- **FR-004**: pan＋慣性は自然停止/途中停止とも表示履歴1境界。frameを履歴へ追加しない。
- **FR-005**: content/Board/Room/save/編集Undo/Redo、edge auto-pan、haptic、zoom inertiaを変更しない。
- **FR-006**: 標準の速度推定・減衰を優先し、有限な速度上限で弱い移動に制限する。独自physicsと依存更新をしない。
- **FR-007**: native入力で低速/高速/割り込み/履歴/不変条件を検証し、Pixel9a/Android17で即停止と慣性を比較して採否の根拠を記録する。

### Key Entities

- 手動panの継続: 開始前の表示位置、終了時の速度、継続を所有する画面の寿命。永続化しない。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 低速panのrelease後300msで追加移動0。速い払いは同方向に追加移動して有限時間で停止する。
- **SC-002**: 各割り込み後300msで旧慣性による追加移動0。
- **SC-003**: 表示履歴は一回のback/forwardで開始/停止位置を復元する。内容・保存・編集履歴の変化0。
- **SC-004**: 実機で精密pan、長距離、払い、再touch、pinch、片手操作の比較結果を記録する。未確認を成功と扱わない。

## Assumptions

- read-only checkpointと利用者の方向決定を経て、条件付き慣性のbounded implementationへ進む。実装後の実機比較は未実施。
- merged Spec010の表示履歴、Spec008のedge auto-pan、Spec014の振動を維持する。
- 技術上の速度閾値/上限はplanに定義し、実機結果に応じた補正は根拠を残す。
- full current-head CI、canonical review、fresh main、thread0を確認してmergeする。実機比較未達ならIssueをopenに保つ。
