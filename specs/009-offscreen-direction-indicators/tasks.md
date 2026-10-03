# Tasks: 画面外対象の方向インジケーター

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、research/data-model/contracts/quickstart。

## Phase 1: Setup

- [x] T001 現行main/Issue76/PO承認と有限authority censusをspecs/009-offscreen-direction-indicators/plan.mdへ記録する。
- [x] T002 specs/009-offscreen-direction-indicators/spec.md、plan.md、tasks.mdをanalyzeし、10FR/4SC・全scenarioとConstitutionを照合する。

## Phase 2: Foundational

- [x] T003 [P] app/src/test/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktに四辺/角/境界接触/倍率/density/衝突/省略/無効入力のpure regressionを先に追加する。
- [x] T004 app/src/main/java/com/thinkcanvas/canvas/OffscreenIndicators.ktにSEARCH/SELECTION targetとlayout helperを実装する。非空IDs、finite bounds、最大2件、inclusive交差、48dp、search優先、同edge collisionのdata-model制約を維持する。

## Phase 3: US1 現在の検索結果

**Independent test**: current searchを画面外へpan→1表示→touch/action→同じ結果に戻り、保存・履歴不変。

- [x] T005 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktに実検索・nativepan・indicator tap・current result/query切替・Room/history/save0の回帰を追加する。
- [x] T006 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktにcurrentMatchのderived markerと既存focusMatchへのcallbackを接続する。

## Phase 4: US2 選択まとまり

**Independent test**: 単一/複数・異種選択をoffscreenへpanし1表示でfit、selection・content不変。検索との最大2/単一重複を照合。

- [x] T007 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktに単一text/shape/region/ink/arrow・multi、最大2/重複/衝突、semantic zoomの回帰を追加する。
- [x] T008 [US2] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktにselected geometry union、共通text focus、選択だけの既存fit、重複排除を統合する。選択IDs/BoardStateを変更しない。

## Phase 5: US3 通常操作と寿命

**Independent test**: nativepan/pinch・古い位置・入力/preview/save抑止・Back/再生成で残留0、次の通常操作成功。

- [x] T009 [US3] app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktにpan/pinch、chrome/hit cleanup、editor/IME/modal/tool/move/save、Back/再生成と次のgesture、semantics identity/action/orderの回帰を追加する。
- [x] T010 [US3] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktに48dp標準clickable、実chrome避け、conditional register/disposeとlatest-derived admission、live target/guard、安定semantics順を統合する。

## Phase 6: Polish / delivery

- [x] T011 specs/009-offscreen-direction-indicators/tasks.mdへconverge残差を追記し、README.mdとspecs/008-drag-edge-auto-pan/tasks.md/quickstart.mdのmerge後完了事実を同期する。
- [ ] T012 app/のlint/unit/debug/androidTest build、schema/public/diff、標準GMD focused、final head CIのfull source/XML censusを検証しspecs/009-offscreen-direction-indicators/quickstart.mdから証跡を参照できるようにする。
- [ ] T013 GitHub PRのfinal head両CI/canonical/base/threadを収束し、merge後Issue76最新ACを逐条評価する。specs/009-offscreen-direction-indicators/quickstart.mdからdeliveryへ到達できるようにする。

## Dependencies / Parallel Opportunities

T001→T002→T003/T004→US1→US2→US3→T011→T012→T013。pure unit file作成だけCanvasScreenの設計確認と並行可能。US1は検索のMVP、全Issue契約のdeliveryはUS2/US3まで必要。同じCanvasScreen/instrumentationは順次作業。Gradle/GMDは1つずつ、GMD実行中はsource/docsをfreezeする。

## Implementation Strategy

各storyの意味のある回帰を先に作る。custom checklistはreviewer-ownedの未査読markerを保持する。利用者の2対象で進める指示と#81/merge許可は既にあり、追加の製品判断が必要な場合だけPOへ停止する。未実行deliveryを完了扱いにしない。

## Phase 7: Convergence

- [x] T014 HIGH: app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktにmove grip→edge auto-pan中のmarker0、native CANCEL後の選択marker復帰、内容/save/history不変を追加する。FR-007、US3/AC2、T009（partial）。
- [x] T015 HIGH: app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.ktにpending acknowledgementだけのblockと対象削除直後のstale actionを同一UI turnで拒否する回帰を追加する。FR-006/007、US1/AC3、T009（partial）。
