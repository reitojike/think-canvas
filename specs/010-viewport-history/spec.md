# Feature Specification: 視点履歴と編集変更の表示補助

**Feature Branch**: `codex/issue-77-viewport-history`
**Created**: 2026-10-04
**Status**: Draft
**Input**: Issue #77 / Roadmap #81。PO承認: 編集と視点の履歴を分離し、画面外の編集Undo/Redo対象へ移動する。現行specをmerge前に暗黙に置き換えない。

## Clarifications

### Session 2026-10-04

- Q: 記録する視点操作はjump中心か手動操作も含むか？ → A: 検索/focus/fit/段階切替/indicator等に加え、完了したpan/pinchを1gesture=1履歴とする。
- Q: 編集と視点を共通履歴にするか？ → A: 分離する。画面外の編集Undo/Redo対象へ移動し、複数対象は変更範囲へfit、消える対象は変更前の位置を表示する。自動移動は視点履歴だけに1回記録する。

## User Scenarios & Testing

### User Story 1 - 視点だけ戻る/進む (Priority: P1)

利用者は検索結果・囲み・indicatorへ移動した後、以前の表示へ戻り、再び先の表示へ進める。直接pan/pinchした視点も同じ操作で辿れる。

**Why this priority**: 内容を変えず、行き来した場所を再探索する手間を減らす。
**Independent Test**: 検索jumpとnative pan/pinchの後に視点back/forwardを実行し、world focus/倍率と内容・保存・Undo/Redoの不変性を照合する。

**Acceptance Scenarios**:

1. **Given** 以前の視点A、**When** 検索/囲みfit/段階切替/double tap/indicatorでBへ移り戻る/進む、**Then** A/Bを復元し、query/選択/内容を変更しない。
2. **Given** A、**When** pan/pinchを完了、**Then** 操作途中を増殖させず1履歴にし、1回でAへ戻る。
3. **Given** A→B→CからBへ戻った、**When** Dへ新しく移動、**Then** 視点のforward Cだけを破棄する。編集Redoは保持する。
4. **Given** 同じ/ほぼ同じ視点、**When** 同一移動を繰り返す、**Then** 重複履歴を追加しない。restoreも自己記録しない。

### User Story 2 - 編集Undo/Redoの変更位置が見える (Priority: P1)

利用者は内容を戻す/やり直す際に、画面外で起きた変更をその位置で確認できる。

**Why this priority**: 見えない内容変更と視点操作の混同を防ぐ。
**Independent Test**: 各要素familyの編集後に遠くへpanし、編集Undo/Redoを行う。実内容・保存は既存の一回の編集履歴に従い、対象表示のcamera移動だけが視点履歴に入る。

**Acceptance Scenarios**:

1. **Given** 画面内で確認できる変更対象、**When** 編集Undo/Redo、**Then** 視点を維持する。
2. **Given** 画面外の変更対象、**When** 編集Undo/Redo、**Then** 対象位置を表示し、内容を再編集したり別のUndo単位を作らない。
3. **Given** 複数対象または消える対象、**When** 編集Undo/Redo、**Then** 変更対象の範囲をfitし、消える要素は変更前の位置を含める。
4. **Given** 編集Undoで対象へ自動移動した、**When** 視点back、**Then** Undo直前の視点へ戻り、取り消した内容は取り消したまま。pan/zoom後も編集Redoが利用できる。

### User Story 3 - 画面寿命と既存操作を守る (Priority: P2)

利用者は再生成/画面サイズ変更後も履歴を辿り、編集・保存中の操作制限と通常Backを混同しない。

**Why this priority**: 保存・gesture・IMEの既存contractを維持する。
**Independent Test**: Activity再生成、board切替、size/density変化、保存Running/Failed/pending ack、editor/tool/move previewとnative cancelで履歴の寿命と次の操作を照合する。

**Acceptance Scenarios**:

1. **Given** boardごとの履歴、**When** Activity再生成/board離脱と復帰/size-density変化、**Then** 同じworld focusと倍率を可能な範囲で復元する。他boardの履歴は混ざらず、process再起動はstackを復元しない。
2. **Given** 初期fit/animation frame/要素move中auto-pan/取り消したgesture、**When** それらが動く、**Then** 各frameや取消を完了履歴として追加しない。
3. **Given** 保存制限/editor/IME/modal/tool/preview、**When** 古い視点actionまたは次のgesture、**Then** 禁止中の移動を受理せず、終了後は最新状態から操作できる。
4. **Given** 視点履歴control、**When** TalkBack操作またはsystem Back、**Then** controlは編集Undoと区別され、system BackのSpec007の一段階終了/一覧契約を維持する。

