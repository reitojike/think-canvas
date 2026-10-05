# 実装と検証: 操作の意味に合わせたハプティクス

## Phase 1: Setup

- [x] T001 `specs/014-semantic-haptics/research.md` にcurrent-main censusと標準照合を記録する。（FR-001〜005）
- [x] T002 `specs/014-semantic-haptics/spec.md` と `plan.md` の範囲・不変条件・検証を定義し、要件品質を確認する。

## Phase 2: Foundation

- [x] T003 `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt` にLocalHapticFeedback recorderを既存sessionのCompositionLocalとして追加する。

## Phase 3: US1 長押しの成立 (P1)

- [x] T004 [US1] `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt` の要素/空白release/drag4経路でLongPress1回、追加0回を検証する。（FR-001/002、SC-001）
- [x] T005 [US1] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` のgap threshold feedbackだけを除去し、長押しと操作判定を維持する。

## Phase 4: US2 成功の区別 (P2)

- [x] T006 [US2] `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt` で図形/矢印成功・矢印失敗、touch/stylus stroke終了とUndo/Redoを確認する。（FR-003/004/005、SC-002〜004）
- [x] T007 [US2] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` のcreate共通成功・endpointをConfirmにし、ink/region feedbackを除去する。
- [x] T008 [US2] `specs/002-spatial-organization/contracts/interaction.md` のfeedback規則を本featureへ参照更新する。

## Phase 5: 検証と収束

- [x] T009 `specs/014-semantic-haptics/quickstart.md` にローカルlint/unit/build/androidTest compile、focused GMD、公開境界と差分検査の結果を記録する。（FR-005/006）
- [ ] T010 `specs/014-semantic-haptics/quickstart.md` を参照しPixel9a/Android17実機評価をIssue #101に記録する。（FR-006、未評価ならIssue open）
- [ ] T011 `specs/014-semantic-haptics/quickstart.md` の手順からfinal-head full required CI/canonical review/fresh base/thread0をPRで確認しmergeする。

## Dependencies and strategy

T001→T002→T003→US1→US2→T009→T011。T010は利用者による実機結果待ち。US1だけでも重複削減の価値があるが、今回は同じfeedback familyを一つのPRにまとめる。テストと同一ファイルの変更は逐次実行する。researchと文書の独立な読み取りは並行可能。新しい並列implementation agentは不要。

