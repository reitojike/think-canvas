# 実装タスク: edge auto-pan

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、research/data-model/contracts/quickstart。

## Phase 1: 開始時checkpoint

- [x] T001 #73のmerge/closure/current mainを再読し、app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktのpointer/Back/save/preview finite censusとSHAをspecs/008-drag-edge-auto-pan/plan.mdへ追記する。
- [x] T002 specs/008-drag-edge-auto-pan/のspec/plan/checklist/tasksをanalyzeし、authority/PO stop boundary・対象2 family・最終検証を照合する。

## Phase 2: 共通geometryとsession

- [ ] T003 [P] app/src/test/java/com/thinkcanvas/canvas/EdgeAutoPanTest.ktに四辺/角/中央/小画面/速度上限、density、dt、倍率.15/1/3のpointer/camera world差分テストを先に追加する。
- [ ] T004 app/src/main/java/com/thinkcanvas/canvas/EdgeAutoPan.ktにpure速度/world anchor helperとUI-local move sessionを実装する。band/速度/dtはfinite、中央停止帯、preview中はBoardSnapshot非変更、sessionをRoom/saved instanceへ保存しない。

## Phase 3: US1 指を離さず運ぶ

**独立検証**: pointerを端で固定した一回のdragでcanvas幅/高さを超えて運び、中央復帰でpan停止、UPで見た位置へ一回確定。

- [ ] T005 [US1] app/src/androidTest/java/com/thinkcanvas/canvas/EdgeAutoPanTest.ktにmanual frame clockのstationary pointer、finger offset、固定要素pan、release前BoardState不変、UP位置の回帰を追加する。
- [ ] T006 [US1] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktにrestricted pointer scope外のframe処理を追加し、move/MOVE handleのpreview・releaseを一つのworld anchor差分へ統合する。frameでは既存viewport/previewのみ、releaseではmoveSelection/saveSnapshotを一回呼ぶ。
- [ ] T007 [US1] Pixel9相当でA/B profileの右/下/角/中央復帰/dropを比較し、specs/008-drag-edge-auto-pan/plan.mdへ採用値・操作・結果・物理端末の限界を記録する。製品判断が必要ならPOへエスカレーションし停止する。

## Phase 4: US2 相対配置と履歴

**独立検証**: multi/region/inkの移動後の相対配置、Room、Undo/Redoを一回で照合。

- [ ] T008 [US2] app/src/androidTest/java/com/thinkcanvas/canvas/EdgeAutoPanTest.ktにmulti-selection/region/ink、開始時包含、接続/自由矢印、one Undo/save、再読み込みの回帰を追加する。
- [ ] T009 [US2] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktでsession IDs/world anchorを固定し、既存translatedSelection/BoardState.moveSelectionへ同じdeltaだけを渡すことを確認する。frameごとのcontent record/saveを作らない。

## Phase 5: US3 取消と次の操作

**独立検証**: Back/CANCEL/2本指/stylus/save block/recreation・STOPで残留0、古いframe/UP無効、対象外gestureと次の操作が成功。

- [ ] T010 [US3] app/src/androidTest/java/com/thinkcanvas/canvas/EdgeAutoPanTest.ktに全停止条件・古いUP・MOVE handle・normal pan/pinch/ink/stylus・accessibilityの有限回帰を追加する。
- [ ] T011 [US3] app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.ktでowner/世代の同期失効とfinally/Lifecycle STOP/disposal cleanupを統合し、#73終了primitiveを再利用してframe/Releaseをlive guardで拒否する。

## Phase 6: 検証とdelivery

- [ ] T012 specs/008-drag-edge-auto-pan/tasks.mdにconvergeでbuildable残差を追記し、README.mdとquickstart.mdのfeature/検証案内を同期する。
- [ ] T013 app/のlint/unit/debug/androidTest build、schema/public/diff、標準launcherのfocused、final-head full source/CI XML censusを検証し、specs/008-drag-edge-auto-pan/quickstart.mdへ記録する。
- [ ] T014 GitHub PRのfinal-head CI/canonical review/base/threadを収束し、merge後のIssue #78最新ACを逐条判定する。specs/008-drag-edge-auto-pan/quickstart.mdからdelivery証跡へ到達できるようにする。

## 依存・並列・実装戦略

T001/T002→T003/T004→US1→US2→US3→converge/検証/delivery。同じCanvasScreenとinstrumentationは順に作業し、Gradleは一つずつ実行する。pure helperのtest作成は別ファイルの設計確認と並行可能。US1は単要素のMVP、US2/US3を加えてIssue78の全契約を満たす。

custom checklistはreviewer-ownedの未査読markerを維持する。利用者の#81対応・merge許可に沿って進め、PO判断が必要な範囲では停止する。全最終検証が未完了の状態を完了と扱わない。
