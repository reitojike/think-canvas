# 機能仕様: テキスト入力の外側タップによる確定・終了

**Feature Branch**: `codex/issue-71-text-dismiss`
**Created**: 2026-10-02
**Status**: Draft（merge までは現行仕様を置き換えない）
**Input**: [Issue #71](https://github.com/reitojike/think-canvas/issues/71) と [Product decision update](https://github.com/reitojike/think-canvas/issues/71#issuecomment-5954690450)。外側タップは空の新規入力を無保存で終了し、入力済み・既存編集を現在の「完了」の契約で確定する。

## User Scenarios & Testing

### User Story 1 - 外側をタップして入力を確定・終了する (Priority: P1)

利用者は、編集ツールバーまで指を移動せず、入力欄の外側を1回タップして入力を終えられる。1文字も入力していない新規入力は何も作らず終了し、文字を入力した場合は「完了」と同じ検証・保存を行う。

**優先理由**: 日常の入力終了を短い操作で完了できる。
**独立した検証**: 新規の空・入力済み draft と既存編集について、画面・world 座標・履歴・保存内容・再オープン後の内容を照合する。

**受け入れシナリオ**:

1. **前提** 新規の空の draft、**操作** 外側の空白を1回タップ、**結果** 入力欄・ツールバー・キーボード・focus が解除され、要素・保存・Undo/Redo の変更は0件。
2. **前提** 保存可能な文字・種類・色を入力した新規 draft、**操作** 外側の空白を1回タップ、**結果** 「完了」と同じ契約で1要素を元の world 座標に保存し、対象要求の成功後に editor を閉じる。同じタップでは次の入力を開始しない。
3. **前提** 既存文字の内容・種類・色を編集中、**操作** 外側をタップ、**結果** 「完了」と同じ validation と編集保存を行う。保存可能な変更は同じ要素に反映し、元の座標を維持し、別要素・重複履歴・重複保存を作らない。
4. **前提** 外側タップによる終了後、**操作** 改めて空白をタップ、**結果** 通常どおり新しい入力を開始できる。既存要素の編集終了後は従来の選択解除規則に従う。
5. **前提** draft がある、**操作** 外側の既存要素または通常 chrome の旧表示位置をタップ、**結果** 空の新規入力の破棄または「完了」の確定だけを行う。そのタップによる選択・移動・削除・新規入力・zoom・control action は起きない。
6. **前提** draft がある、**操作** 入力欄内または編集ツールバーを操作、**結果** 編集を継続でき、「完了」は explicit commit、「やめる」は explicit cancel として機能する。
7. **前提** 新規または既存の入力途中、**操作** 「やめる」、**結果** 未確定変更を破棄し、要素・保存・履歴を変えず、既存文字を元の状態に保つ。
8. **前提** 同じ editing session の外側 DOWN/UP 間に文字更新が届く、**操作** 短い外側タップの UP、**結果** 最新内容で終了を判断する。新規の最新内容が空なら無保存で破棄し、非空なら「完了」に従う。

### User Story 2 - 入力と保存を意図せず失わない (Priority: P1)

利用者が外側タップまたは明示操作で終了していない入力は、画面の再生成や一時的な focus 変化で失われず、確定した内容の保存失敗は再試行できる。

**優先理由**: lifecycle と IME の変化を外側操作と誤認すると、利用者の意図なしに入力を確定・破棄する。
**独立した検証**: 入力途中と、外側タップから開始した保存の Running/Failed で再生成し、内容・対象 acknowledgement・要求数・再試行を確認する。

**受け入れシナリオ**:

1. **前提** 新規または既存の draft、**操作** Activity 再生成、一時停止・再開、再描画、IME を閉じる、**結果** draft の内容・種類・色・world 座標を保持し、確定も破棄も保存もしない。
2. **前提** 外側タップまたは「完了」から保存中、**操作** 外側タップまたは画面再生成、**結果** 同じ保存要求を維持し、重複確定せず、対象要求の成功後に editor を閉じる。
3. **前提** 外側タップまたは「完了」の保存が失敗、**操作** 外側タップまたは画面再生成後に「再試行」、**結果** 失敗中は draft を破棄せず、外側タップによる自動再試行もしない。既存 retry により同じ変更を1回分として保存し、成功後に閉じる。

### Edge Cases

- 終了は単一 pointer の短いタップの正規 release で成立する。長押し・drag・複数 pointer・途中キャンセルは確定・破棄しない。
- 新規の無保存破棄条件は exact empty string。空白文字も非空として「完了」の validation に渡す。現在の「完了」が空白文字のみを要素化しない動作を変更しない。既存編集の空文字・空白文字も「完了」の validation に従い、独自の削除・破棄・保存規則を追加しない。
- 入力欄と編集ツールバーを外側タップから除外し、表示倍率・IME・再生成で変わる bounds に追従する。閉じた editor と旧 chrome の bounds は次の操作を遮らない。
- 一般的な focus loss、system bars、IME の内部操作だけでは入力を確定・破棄しない。
- 「やめる」は引き続き支援技術・キーボードで到達でき、終了後に非表示の入力欄へ focus を残さない。

## Requirements

### Functional Requirements

- **FR-001**: 保存により編集が禁止されていないとき、入力欄・編集ツールバーの外側タップで終了する。新規の最新 draft が exact empty string なら無保存で破棄する。それ以外は既存「完了」の validation・create/edit・save・acknowledgement に従う。
- **FR-002**: 空の新規入力の破棄と explicit cancel は保存・要素・Undo/Redo を変更しない。保存可能な外側確定では、新規は1要素、既存は同一要素の1編集として保存し、world 座標を維持し、重複履歴・重複保存・重複要素を作らない。
- **FR-003**: 終了タップを消費し、同じ gesture で次の入力、要素への操作、zoom、その他の操作を実行しない。終了後の独立した空白タップは通常の選択解除・新規入力規則に従う。
- **FR-004**: 終了で focus・software keyboard・editor toolbar を解除する。保存が必要な場合は既存の対象要求の成功を待つ。「完了」は explicit commit、「やめる」は無保存の explicit cancel として維持する。
- **FR-005**: 入力欄内と編集ツールバーは通常どおり操作できる。表示中の bounds を用い、旧 bounds が後の操作を遮らない。
- **FR-006**: focus loss、Activity recreation、configuration/lifecycle transition、再描画、IME 内部 transition のみを終了の契機にしない。同じ process の再生成では draft を保持する。
- **FR-007**: Running/Failed/pending acknowledgement では外側 finalization と explicit cancel を拒否する。同じ保存所有者・対象要求・既存 retry を維持し、外側タップで重複要求や自動 retry を始めない。
- **FR-008**: 非タップの外側 gesture は終了しない。DOWN/UP 間の文字更新を同じ編集として扱い、UP 時点の最新内容を用いる。通常操作、TalkBack、toolbar の accessibility action、system back、ボード移動の既存契約を維持する。

### Key Entities

- **draft**: 未確定のテキスト・種類・色・world 座標・編集対象・editing session の識別。保存済み要素とは別のボード単位の状態。
- **保存要求**: 明示「完了」または外側確定に共通する対象要求。既存の所有者が耐久保存と成功通知を担う。

## Success Criteria

- **SC-001**: 空の新規入力と explicit cancel の保存・要素・履歴変更0件。保存可能な新規の外側確定は1要素、既存は同一要素を保存し、再オープンでも内容・種類・色・位置が一致する。重複保存・重複要素・重複履歴0件。
- **SC-002**: 入力途中・Running/Failed の再生成と一時停止・再開で、意図しない破棄・確定と重複作成0件。外側確定からの同じ要求が既存 retry で成功する。
- **SC-003**: 外側終了と明示操作後に focus・IME・toolbar が解除され、同じ gesture の forwarding 0件、次の有効な空白タップと既存 toolbar 操作が成功する。

## Assumptions と補正記録

- forwarding は finalize-only とする。通常 chrome は編集中に非表示で、表示中の編集ツールバーは操作を維持する。外側の既存要素は終了だけを行う。
- process 終了後の draft 復元、toolbar 再設計、gesture architecture 全体の変更、保存所有者・schema・依存版の変更は対象外。
- 旧 head `e806f77141439937d94b268325a8676c64dbcba6` の outside cancel 契約と MERGE_READY は Issue #71 の product decision と不一致で撤回した。bounded correction #2 として本 spec と実装を同期する。過去の証跡は PR #75 と Git 履歴に保持する。
- 物理端末の TalkBack・manufacturer IME・表示/文字倍率は既知の未確認事項として PR に記録し、Roadmap #81 / #73 の mobile dogfooding で後続確認できる。今回の merge blocker へ追加しない。