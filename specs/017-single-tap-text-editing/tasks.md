# Tasks: テキストの1タップ編集

## Phase 1: Setup

- [x] T001 `specs/017-single-tap-text-editing/spec.md`と関連spec/現行コードの有限censusを照合し、承認仕様と対象外を固定する。

## Phase 2: User Story 1 - 通常時の直接編集

独立検証: 未選択/単独選択の本文・見出しが1タップで同一対象の編集を開き、確定前の保存/履歴を変えない。

- [x] T002 [US1] `app/src/test/java/com/thinkcanvas/canvas/TextPrimaryActionTest.kt`へ、通常0/1件と複数選択の内外のpure matrixを記述する（FR-001/002/005、SC-001/002）。
- [x] T003 [US1] `app/src/main/java/com/thinkcanvas/canvas/TextPrimaryAction.kt`と`CanvasScreen.kt`へ共用primary判定とguard付き編集開始を接続し、semanticsの対象と名前を揃える（FR-001/002/005/009）。

## Phase 3: User Story 2 - 選択と要素操作

独立検証: 明示選択でgrip・共有へ到達し、複数選択の追加/解除ではeditorを開かず、既存の長押しdrag・投げ縄を維持する。

- [x] T004 [US2] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`のtext menuへ選択を追加し、既存`LongPressGestureTest.kt`のrequiredケースでprimary action・選択・既存操作を検証する（FR-002/003/008、SC-002/004）。

## Phase 4: User Story 3 - 検索と編集終了・支援技術

独立検証: 検索移動だけではeditorを開かず、明示tapで編集する。Aを閉じる外側B tapをBの編集へforwardしない。

- [x] T005 [US3] `app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt`等の既存ケース・helperを、1タップ編集と明示選択の契約へ合わせ、既存のguard/終了assertionを保持する（FR-004/006/007/009、SC-003/004）。

## Phase 5: Polish と検証

- [x] T006 `specs/001-canvas-foundation/spec.md`と`specs/002-spatial-organization/spec.md`の旧tap規則を同期し、影響する既存Android fixtureの選択前提だけを修正する。
- [x] T007 `gradlew.bat`で対象/全JVM・lint・build・Androidテストcompileを行い、`scripts/check-public-boundary.ps1`と既存suite validatorで公開境界/identity構成を確認する。
- [ ] T008 通常PR CIの現head結果・artifact・正式レビュー準備・固定署名APKを確認し、`specs/017-single-tap-text-editing/converge.md`へローカル結果と制約を記録し、head固定後の結果はDraft PRへ残す。
- [ ] T009 `specs/017-single-tap-text-editing/quickstart.md`の実機確認手順と未確認範囲をDraft PRで提示し、mergeせず停止する。

## Dependencies と実施方針

T001 → T002 → T003 → T004/T005 → T006 → T007 → T008 → T009。T004/T005は共通fixtureに影響するため順に統合する。独立ファイルの読み取り・公開境界検証は並行可能だが、同じCanvasScreenとGradle出力は同時に変更しない。通常編集・選択・既存終了契約を一つの限定deliveryとして扱い、部分的にgesture所有権を変更しない。

T008/T009の実行結果はheadを固定した後、Draft PRの検証記録に記載する。CI・レビューの証拠を残すだけのcommitでheadを変えない。
