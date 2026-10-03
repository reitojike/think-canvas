# 機能仕様: 通常キャンバスへ戻る共通操作

**Feature Branch**: `codex/issue-73-neutral-state`
**Created**: 2026-10-03
**Status**: 実装済み（実機確認項目あり。merge後に現行仕様として扱う）
**Input**: [Issue #73](https://github.com/reitojike/think-canvas/issues/73)、親 [#81](https://github.com/reitojike/think-canvas/issues/81)。先行する #71 / Spec 006 は実装・merge・完了済み。

## Clarifications

### Session 2026-10-03

- Q: IMEを閉じた後のBackで、テキスト・囲み名の未確定入力をどう扱うか？ → A: 未入力の新規入力・変更のない既存編集はそのまま終了する。変更があれば「編集内容を破棄しますか？」と「破棄する／編集を続ける」を表示する。確認画面でのBack・外側タップは編集継続とする。IMEを閉じるだけのBackでは入力を保持する。保存中・保存失敗中は破棄しない。

## User Scenarios & Testing

### User Story 1 - 入力を守りながらBackで編集を終える (Priority: P1)

利用者は、終了ボタンを探さず編集からキャンバスへ戻れる。未確定の変更を失う場合だけ確認を受ける。

**優先理由**: Backの無反応と意図しない入力消失を防ぐ。
**独立した検証**: 新規・既存テキストと囲み名について、確認・継続・破棄・保存状態・再生成を照合する。

1. **前提** キーボードが表示中、**操作** system Back、**結果** キーボードだけ閉じ、入力を保持する。
2. **前提** IME非表示で未入力の新規テキストまたは変更のない既存編集、**操作** Back、**結果** 無保存で編集を閉じ、そのBackではボード一覧へ移動しない。
3. **前提** 未確定の変更あり、**操作** Back、**結果** 破棄確認を表示する。「編集を続ける」、確認の外側タップ、確認中のBackは入力・保存・履歴を変えない。
4. **前提** 破棄確認、**操作** 「破棄する」、**結果** 未確定変更だけを破棄する。既存要素の内容・位置と履歴は保持し、入力欄・IME・focus・旧hit targetを除去する。
5. **前提** 新しい囲みの名前を入力中、**操作** 変更を破棄、**結果** 名前入力のみ破棄する。作成・保存済みの囲みは削除しない。
6. **前提** 編集途中または破棄確認中、**操作** Activity再生成、**結果** 入力と確認対象を保持し、自動確定・破棄しない。

### User Story 2 - ツールから自然に通常操作へ戻る (Priority: P1)

利用者は図形を一度作成したら通常の選択へ戻り、ペンは連続描画してからBackで終了できる。

**優先理由**: 一回の操作で意図しない次の作成・ボード移動を起こさない。
**独立した検証**: 作成・lasso・連続ink・途中previewの取り消しと、次のtap/pan/zoomを確認する。

1. **前提** 四角・丸・囲み・矢印ツール、**操作** 作成成功、**結果** 作成ツールを解除し選択を保持する。囲み名編集だけは別の入力として継続する。
2. **前提** lasso、**操作** 選択完了、**結果** 選択を保持しlassoツールを解除する。
3. **前提** ペン・マーカー、**操作** 複数stroke、**結果** 連続描画を維持する。Backまたは「やめる」で未確定previewを取り消し通常操作へ戻る。確定済みstrokeは保持する。
4. **前提** 作成・移動等の途中preview、**操作** Back、**結果** 未確定内容を追加せずpreviewとツールを解除する。その後の古いpointerのUPでも確定しない。
5. **前提** active tool、**操作** Activity再生成、**結果** armed toolを保持する。進行中gestureのpreviewは取り消し、自動確定しない。

### Edge Cases

- 既存テキストの変更は内容・種類・色を開始時の保存済み要素と比較する。変更後に元へ戻した場合は確認不要。新規テキストはexact emptyなら、種類・色を選んだ場合も未入力として終了する。空白文字は未確定入力として確認する。
- 囲み名は空文字も有効な変更。元の名前との差分がなければ確認不要。
- Running/Failed/pending acknowledgementではBackによる編集破棄・ボード移動・自動retryを拒否し、既存の対象要求と再試行を保持する。
- 確認対象が別の編集sessionへ変わった場合、古い確認で新しい入力を破棄しない。
- inkのblank tapは短いstrokeと衝突するため終了に使用しない。スタイラスのモード優先規則を維持する。

## Requirements

### Functional Requirements

- **FR-001**: 通常状態は未確定editor・armed creation tool・wet preview・不要なIME/focus・旧chrome hit targetを持たず、tap/pan/zoom/selectionを受理する。確定した選択とcontextual actionsは保持してよい。
- **FR-002**: IME非表示のBackで、未入力新規・変更なし既存の編集は無保存で閉じる。変更ありは破棄確認し、明示「破棄する」だけが未確定変更を破棄する。
- **FR-003**: 「編集を続ける」・確認のBack・外側dismissは編集を保持する。確認表示時は入力focus・IMEを解除し、未確定内容は保持する。確認後の破棄は同じ編集sessionだけに適用する。
- **FR-004**: Spec 006の外側タップ・Done・明示Cancel・validation・save/ack/retryを維持する。Backは今回のPO判断により追加する終了操作であり、外側タップとは意味を分ける。
- **FR-005**: 四角・丸・囲み・矢印・lassoは既存のone-shot完了と選択を維持する。囲み名入力は作成ツールとは別のeditorとする。
- **FR-006**: inkは連続描画を維持し、Back・明示終了で未確定strokeを取り消す。作成・移動・resize等のpreviewも終了後に古いgestureから確定しない。
- **FR-007**: Backの優先順は保存guard、標準modal、破棄確認、text/囲み名editor、context menu、検索、active tool/preview、展開tool、選択、ボード一覧とする。一回のBackは一段階のみ処理する。
- **FR-008**: editor・ツール終了の共通cleanupはfocus/IME・preview・該当chrome boundsを除去する。modeごとの確定・破棄と保存の責務を共通cleanupへ移さない。
- **FR-009**: Activity再生成・focus lossのみで確定・破棄しない。編集・確認対象・armed toolを保持し、進行中pointerのpreviewだけを取り消す。
- **FR-010**: 終了操作は支援技術から到達可能で、確認ボタンは操作結果を明示する。狭い画面・大きい文字でも確認と継続に到達できる。

### Key Entities

- **編集session**: 対象・未確定内容・開始時の比較元・session識別を持つ。
- **終了要求**: Back・明示終了・作成完了・外側finalization。各modeに応じて保持・確認・破棄・確定・ツール解除を決める。
- **破棄確認**: 一つの編集sessionに紐付き、確認画面のdismissは編集継続を意味する。

## Success Criteria

- **SC-001**: 新規・既存テキスト・囲み名の変更あり/なし、確認継続/破棄で、意図しない保存・要素削除・履歴変更は0件。
- **SC-002**: 全5 spatial toolと2 ink toolの終了後、次の通常操作が成功し、旧preview・hit targetからの追加操作は0件。
- **SC-003**: 再生成・保存中・保存失敗中の確認で、意図しない編集消失・重複確定・自動再試行は0件。既存Spec 006の回帰検証を維持する。

## Assumptions

- 共通化は実際にeditor2 family、spatial/ink familyで必要な終了判定・cleanupに限定する。全面的gesture rewrite・toolbar redesign・保存schema変更・新機能は対象外。
- 明示「やめる」は従来の即時cancelを維持する。今回の確認はBackからの未確定変更破棄に適用する。
- 囲み名の外側タップによる自動保存は追加しない。既存Doneと新しいBackを使用する。
- 物理端末のTalkBack・IME製品差・片手操作の評価はRoadmap #81の横断dogfoodingで記録する。