### Edge Cases

- animation中の次の移動/直接gestureは既存animationを停止し、実際に到達した視点を境界にする。resize/画面終了による中断は新しいユーザー履歴を増やさない。
- 検索入力の各文字のauto-focusは検索入力区間としてまとめ、結果の前/次や他のnavigationを独立境界にする。query自体は履歴化しない。
- 変更対象は内容差分から特定し、未変更要素をfitへ入れない。接続変更等で影響を受けたarrowも対象に含める。
- 変更後に存在する要素は変更後位置、消えた要素は変更前位置を使う。visible intersectionがない対象またはSemantic Zoomで確認できない対象を表示補助の対象とする。
- 部分的に見えている変更対象だけなら視点維持。複数のうち画面外対象があれば変更範囲全体をfitする。最小倍率で収まらない巨大範囲は中心へ移動する。
- 履歴はfiniteな上限を持ち、古いentryから落とす。無効/非finite視点や未測定canvasでは操作しない。

## Requirements

### Functional Requirements

- **FR-001**: 視点履歴はworld-space focusと倍率をauthorityとし、編集履歴と独立したboardごとのback/forwardを提供する。
- **FR-002**: finite navigation familyは検索focus、囲みfit、段階切替、blank double tap、indicator、完了pan/pinch、編集変更表示の自動focus/fitとする。初期fit・animation frame・move auto-pan・取消gestureは除外する。
- **FR-003**: back/forwardは直前/次の視点を復元する。新navigationで視点forwardだけ破棄し、restore自身はentryを追加しない。
- **FR-004**: ほぼ同じ視点を重複記録せず、検索連続入力をまとめ、履歴を有限に保つ。
- **FR-005**: 視点操作は内容、world配置、選択、検索query、編集Undo/Redo、保存要求を変更しない。
- **FR-006**: 編集Undo/Redoは既存の一回の内容変更/保存単位を維持する。変更対象が画面内ならcameraを維持し、画面外/確認不能なら対象へfocusする。複数対象は変更範囲へfitし、消える対象は変更前位置を使う。
- **FR-007**: FR-006の自動移動は視点履歴だけに1回記録する。視点backによって内容を再適用/再取消しない。
- **FR-008**: boardのsession内で視点と履歴を保持し、Activity再生成/size-density変化では同じworld focus・倍率を復元する。process終了後のstack永続化はしない。IME表示・非表示とeditor entry/exitだけではworld focus・倍率を変えず、入力欄を見せる一時的な表示補助をcameraや視点履歴へ残さない。終了後は同じsizeで固定要素のscreen位置へ戻り、5回の反復でもずれを累積しない（Issue #104）。
- **FR-009**: 視点controlは倍率付近に独立したconditional操作として提供する。履歴がない間は非表示、利用不能方向はdisabled、名称/action/semanticsは編集Undo/Redoと区別する。既存system Backを維持する。
- **FR-010**: 保存Running/Failed/pending acknowledgement、editor/IME/modal/tool/preview中は視点controlを抑止し、live guardで同一UI turnのstale actionも拒否する。次の独立gestureやhidden hitの寿命を守る。

### Key Entities

- **視点entry**: world focusと倍率。対象要素のidentityや内容snapshotではない。
- **board視点session**: 現在の視点、back/forward、現在のnavigation境界。内容history/保存sessionと責務を分ける。
- **編集変更範囲**: 編集前後の差分に含まれる要素と、表示すべき変更後または消える前の位置。

## Success Criteria

### Measurable Outcomes

- **SC-001**: 有限navigation familyすべてで、back/forwardのfocus/倍率復元と1操作1履歴を検証できる。
- **SC-002**: 視点操作の前後で内容・配置・編集Undo/Redo・保存への余計な変更は0件。
- **SC-003**: 全要素familyの画面内/外・複数・消失Undo/Redoで、変更位置を表示し、余計な編集/保存/視点entryは0件。
- **SC-004**: 再生成・size-density・guard・cancel・chrome/accessibilityの主要経路を検証し、full required CIとcurrent-head reviewを収束させる。

## Assumptions

- Issue77のPO決定を実装前のauthorityとする。履歴対象と表示補助は今回の明示承認により追加し、既存Spec004/007/008/009の内容保存とsystem Back規則を維持する。
- 共通timeline/編集Redoのpanによる破棄/board switching history/永続履歴/schema変更は対象外。
- compact/large font/TalkBack/物理端末の横断dogfoodingは親81で区別して継続する。
