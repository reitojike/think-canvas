# Tasks: Android共有からテキストを取り込む

**Input**: `specs/011-share-text/` のspec/plan/research/data-model/contracts。
**Tests**: spec/Issue79が要求する保存・復元・instrumentation検証を行う。

## Phase 1: Setup

- [x] T001 `specs/011-share-text/` をread-only突合し、有限surface/authority/復元境界の実装前checkpointをIssue79へ記録する。FR-001〜015、SC-001〜005。
- [x] T002 `specs/010-viewport-history/tasks.md` の実delivery T012/T013/T016〜T022をPR90/Issue77の完了証跡に同期する。Spec011の依存authorityを現状に合わせる。

## Phase 2: Foundational

- [x] T003 `app/src/test/java/com/thinkcanvas/share/ShareImportRequestTest.kt` と `app/src/main/java/com/thinkcanvas/share/ShareImportRequest.kt` に固定request/element UUID、`textは非blankの原文`、`x/yはfinite world座標`、一件busy、fresh同文/復元のidentity規則を実装・検証する。FR-008/012/015。
- [x] T004 `app/src/main/java/com/thinkcanvas/share/ShareImportCheckpoint.kt` にtask token + private AtomicFileの最新checkpoint、terminal、trusted Bundle tokenを実装する。大きな本文をBundleへ置かず、外部extrasを内部tokenへ使わない。復元token/file欠落でも初期Intentを再受付せず、onStopで取消しない。FR-008/011/013。
- [x] T005 [P] `app/src/main/java/com/thinkcanvas/data/CanvasDatabase.kt` と `app/schemas/com.thinkcanvas.data.CanvasDatabase/3.json` に本文なしreceiptとMIGRATION_2_3を追加、全replaceAllとの原子性・duplicate no-writeを実装する。receiptはboard削除cascadeなし。FR-009/013、SC-005。
- [x] T006 `app/src/main/java/com/thinkcanvas/data/CanvasStore.kt` にlast候補read-only照会、receipt照会/共有save、共有専用cancel伝播を追加する。`BoardSessionViewModel.kt` とそのunit testに通常成功の共有metadata付きack/固定patch一回適用を接続する。通常save/actor/coalescingは維持。FR-002/005/008/009/011。

## Phase 3: US1 確認して一件取り込む（P1）

**Independent test**: warm/cold/lastなし/変更/取消でpreviewと選択先一text、一Undo/一saveを観測。

- [x] T007 [US1] `app/src/main/java/com/thinkcanvas/share/ShareImportViewModel.kt` に受信・一件保留・preview/picker・確定patchを追加。file成功前は受理せず、request状態を先に切り替えてstale確定を拒否。FR-003/004/006/015。
- [x] T008 [US1] `app/src/main/java/com/thinkcanvas/share/ShareImportDialog.kt` に全文scroll、board名、変更/picker/取消/取り込む、0件の明示createを実装する。本文編集は取り込み後。FR-003/006/014。
- [x] T009 [US1] `app/src/main/AndroidManifest.xml` / `app/src/main/java/com/thinkcanvas/MainActivity.kt` にtext/plain ACTION_SEND、singleTask/onNewIntent、current→last→picker、測定済視点中央の確定と既存owner接続を実装する。URL取得/logなし、cold shareで自動board作成なし。FR-001〜006/013。
- [x] T010 [US1] `app/src/androidTest/java/com/thinkcanvas/share/ShareImportInteractionTest.kt` にwarm/cold/候補なし/変更/cancel/URL/一Undo一saveの観測可能な回帰を追加する。FR-001〜006、SC-001/002。

## Phase 4: US2 保存・復元（P1）

**Independent test**: 保存境界のfresh ownerで同要求追加0、未完了manual retry、同文別要求は各一件。

- [x] T011 [US2] `app/src/test/java/com/thinkcanvas/BoardSessionViewModelTest.kt` にreceipt付きack/Failed retry/同要求一回/共有cancelと普通saveの非干渉を検証する。FR-005/008〜011。
- [x] T012 [US2] `app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt` の共有ackにrestore Failed/manual retry/対象共有operation取消を実装。T006の通常成功経路と一般coalescing/ackを維持。FR-005/008〜011。
- [x] T013 [US2] `app/src/main/java/com/thinkcanvas/share/ShareImportViewModel.kt` / `MainActivity.kt` に同task latest checkpoint + receipt照合、config retained継続、fresh Accepted→manual retry、terminal/明示task終了Bを接続する。destination消失はsilent fallbackなし。FR-008〜012。
- [x] T014 [P] [US2] `app/src/test/java/com/thinkcanvas/data/CanvasDatabaseTest.kt` に原子rollback、receipt後Undo/deleteのno-write、1/2→3 data保持を追加する。FR-009、SC-003/005。
- [x] T015 [US2] `app/src/androidTest/java/com/thinkcanvas/share/ShareImportInteractionTest.kt` にActivity再生成とfresh owner/file/parcel token/実Roomの復元、ack gap/Failed/manual retry/new token Bを追加する。ActivityScenarioをOS killと同一視しない。FR-008〜012、SC-003/004。

## Phase 5: US3 既存操作と読み上げ（P2）

**Independent test**: editor/IME/tool/search/save中に保留し、解除後preview。古いaction無効、読み上げで全導線を識別。

