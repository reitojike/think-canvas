# 実装タスク: 通常キャンバスへの復帰

## Phase 1: 仕様と開始時checkpoint

- [x] T001 main/#71のfinite censusとPO確認をspecs/007-neutral-canvas-interaction/spec.md、plan.md、research.mdに記録する。
- [x] T002 specs/007-neutral-canvas-interaction/data-model.md、contracts/interaction.md、quickstart.md、checklists/を作成し要件をanalyzeする。

## Phase 2: 共通editor基盤

- [x] T003 app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.ktにRegionNameDraftと変更判定を追加する。newはtext.isNotEmpty、existingはtext/kind/color、regionはname != originalName、sessionIdはcopyで保持する。
- [x] T004 app/src/test/java/com/thinkcanvas/canvas/EditorExitTest.ktで空/空白、種類/色、元へ戻した内容、region空名とidentityを検証する。

## Phase 3: US1 入力のBack終了

- [x] T005 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktにeditor変更あり/なし、確認継続/破棄、IME、recreation、save guardsの回帰を追加する。
- [x] T006 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktとapp/src/main/res/values/strings.xmlにlive guard/session bound確認、region session保持、共通focus/IME/bounds cleanupを実装する。

## Phase 4: US2 ツールと通常状態

- [x] T007 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktにspatial/lasso/連続ink/Back/preview cancellation/次の操作/recreationを追加する。
- [x] T008 [US2] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktで有限Back優先順、tool状態保持、pointer continuation取消、共通preview cleanupを実装する。

## Phase 5: 検証と収束

- [x] T009 app/でlint/unit/debug/androidTest build、schema、公開境界、diff、focused/full instrumentationと既存Spec 006 regressionを検証しspecs/007-neutral-canvas-interaction/quickstart.mdへ結果を記録する。
- [x] T010 specs/007-neutral-canvas-interaction/tasks.mdにconvergeで残差を確認し、README.mdとSpec 006のBack参照を同期する。
- [x] T011 GitHub PRでfinal-head CI/canonical review/threads/baseを収束し、merge後にIssue #73の最新ACを逐条判定する。specs/007-neutral-canvas-interaction/quickstart.mdから証跡へ到達できるようにする。

## 依存・実装戦略

T001→T002→T003/T004→US1→US2→検証・converge。US1はeditorからの無保存close、US2はtoolからの復帰を独立に検証できる。テストsuiteとCanvasScreenの作業は別ファイルで並行可能だがGradle実行は一つにする。custom checklistはreviewer-ownedの未査読状態を保持し、利用者の「提案した挙動で進める」という実装許可に従い進める。

## Phase 6: Convergence

- [x] T012 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktのDOWN admissionと全event/Releaseでlive gesture generationを照合し、recomposition前の終了後UPによる確定を拒否する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでBackと古いUPを同一UI turnでdispatchし、修正前failure・修正後無確定を確認する。FR-006/008、US2/AC4（contradicts）。
- [x] T013 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktのtext/region自動focusを破棄確認中は抑制し、確認解除時だけ編集へ復帰する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでtext/region確認の再生成後に背後editorが非focusであること、IMEを強制hideせず一回のBackで確認を閉じ入力を保持することを検証する。FR-007、US1再生成（contradicts）。
- [x] T014: app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktのnew/existing/region entryとContinue/dialog Back/outside resumeで、editor focus・Activity window focus・active input connection・IME visible/bottomを同じreadiness helperで待つ。次のhideより後にIMEが表示される競合をfixtureで防ぎ、再生成確認の非focus・一回Back・Board/Room保持assertを維持する。US1 continuationの検証不足（missing）。

## Phase 7: Convergence

- [x] T015 CRITICAL: app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktでIME/確認/editor/menu/search/tool/expanded/selection/listの受理Backによるpointer continuationを同期に失効させ、preview前DOWNも古いMOVE/UPから確定させない。Initial passのtext outside loopもDOWN/live世代を照合する。app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.ktでpreview前のtext/grip/MOVE/resize、menu/search/expanded、outside-confirmをBackとMOVE/UP同一UI turnで検証し、priority・入力/選択/Room/Undo・次の操作を維持する。FR-006/007/008、US1/AC3、US2/AC4（contradicts）。

