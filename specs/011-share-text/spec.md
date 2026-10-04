# Feature Specification: Android共有からテキストを取り込む

**Feature Branch**: `codex/issue-79-share-text`
**Created**: 2026-10-04
**Status**: Draft（mergeまで現行specを置き換えない）
**Input**: [Issue79](https://github.com/reitojike/think-canvas/issues/79)のdestination/placement A/Aと明示task終了B。PR90/Issue77完了後のslice。

## Clarifications

### Session 2026-10-04

- destination: warm/coldとも表示中board、なければ最後に開いた有効なboardを初期候補とする。内容とboard名のpreviewを必ず表示し、変更可能。有効候補なしはpicker。
- placement: preview確定後、取り込み先で表示する視点の中央付近へ一つのtextを追加する。自動整列/重なり回避は行わない。
- 明示task終了: 未保存の要求を次回起動へ持ち越さないB。保存済み内容をUI ackの有無で削除しない。OSによるprocess終了とtask restorationは取消と区別し、同じ受理済み要求を重複追加しない。

## User Scenarios & Testing

### User Story 1 - 共有内容と取り込み先を確認する (Priority: P1)

利用者は他アプリの共有メニューでThinkCanvasを選び、内容とboard名を確認して一つのテキストを追加する。

**Why this priority**: コピーして貼り付ける手間を減らし、取り込み先の誤りを防ぐ。
**Independent Test**: warm/coldの共有、候補なし、取り込み先変更、取消を実行し、内容・位置・保存回数を照合する。

**Acceptance Scenarios**:

1. **Given** 表示中board、**When** 文章を共有、**Then** 内容とそのboard名をpreviewし、確認前の内容変更は0件。
2. **Given** boardを表示していない、**When** 文章を共有、**Then** 最後に開いた有効boardを候補にし、なければpickerを表示する。
3. **Given** preview、**When** 別boardを選択して取り込む、**Then** 選んだboardだけに視点中央付近の一つのtextを追加する。
4. **Given** URLや改行を含む共有内容、**When** 取り込む、**Then** 通常のテキストとして内容を保持し、URLの情報を取得しない。
5. **Given** preview/picker、**When** 取消、**Then** 内容・配置・編集Undo/Redo・取り込みによる保存要求を変更しない。

### User Story 2 - 再生成や保存失敗で二重追加しない (Priority: P1)

利用者は回転、OSによるprocess終了、保存失敗を経ても、同じ取り込みが二重に追加されない。

**Why this priority**: 内容の重複と保存済み内容の取り消しを防ぐ。
**Independent Test**: 受理・内容追加・耐久保存・UI完了通知の各境界で新しいownerへ復元し、要求identityと保存内容を照合する。

**Acceptance Scenarios**:

1. **Given** 同じ共有要求、**When** Activity再生成、**Then** previewまたは保存状態を継続し、内容を追加し直さない。
2. **Given** 取り込み確定後で保存完了が不明、**When** OSのtask restoration、**Then** 実際の保存完了を確認し、完了済みは再追加しない。未完了は明示再試行を待つ。
3. **Given** 保存成功後でUI ack前、**When** 再生成またはtask終了、**Then** 保存済み内容を保持する。後からUndoで要素が消えていても同じ要求を再適用しない。
4. **Given** 保存失敗、**When** 再試行、**Then** 同じ要求・要素identity・確定内容と位置を使い、別の追加や別Undo単位を作らない。
5. **Given** 未保存の取り込み、**When** 利用者がtaskを明示的に閉じて新しく起動、**Then** 保留要求を持ち越さない。保存成功済み内容は保持する。
6. **Given** 同じ文章を改めて別の共有操作で受信、**When** それぞれ確認、**Then** 別要求として一つずつ追加できる。

### User Story 3 - 既存編集と読み上げを守る (Priority: P2)

利用者は編集中に共有を受けても未確定編集を失わず、読み上げで取り込み先と確定/取消を識別できる。

**Why this priority**: 受信によって既存editor・保存・通常Backを壊さない。
**Independent Test**: editor/IME/tool/preview/modal/保存制限中の受信、制限終了後のpreview、stale確定、読み上げsemanticsを照合する。

**Acceptance Scenarios**:

1. **Given** 既存編集・操作または保存制限、**When** 共有受信、**Then** 元の操作を黙って終了せず、取り込みを保留して通知する。
2. **Given** 制限終了後のpreview、**When** 取り込み操作または古いaction、**Then** 最新状態で一回だけ受理し、利用不能時のactionを拒否する。
3. **Given** TalkBack利用、**When** previewとpickerを操作、**Then** 共有内容・board名・選択状態・確定/取消・失敗/再試行を識別できる。

### Edge Cases

- 対象外の形式、空/不正な共有内容、読み込み失敗は追加しない。内容を黙って切り詰めない。
- 候補boardがなくなっていればpickerへ戻し、別boardへ黙って追加しない。確定後の保存先消失は失敗として扱う。
- 一件の取り込みが保留/処理中なら、別の新しい共有要求は利用者に再共有を案内する。既存要求を上書きしない。
- 保存完了は耐久保存の事実をauthorityとし、UI表示や現在の要素の有無だけでは判定しない。
- previewで取り込み先を選ぶだけではboardの内容を変更しない。空のpickerからのboard作成は既存の明示的な作成操作として扱う。
- URL、本文に含まれる機密情報、改行は通常textとして扱い、外部取得や送信を開始しない。

## Requirements

### Functional Requirements

- **FR-001**: Android共有メニューでv1のplain text/URLの受け取り先として表示する。対象は一つのテキスト共有とする。
- **FR-002**: warm/coldとも表示中board→最後に開いた有効board→pickerの順で初期候補を決める。
- **FR-003**: 内容とboard名のpreview確認を必須とし、取り込み先を変更可能にする。確認前に内容を追加しない。
- **FR-004**: 確認した内容を選択先の表示する視点中央付近へ一つの通常textとして追加し、既存creation/Undo/save authorityを使う。
- **FR-005**: 通常の成功時は一つの編集Undo単位と一つの保存要求で取り込む。配置の自動整列・重なり回避を行わない。
- **FR-006**: preview/pickerの取消では内容・配置・編集Undo/Redo・取り込みの保存要求を変更しない。
- **FR-007**: 既存editor/IME/tool/preview/modalや保存Running/Failed/pending ackを保護し、取り込みを保留する。確定は最新状態を再確認する。
- **FR-008**: 受理した一つの要求に安定した要求・要素identityを持たせ、Activity/process再生成や同じ要求の復元で二重追加しない。
- **FR-009**: 内容の耐久保存と同じ要求の完了を一つの保存境界にする。完了済み要求を要素の削除/UndoやUI ackの欠落によって再適用しない。
- **FR-010**: 保存失敗は同じ確定内容・位置・identityで明示再試行する。自動retryせず、未確定の保存結果を先に照合する。
- **FR-011**: OSのtask restorationは取消と区別する。明示task終了後の新規起動へ未保存要求を持ち越さず、保存成功済み内容を削除しない。
- **FR-012**: 内容一致だけで別の共有操作を重複扱いしない。別要求は別の確認と追加になる。
- **FR-013**: payloadをlog/外部serverへ出さず、URLの情報取得や自動network accessを行わない。保存は端末内に閉じる。
- **FR-014**: preview/pickerの内容、board名、選択、確定/取消、保存状態/再試行を読み上げ可能にし、通常のBackと既存編集終了契約を維持する。
- **FR-015**: 一件の保留要求を別要求で上書きしない。対象外/不正/空の受信は安全に拒否し、既存boardを変更しない。

### Key Entities

- **共有要求**: 一つの受信、共有本文、安定identity、候補/確定したboard、確定位置、preview/受理/保存/失敗/完了/取消状態。
- **取り込み完了記録**: 同じ要求の内容が耐久保存された事実。現在の要素やUI ackとは独立する。
- **取り込み先候補**: 表示中または最後に開いた有効board、または利用者がpickerで選んだboard。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 有効候補のあるwarm/coldで必須previewから一回の確定で取り込め、候補なし/変更/取消も検証できる。
- **SC-002**: 通常の一要求につきtext一つ・編集Undo単位一つ・保存要求一つ。取消による内容/保存変更は0件。
- **SC-003**: Activity/process復元、保存成功とUI ackの間、完了後Undoの各境界で、同じ要求の重複追加は0件。別共有した同一本文は二件として取り込める。
- **SC-004**: 失敗/再試行、OS復元と明示task終了、既存編集/保存制限、読み上げ操作、payload privacyの主要経路を検証できる。
- **SC-005**: 既存boardを失わず更新でき、受信/復元経路の回帰と現行headの必須検証・reviewを収束させる。

## Assumptions

- v1外部contractはACTION_SEND + text/plain + EXTRA_TEXT。URLは普通のtextで、画像/binary/SEND_MULTIPLE/metadata取得/cloud inbox/AI要約は対象外。
- A/AとBはIssue79本文のPO決定をauthorityとする。現行Spec005/006/007の既存保存/編集終了/Back、Spec010のboard別視点authorityを維持する。
- v1 previewは本文の確認と取り込み先変更を行い、本文の編集は取り込み後の既存text editorで行う。
- OSが同じtaskを復元する場合の状態と、明示task終了後の新規起動を分ける。一般の編集Undo stackをprocess再起動後に永続復元する要件は追加しない。
- compact/large font/TalkBackと全機能の実機横断確認は親81で扱う。