- [x] T016 [US3] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` と `board/BoardListScreen.kt` に基礎中立状態/live guardと外部modal blockを接続。gesture/animation/IME/preview/save/ack、一覧modalを保護し、保留中は元操作可能。既存geometry/historyは維持。FR-007/014。
- [x] T017 [US3] `app/src/androidTest/java/com/thinkcanvas/share/ShareImportInteractionTest.kt` にeditor/IME/save Failed/Running中の受信、live stale action、busy二件目、不正入力、semantics/Back、launcher/画像export resultの回帰を追加する。FR-007/013〜015、SC-004。

## Phase 6: 検証・収束

- [ ] T018 `specs/011-share-text/quickstart.md` にlocal basic/Room/schema/public boundary/標準focused GMDの実績を記録し、全required CI/現行head canonical review/base/threadのgateを確認する。SC-005。
- [ ] T019 `specs/011-share-text/tasks.md` にconverge残差を有限タスクとして追記・解消し、PRを作成。最終headをfreezeしrequired CI/review収束後、許可済みmergeを行う。SC-005。
- [ ] T020 `specs/011-share-text/quickstart.md` のproofと最新Issue79 ACを個別照合、達成項目だけ更新してclose。親81の子状態を同期し、実機横断確認は別に残す。

## Dependencies / Parallel

T001/T002→T003/T004/T005→T006→US1(T007〜010)→US2(T011〜015)→US3(T016/017)→T018〜020。
US1が最小の利用者導線だが、lifecycleを欠く状態ではmergeしない。各storyの独立testを実行してから次へ進む。

異なるfileのT005とT003/004、T014とT013は並行可能。下位モデルの利用は有限な監査/独立surfaceで効率化できるときに限定し、同じfileの同時編集をしない。Gradle/GMDは一度に一実行。

## Requirement coverage

| Requirements | Tasks |
| --- | --- |
| FR-001/002 | T006/009/010 |
| FR-003/004/006 | T007〜010 |
| FR-005 | T009〜012 |
| FR-007 | T016/017 |
| FR-008/012 | T003/004/007/011〜015 |
| FR-009 | T005/006/011〜015 |
| FR-010/011 | T004/006/011〜015 |
| FR-013/014/015 | T003/004/008/009/016/017 |
| SC-001/002 | T010/011 |
| SC-003/004 | T014/015/017 |
| SC-005 | T005/014/018〜020 |

## Phase 7: Convergence

- [x] T021 `app/src/main/java/com/thinkcanvas/share/ShareImportCheckpoint.kt` のsync/rename後の成功を確認し、更新失敗をAccepted/terminalへ進めない。`ShareImportCheckpointTest.kt` でAtomicFileの非throwing rename失敗と修復後retryを検証する。FR-008/010、plan: task checkpoint（partial、HIGH）。
- [x] T022 `app/src/main/java/com/thinkcanvas/MainActivity.kt` のDeferred/Openingでpreview記録もreceipt照合し、未確定の古いdestinationより完了receiptを優先する。`ShareImportInteractionTest.kt` で完了→Undo→古いpreview→fresh ownerの再追加0を検証する。FR-009、SC-003、US2/AC3（partial、HIGH）。
- [x] T023 `app/src/main/java/com/thinkcanvas/board/BoardListScreen.kt` の中立判定へIMEを含め、`ShareImportInteractionTest.kt` の名前編集/IME後の保留解除を検証する。FR-007、US3/AC1、plan: 既存編集保護（partial、HIGH）。
- [x] T024 `app/src/main/AndroidManifest.xml` にdocumentLaunchMode neverを指定し、外部NEW_DOCUMENT/MULTIPLE_TASKで新ownerを作らない。`ShareImportInteractionTest.kt` でfresh同文/busy到着と既存owner保持を検証する。FR-008/011、plan: 単一task/Activity（partial、HIGH）。
- [x] T025 `app/src/main/java/com/thinkcanvas/share/ShareImportDialog.kt` の空board名を既存一覧と同じ「無題のボード」でpreviewし、名前を空にするnative回帰を追加する。FR-003/014、US1/AC1（partial、MEDIUM）。

## Phase 8: Convergence

- [x] T026 `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` のマーカー選択、text選択追加、text/region/search入力callbackにもpreview時のlive guardを適用する。`ShareImportInteractionTest.kt` のcaptured actionとeditor/IMEテストに、古い選択/入力が背面状態を変更しない確認を追加する。FR-007、US3/AC2、plan: 外部modal block（partial、HIGH）。

## Phase 9: Convergence

- [x] T027 `app/src/main/java/com/thinkcanvas/share/ShareImportViewModel.kt` の未確定destination消失時のcheckpoint失敗をFailedへ遷移させ、Main effectで更新を反復しない。`ShareImportInteractionTest.kt` の既存file-failureテストで失敗→同要求保持→修復→明示retryを検証する。FR-010、plan: checkpoint失敗はmanual retry（partial、HIGH）。保存・復元familyのread-only checkpointで追加1roundをこの遷移とnative oracleへ限定した。
- [x] T028 `app/src/androidTest/java/com/thinkcanvas/board/BoardListScreenTest.kt` の名前変更保存前にnative IMEと保存ボタンの安定した画面位置、確定入力を確認する。既存native click、5000ms保存開始gate、再生成後一回のRoom renameというassertを維持する。SC-005、plan: 既存一覧/IME回帰（partial、MEDIUM）。全183件の一失敗は保存開始待ち、原因未確定。入力/位置の準備を観測するfocusedで再検証し、製品guardを根拠なく変更しない。
