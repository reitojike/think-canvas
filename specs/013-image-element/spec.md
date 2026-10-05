# 機能仕様: ローカル画像要素

**Feature Branch**: `codex/issue-80-image-element`
**Created**: 2026-10-05
**Status**: Draft（mergeまで現行specを置き換えない）
**Input**: [Issue #80](https://github.com/reitojike/think-canvas/issues/80)、任意の代替テキスト編集A。既存の編集・保存・geometry・視点の各authorityを維持する。

## Clarifications

### Session 2026-10-05

- **代替テキスト — PO決定A**: 任意の手動編集をv1に含める。取り込み時の入力は必須にしない。画像メニューから変更し、説明があれば読み上げ、空欄は「画像」。画面上のキャプションとは別で、OCR/AI自動生成なし。
- **既存一般規則の拡張**: 画像は四角い独立要素として選択・移動・複数選択・囲み包含・矢印接続へ参加する。包含は保存した世界boundsの中心、矢印は対象IDと相対接続位置を使う。画像だけの別geometry/historyを作らない。
- **v1範囲**: 写真pickerと画像ファイルpickerから一枚ずつ追加する。外部の画像Share Target受信は画像要素完成後の別integrationとし、本変更に含めない。回転は0固定で、素材に記録された向きの補正とは区別する。

## User Scenarios & Testing

### User Story 1 - 写真やスクリーンショットを持ち込む (Priority: P1)

利用者はボードの既存ツール入口から画像追加を選び、端末の写真または画像ファイルを一枚選択する。取り込んだ画像を中央付近で確認し、移動・サイズ変更して他の材料と並べる。

**Why this priority**: 思考材料を画像のまま端末内で扱えるようにする。
**Independent Test**: 写真/ファイルの各入口から縦横画像を追加し、向き・内容・配置を確認して再起動する。

**Acceptance Scenarios**:

1. **Given** 有効なボード、**When** 写真または画像ファイルを選ぶ、**Then** 一枚の画像を追加し、元の要素を変更しない。選択取消では内容・Undo・保存を変えない。
2. **Given** 画像選択完了、**When** 取り込みが成功、**Then** 元の縦横比を保ち、選択開始時の表示範囲の中央付近へ、画面で把握できる初期サイズで一枚だけ追加する。
3. **Given** 元の画像を移動/削除するか元のアクセス許可がなくなる、**When** ボードを再度開く、**Then** 取り込み済み画像と世界座標・幅・高さが残る。
4. **Given** 向き情報を持つ写真、**When** 追加・再表示・画像出力、**Then** 正しい向きと縦横比で表示される。
5. **Given** 不正・非画像・読取不能・容量制限を超えた素材、**When** 選択する、**Then** 失敗を明示し、既存内容を変更せず、再選択できる。

### User Story 2 - 他の要素と一緒に整理し、戻す (Priority: P1)

利用者は画像を選択、移動、縦横比を保ってresize、削除できる。複数選択・囲み・接続矢印と一緒に操作し、編集Undo/Redoで元に戻せる。

**Why this priority**: 画像を別の添付画面にせず、同じ空間の材料として扱う。
**Independent Test**: text/shape/ink/imageを囲み、接続矢印を付け、移動・サイズ変更・削除・Undo/Redo・board複製/削除を行う。

**Acceptance Scenarios**:

1. **Given** 画像、**When** タップ/長押しドラッグ/サイズ変更/削除を行う、**Then** 既存の選択・world-space操作に従い、各確定を一つの編集Undo単位で保存する。通常ドラッグはパンを維持する。
2. **Given** 画像と他要素の複数選択または囲み、**When** 移動する、**Then** 初期対象集合を一度ずつ同じworld差分で動かす。接続矢印が追従し、自由端の既存規則を維持する。
3. **Given** 画像削除/サイズ変更を確定、**When** Undo/Redo、**Then** 元画像と説明・geometry・矢印を復元し、素材を失わない。視点履歴とは分離する。
4. **Given** 画像を持つboard、**When** 複製後に片方を削除、**Then** 残したboardの画像は引き続き表示/出力できる。
5. **Given** 保存Running/Failedまたは外部modal、**When** 古い画像操作callbackを実行、**Then** 拒否し、別内容や別boardへ変更しない。

### User Story 3 - 画像を含めて見渡し、出力する (Priority: P2)

利用者は画像を含むboardを一覧thumbnailで見分け、全体または選択部分を画像としてコピー/共有する。遠景から近景まで画像が過剰な読込みで操作を妨げない。

**Why this priority**: 現行の一覧・出力・Semantic Zoomを画像で壊さない。
**Independent Test**: 高解像度写真を含むboardをパン/ズームし、一覧、board/選択出力、共有プレビューを確認する。

**Acceptance Scenarios**:

1. **Given** 画像を含むboard、**When** thumbnail/board出力/選択出力を表示、**Then** 同じworld配置と縦横比の画像を含み、画面chromeや選択枠を出力しない。
2. **Given** 高解像度画像、**When** 遠景/中景/近景や画面外へ移動、**Then** 表示に必要な範囲の解像度だけを読み込み、画面外の画像を全解像度で常時保持しない。
3. **Given** 元画像に位置情報等のmetadata、**When** board/選択を画像出力、**Then** 元metadataや素材の内部参照を出力へ埋め込まない。

### User Story 4 - 読み上げで画像を区別する (Priority: P2)

利用者は画像メニューから任意の代替テキストを編集し、画像を読み上げで区別できる。説明がなくても追加・選択・移動・resize・削除ができる。

**Why this priority**: PO決定Aと、gestureだけに依存しない操作を満たす。
**Independent Test**: 説明の入力/取消/消去、保存/再起動/Undo、TalkBackの選択/移動/resize/削除を確認する。

**Acceptance Scenarios**:

1. **Given** 説明なしの画像、**When** 読み上げで選択、**Then** 汎用の「画像」と選択状態を識別でき、ファイル名や内部参照を説明として使わない。
2. **Given** 画像メニュー、**When** 説明を入力して完了、**Then** 説明を端末内へ保存し、読み上げへ反映する。画面内に新しいcaptionを描かない。
3. **Given** 未確定の説明編集、**When** 明示取消、**Then** 元の説明を保持して閉じる。IME非表示のBackでは未変更なら閉じ、変更済みなら既存の破棄確認に従う。空欄を確定すれば説明を消せる。
4. **Given** compact画面またはlarge font、**When** 主要画像操作、**Then** 確定/取消/失敗から戻れ、既存編集やIMEを黙って終了しない。

### Edge Cases

- picker中のActivity再生成、遅れて返るcallback、board切替/削除、二重操作は、同じ取り込み要求と開始boardへ照合する。無効になった結果を別boardへ追加しない。
- 完了前に利用者がtaskを明示終了した場合は未確定の取り込みを次回へ持ち越さない。保存成功済み画像を取消として削除しない。
- 画像を読込みできなければ、元の位置/サイズを持つ失敗表示を残す。勝手に要素削除・再配置しない。出力失敗を成功扱いしない。
- 素材cleanupは、保存済みboard、起動中Undo/Redo、進行中保存・取り込み・出力のいずれからも参照されないものに限る。中断して残った一時素材も回収する。
- 極端な縦横比でも歪めず、有限な位置と正のサイズだけを許可する。透明部分を持つ画像を扱える。
- 既存の余白挿入で画像が境界をまたぐ場合は画像を歪めず、bounds中心に基づいて移動対象を決める。shape/regionの伸張規則は維持する。

## Requirements

### Functional Requirements

- **FR-001**: 写真pickerと画像ファイルpickerから一枚の画像を追加できる。写真全体への閲覧権限やアプリ内cameraを要求しない。
- **FR-002**: 利用者が明示選択した素材だけを端末内へ取り込み、元のファイル位置やアクセス許可への永続依存を避ける。
- **FR-003**: 追加先boardを開始時に固定し、picker結果・再生成・遅延callbackを照合する。取消/失敗/無効結果では内容・編集履歴・保存を変更しない。
- **FR-004**: 画像は安定ID、素材参照、向き補正済みの寸法、world位置・幅・高さ、任意代替テキストを持つ。利用者の回転編集はv1に含めない。
- **FR-005**: 初期配置は開始時の表示中心付近、初期サイズは表示範囲に収め、縦横比を維持する。自動整列・重なり回避・他要素の移動を行わない。
- **FR-006**: 既存一般規則でrender/select/move/resize/deleteへ参加し、resizeでは縦横比を保つ。確定ごとに既存の編集履歴・保存を使う。
- **FR-007**: 複数選択・囲み包含・余白挿入・接続矢印へ参加し、world geometryのauthorityを共有する。
- **FR-008**: 画像追加/移動/resize/削除/説明変更をUndo/Redoでき、履歴から復元できる間は素材を保持する。編集Undo対象への視点移動も既存規則を使う。
- **FR-009**: board再表示/再起動で画像・geometry・説明を保持する。既存の保存進行中/失敗・明示retryを維持する。
- **FR-010**: board複製と片方削除で残した画像を壊さず、すべての参照がなくなった素材だけをcleanupする。
- **FR-011**: board thumbnail、board/選択画像出力、コピー/Android共有出力へ画像を含め、同じrendered geometryを使う。
- **FR-012**: 表示・出力のbitmap解像度と保持総量に上限を設け、高解像度素材も全解像度で常時保持しない。Semantic Zoomと画面外判定で必要量を制御する。
- **FR-013**: EXIFの回転/反転を表示・出力へ反映し、元metadataを出力に埋め込まない。
- **FR-014**: 任意の手動代替テキストを画像メニューから編集/取消/消去できる。空欄は汎用の「画像」とし、元ファイル名を無条件で読み上げない。
- **FR-015**: 選択・移動・resize・削除・説明編集の読み上げ操作と利用可否を既存要素へ揃え、compact画面/large fontで確定・取消できる。
- **FR-016**: 既存editor/IME/tool/save/share modalの保護とBack/neutral契約を維持し、古いcallbackが保護中の内容・視点を変えない。
- **FR-017**: migrationで既存text/shape/arrow/ink/共有完了記録を保持し、画像がない既存boardでも同じ動作を維持する。
- **FR-018**: 画像・説明を自動送信、OCR、AI解析しない。素材参照や本文をlogへ出さない。外部画像Share Target受信はfollow-upに分離する。
- **FR-019**: 不正/読取失敗/資源上限/保存失敗を利用者に明示し、部分成功や無限retryで既存内容を壊さない。
- **FR-020**: migration/persistence/geometry/lifecycle/native回帰と現行headのrequired CI、および代表実機確認を完了条件とする。

### Key Entities

- **画像要素**: boardの一要素。ID、素材参照、world bounds、intrinsic寸法、任意の説明を持つ。
- **ローカル画像素材**: 端末内の不変素材。複数の要素/boardやUndoが参照でき、参照がある間は保持する。
- **取り込み要求**: 開始board、安定identity、開始時の配置、選択/読込み/適用/保存/失敗/取消を追跡する。
- **画像表示資源**: 表示/出力に必要な解像度に制限した一時画像。boardの保存内容とは分離する。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 写真/ファイル取消、成功、失敗、再生成、古い結果を含む各経路で、成功した一要求あたり一枚、他の結果では追加0枚。
- **SC-002**: 追加・移動・resize・削除・説明変更・Undo/Redoの前後と再起動後で、画像・world bounds・説明が期待値と一致する。
- **SC-003**: 複製片方削除、要素削除Undo、履歴の上限到達、未参照素材回収で、必要な画像を失う件数0。
- **SC-004**: 回転/反転写真、透明画像、高解像度素材の表示・thumbnail・board/選択出力で、向き・縦横比・配置が一致する。
- **SC-005**: 高解像度画像を複数配置して遠景/近景/画面外を反復しても、設計で定めるdecode/保持上限を超えず、制限超過は安全に失敗する。
- **SC-006**: 読み上げ・compact画面・large fontの代表実機で追加、説明編集、移動、resize、取消、削除を完了でき、編集や保存との二重actionが0件。
- **SC-007**: 既存boardのmigrationとfull required CIが成功し、image導入によって既存4要素・text共有の保存/geometry authorityが分裂しない。

## Assumptions

- 本仕様はIssue80とPO決定Aのv1であり、初期Spec001等の画像非スコープをこの機能の対象内に限って拡張する。
- 静止した写真・スクリーンショットを扱う。対応形式と資源上限、animated形式の拒否方法はplanのdecode契約へ固定し、内容を黙って別形式として取り込まない。
- 画像のcrop、filters、画素の編集はv1対象外。位置・縦横比を保つresize・任意説明の編集を扱い、元画像の内容は加工しない。
- 編集履歴は既存の起動中最大80操作、再起動後は空。保存・board lifecycle・view history・geometryは現行Spec001〜011を使う。
- OSの標準pickerで利用者が選んだ素材の読込み以外に、アプリが外部取得/自動uploadを開始しない。
- 代表実機確認はIssue80自身のACであり、未確認のままIssue80をcloseしない。親Issue81の横断確認も別に残す。