最終T009/T011は[Issue73のclosure証跡](https://github.com/reitojike/think-canvas/issues/73#issuecomment-5970533783)で完了。merge d56c098、final-head c4b5df7の両CI成功・fresh110/0/0/0・canonical clean・thread0・最新17 AC達成を記録した。

## Phase 8: Issue91 独立IME復帰follow-up

- [x] T016 HIGH: `CanvasScreen.kt` のtext/search/regionNameの既存focus effectでwindow ownerを標準WindowInfoのone-shotで待ち、frame適用後にIMEをshowする。`NeutralInteractionTest.kt` へnative別window所有中のeditor entryと、Backで隠したIMEのwindow復帰時保持の回帰を追加し、dialogのhide/Back fixtureを明示isDialog rootへ限定する。Spec007 FR-003/007/009。main修正前のFocused=trueをnative回帰で再現。PO/save/Undo/geometry/Back priority維持。
- [x] T017 `Issue91` の最終headでfocused、lint/unit/build、公開境界、full CI/source XML census、canonical review/base/threadを収束し、merge後に最新7 ACをsemantic closureする。PR90の視点履歴とはreview/rollback境界を分離する。

## Phase 9: Issue91 Convergence

- [x] T018 HIGH: `CanvasScreen.kt` のtext/search/regionName focus要求を、window復帰とframe適用後に同じnative host viewのactive input connection/acceptingText成立まで取消可能なframe待機にする。showは一回だけとし、effect keyとBackで隠したIMEの保持を維持する。`NeutralInteractionTest.kt` のreadiness timeoutにfield/native window/接続/insetsの診断をfailure時だけ追加しassert/timeoutを維持する。Spec007 FR-003/007/009、Issue91 AC1/2/3/4、[bounded checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5975731094)（partial）。

- [x] T019 HIGH: `NeutralInteractionTest.kt` で別native window所有中のpending editorを終了し、再composition前のwindow帰還でも古いeditorへfocus/IMEを戻さないことを検証する。必要なら `CanvasScreen.kt` の三focus要求で既存session/search/確認のlive stateをawait後に照合する。Issue91 AC3、Spec007 FR-003/009（pending要求の取消検証不足）。

## Phase 10: Issue91 input owner Convergence

- [x] T020 HIGH: `CanvasScreen.kt` の三focus ownerをlive save/ack admissionと実在fieldで保護し、blocked状態のkey変化で待機を取消す。IMM active/acceptingTextの無期限frame pollを除去し、API30以降は標準IME-controllabilityの取消可能な通知、API26〜29はwindow focus/frame経路を使い、entry/resumeごとの一回showとwindow focus非keyを維持する。`NeutralInteractionTest.kt` のwindow/継続/pending終了・置換および `TextEditorDismissalTest.kt` のRunning/Failed再生成をnative focused/fullで検証する。Spec007 FR-003/004/007/009、Spec006 FR-006/007、Issue91 AC1〜5、[第三bounded checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5976059426)（partial）。

## Phase 11: Issue91 readonly focus Convergence

- [x] T021 HIGH: `CanvasScreen.kt` の三ownerでfocusとIMEのadmissionを分離し、native window復帰後の現在fieldへのfocusをreadonlyでも保持する。live save/ack guardはfocus要求後のIME待機/showだけを拒否し、blockedでは即returnする。既存 `TextEditorDismissalTest.kt` のFocused=true/draft/ack/要求/Room/Undo保持条件を緩めずRunning/Failed再生成を検証し、Neutral継続/pending終了・置換と最終142件のdeliveryを収束する。Spec007 FR-004/009・SC-003、Spec006 FR-006/007、[第四bounded checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5976130770)（contradicts）。

## Phase 12: Issue91 pending入力取消 Convergence

- [x] T022 HIGH: `CanvasScreen.kt` の三入力要求を標準Jobで取消可能に登録し、既存clearEditorFocus/IME優先Back/検索closeの受理hideで同期cancelする。finallyで登録を除去し、searchのlive確認guardを追加する。`NeutralInteractionTest.kt` のnative別windowで確認受理から再composition前の非focus、確認Backから編集復帰、IME Backからwindow帰還後の非表示とdraft/Board/Room/Undo保持を検証する。Spec007 FR-003/007/009、Issue91 AC3/4/5、[第五bounded checkpoint](https://github.com/reitojike/think-canvas/pull/92#issuecomment-5976244593)（contradicts）。実装・focused成功後も、最終head全体143件とdeliveryの確定までT017/T020〜T022はpending。

## Phase 13: Convergence

- [x] T023 HIGH: `CanvasScreen.kt` とapp固有の `EditorImeWindow.kt` で、未完了IME要求がcontrol/frame中にnative window focusを失っても現在ownerのまま取消可能に復帰を待つ。focus要求・IME showは各一回、readonly/save/ack/live sessionとpending Job取消を維持し、IME-control通知と実際の所有を分離する。`EditorImeWindowTest.kt` のlegacy/modern段階・終了/取消で補正前RED/後GREENを確認し、`NeutralInteractionTest.kt` で三ownerのlate window往復と既存hide取消を検証する。Spec007 FR-003/007/009、Issue91 AC1–5（partial）。deliveryは既存T017で確定し、API26〜29のnative未実行を明記する。

T017/T020〜T022は[Issue91の最新7AC closure](https://github.com/reitojike/think-canvas/issues/91#issuecomment-5977858342)で完了。PR92 merge a116374bのtreeは最終検証head bfdbad45と一致し、両CI37183797445成功・fresh144/0/0/0・最後の依頼より新しいcanonical clean・thread0を確認した。過去のpending記述は当時の状態として保持する。

## Issue #112 bounded implementation

- [ ] T024 `CanvasScreen.kt` にshort outside tapのdismiss専用ownerを追加し、既存editor/modal/chrome優先・drag/pinch/stylus・世代guard・blank arbitrationを維持する。
- [ ] T025 `ConditionalChromeLifecycleTest.kt` の既存native testを拡張し、outside blank/element、取消/長押し、drag/pinch/stylus、control、次gestureと旧palette hitを検証する。suite identity・quarantineは変更しない。
- [ ] T026 local compile/lint/unit/build/androidTest compile/receipt/public boundaryとfocused GMD、PR natural CI、exact-head review/thread/baseを確認し、Task Contractのmerge gateを満たす場合だけmergeする。
- [ ] T027 main natural CIのexact214と最新Issue ACを個別reconcileする。実機証拠がない条件をチェックせず、具体的なチェックリストとともにHOLDする。

2026-10-10 pilotは `ISSUE_112_SCOPE_OR_EVIDENCE_HOLD`。T024/T025の候補コードとnative回帰は追加したが、GMDは既存 `local.properties` に対する `WINDOWS_GMD_PREFLIGHT_BLOCKED / LOCAL_OR_UNSUPPORTED_CONFIG_PRESENT` でtest開始前に停止した。設定の削除・launcherの緩和・blind rerunは行わない。Claude Codeのread-only second opinionでregion併存の退行とtest証拠の不足を確認し、同一責務内で第1ラウンドの補正を実施した。補正中のlint解析は `FirExpressionStub` の `ClassCastException` で失敗し、source更新も重なったため最終候補の成功証拠にしない。最終lint・focused GMD・PR/main natural CI・canonical review・実機確認は未証明であり、T024〜T027とIssue ACは未完了のまま保持する。

## Issue #112 Recovery の到達点（2026-10-10）

- 元checkoutの6ファイルをhash/patch/byte backupで保全し、専用clean worktreeへbyte一致で移行した。元の未commit変更、local.properties、build outputsは変更/コピーしていない。
- 移行したfrozen sourceのlintDebugは成功し、FIR例外のHOLDを解消した。round2後はlint/unit/debug build/androidTest compile成功、checkpoint追加round後もlint/androidTest compile成功。receipt/public boundary/schema/workflow/suite集合の不変を確認した。
- round1を消費済みとして引き継ぎ、round2を新設fixtureのfinger入力へ限定した。outside element tap後のnative palette非表示assertは同じ箇所で再現したため、Unknown metadataを単独原因とは扱わない。
- [family checkpoint](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6096737676)をread-onlyで実施し、Claude Codeと生成bytecodeの独立確認を行った。追加1 roundは新設fixtureのoutside座標前提と数値診断だけに限定した。
- region priorityの標準Windows GMDはfresh owned exact1/0/0/0、child exit0、source unchangedでPASS。checkpoint追加roundのoutside sampleはdismissal・二重発火防止・次tap・CANCEL/FLAG_CANCELED/長押し・pan/pinchのassertを通過したが、stylus stroke保存で期待1/実際0のfailure。fresh owned exact1/1/0/0、child exit1、source unchangedであり、suite全体はstrict red。
- 最終分類は `ISSUE_112_SCOPE_OR_EVIDENCE_HOLD`。stylus failureのproduct/fixture起因は未確定。追加roundを使い切ったため、追加checkpointなしにround4へ進まない。productionのInk/saveや#123/#124のharnessを推測で補正しない。
- PR/push/merge/required PR-main CI/canonical review/実機確認は未実施。IssueはOPEN、ACは未チェック。T024〜T027は全体の未証明を保持して未完了とする。quarantine38/manual full252は起動していない。
## Stylus family recovery checkpoint（2026-10-10）

[Phase A checkpoint](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6097182988)で元/recovery両checkoutの6ファイルを保全し、既存stylus strict redとnative metadata・bounds・Initial/main/generation・ink mode/release/preview/BoardState/save/Roomの有限surfaceをread-only照合した。sourceではstylusはpaletteTapから除外されるが、旧artifactに実観測がなく原因を直接分類できなかった。

Phase Bの一時診断をstylus区間でだけ有効化する形で追加し、元assertion・timeout・selectorを保持してfocused GMDを1回実行した。`20261010T115511Z-d18670f181724cfeb8495785c363f468` はPixel7/API37、fresh owned exact1/1/0/0、child exit1、source unchanged。stylus区間の手前、長押し後のnative「ペン」control存在assert（probe source L328）で停止し、stylus診断tagは0件。XML SHA-256は `344FD0EC397823CA4C35D569B231FCE53396523760D00CF4341E4F7A1D5D258E`。

**責務分類: DIAGNOSTIC_INCONCLUSIVE / 最終分類: ISSUE_112_SCOPE_OR_EVIDENCE_HOLD。** stylus failureは解消済みとしない。Phase CでFIXTURE_CORRECTION/PRODUCT_OUTSIDE_ADMISSION_CORRECTIONを支持する直接証拠がなく、追加1 correction roundは開始しない。round1/2/3は消費済みのまま、probeは1回上限を消費。別findingへ分割してbudgetをリセットせず、再実行・長押し/Ink/save/harnessの推測補正をしない。

GMDのterminal照合後、一時診断を除去し、両checkoutの6ファイルが開始時の保全bytesと一致することを確認した。この記録の追記以外、既存候補は維持。public boundary/diffと禁止対象への差分なしを確認。delivery gateは未成立で、push/PR/canonical review/merge/post-merge reconciliationは未実施。Issue OPEN・AC未チェックを維持する。
## Testability recovery（2026-10-10）

[独立診断checkpoint](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6097753000)に従い、元/recovery両checkoutの6ファイルを保全した。既存methodのassertion/timeoutを保持し、一時method A/Bを各1回だけ標準Windows GMDで実行した。正式suiteへ追加せず、terminal照合後にtest/診断codeをbyte復元して除去した。

- A: `20261010T130911Z-3b0b975a65b54a5991aae13b5fc669c2`、Pixel7/API37、fresh exact1/0/0/0、child0、source unchanged。native長押し851ms/threshold400ms、generation1、palette expanded維持、Compose/cached/fresh platformのPen存在を観測した。元のeditor/取消/pan/pinch等を省いたsetupなので、元long-press failureの解消証拠にはしない。
- B: `20261010T131719Z-97d2d1cfaf604706817624c3ebd23523`、Pixel7/API37、fresh exact1/1/0/0、child1、source unchanged、Room期待1/実際0を独立再現。STYLUS/source16386/flags0/id0/pressure1のDOWN/MOVE/UP（elapsed0/69/139ms）を記録し、3点すべてcanvas内・観測chrome外と照合した。DOWNはCompose Stylus/consume false/chromeHit false、generation1でink modeへadmit、preview1点。MOVE注入直後、native=nullのsynthetic Releaseが同じgenerationで届き、normalRelease=false。boardAdded/saveRequest/saveAcceptedは0件、BoardState/Roomとも0、save Idle。

Bは「正常release後の保存失敗」ではなく、注入側の正常UPより前に受信側がsynthetic cancelされたことを証明した。#115のnative正常release contractに従ったpreview破棄を変更しない。取消producer（native取消、pointer node lifetime、system gesture等）は現artifactでは未確定。Aも元failureを同じsetupでは再現していない。

責務分類は `DIAGNOSTIC_INCONCLUSIVE`、最終分類は `ISSUE_112_SCOPE_OR_EVIDENCE_HOLD`。#112に閉じるfixture/product defectの直接証拠がなく、追加1 correctionは開始しない。round1/2/3を消費済みとして維持し、diagnostic PASSをcandidate PASSへ読み替えない。suite receipt/public boundary/diffと禁止対象差分なしを確認。Issue OPEN、AC未チェック、PR/push/review/merge/main CI未実施を維持する。
## Issue112 Test Contract Redesign（2026-10-10）

[全assertion mapping](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6098743250)と[旧strict-red / differential INCONCLUSIVE](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6098556259)を保全。補正round1/2/3をリセットせず、production2ファイルbytesは維持した。#107既存regressionとassertionはそのまま、新設10identityを独立初期状態へ分離し、66/224/38/262のinventory/期待件数/CI表示を同期した。旧cancel producerと元setup依存のlong press failureは未解決履歴。新fixtureのbounds/metadata変更は正常入力前提の明示であり、旧FAILのPASS化ではない。T024〜T027は最終検証・delivery/実機証拠が成立するまで未完了。
