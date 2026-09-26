# Tasks: Semantic Navigation

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[data-model.md](data-model.md)、[操作契約](contracts/interaction.md)
**対象 Issue**: [#5](https://github.com/reitojike/think-canvas/issues/5)

## Phase 1: Setup

- [x] T001 `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` と `SpatialElements.kt`、`InkLayer.kt` の描画順・当たり判定・viewport 操作を確認し、既存機能との接続点を定める。

## Phase 2: Foundational

- [x] T002 `app/src/main/java/com/thinkcanvas/canvas/SemanticNavigation.kt` に `SemanticTier`、`SemanticProjection`、検索結果、視点目標の純粋な型を作り、元の `BoardSnapshot` を変更しない契約を設ける。

## Phase 3: User Story 1 — 遠近を切り替えて全体を読む

**独立した検証**: 文字サイズと倍率を変え、近・中・遠、囲みのまとまり、非表示と選択の例外を確認する。

- [x] T003 [P] [US1] `app/src/test/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` に9・5の境界、3種類の文字サイズ、囲みの120・90、入れ子、選択例外の振る舞いを検証するテストを作る。
- [x] T004 [US1] `app/src/main/java/com/thinkcanvas/canvas/SemanticNavigation.kt` で段階と非表示 ID を計算し、文字・図形・矢印・手書きの隠す条件と選択例外を実装する。
- [x] T005 [US1] `app/src/main/java/com/thinkcanvas/canvas/SpatialElements.kt` と `InkLayer.kt` に表示段階を適用し、囲み面と名前、図形・矢印・手書きの簡略化を描く。
- [x] T006 [US1] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` の文字描画・当たり判定・TalkBack 対象へ同じ表示判定を適用し、本文・見出しの最小サイズ、省略、非表示時の操作除外を実装する。
- [x] T007 [US1] `app/src/androidTest/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` で境界を跨ぐ操作後も保存位置が同じで、見えない要素を選択できないことを確認する。

## Phase 4: User Story 2 — 見たい場所へすばやく移動する

**独立した検証**: 倍率表示、空白ダブルタップ、まとまった囲みのタップで目標視点へ移動し、保存データが変わらないことを確認する。

- [x] T008 [P] [US2] `app/src/test/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` に近→中→遠→近、目標7・3.5・100%、倍率制約、囲みフィットの座標を検証するテストを追加する。
- [x] T009 [US2] `app/src/main/java/com/thinkcanvas/canvas/SemanticNavigation.kt` に空白ダブルタップ、倍率切替、囲みフィットの目標 viewport を算出する関数を作る。
- [x] T010 [US2] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` の右下の倍率表示を TalkBack からも操作可能にし、直接 gesture が優先する約340msの視点アニメーションを追加する。
- [x] T011 [US2] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` の空白ダブルタップとまとまった囲みのタップを目標 viewport へ接続する。
- [x] T012 [US2] `app/src/androidTest/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` に倍率表示・ダブルタップ・囲みフィットと TalkBack 操作の確認を追加する。

## Phase 5: User Story 3 — 言葉から要素を探す

**独立した検証**: 0・1・複数件と大小文字違いを検索し、順序、件数、視点、強調、終了後の表示を確認する。

- [x] T013 [P] [US3] `app/src/test/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` に検索語、大文字小文字、上端→左端→IDの順序、前後の循環を検証するテストを追加する。
- [x] T014 [US3] `app/src/main/java/com/thinkcanvas/canvas/SemanticNavigation.kt` に現在の board snapshot を対象とする検索と一致要素への視点目標を実装する。
- [x] T015 [US3] `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt` にモックに準じた検索欄、フォーカス、IME検索、前後・閉じる・件数表示と強調を追加し、操作と件数の読み上げを設定する。
- [x] T016 [US3] `app/src/main/java/com/thinkcanvas/canvas/SpatialElements.kt` と `InkLayer.kt`、`CanvasScreen.kt` で検索一致と選択例外を可視に保ち、非一致を薄くする。
- [x] T017 [US3] `app/src/androidTest/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` に検索0件・複数件、現在位置への移動、検索中のパン・ズーム、終了後の状態を確認する。

## Phase 6: Polish & Cross-Cutting

- [x] T018 `app/src/test/java/com/thinkcanvas/canvas/SemanticNavigationTest.kt` と既存の保存テストで、表示・検索・視点操作が board snapshot と Undo/Redo 履歴を変更しないことを確認する。
- [x] T019 `specs/004-semantic-navigation/quickstart.md` に沿って lint・単体テスト・ビルド・Android 17 エミュレーター・公開情報境界を確認し、モックとの差をレビューする。
- [x] T020 converge で仕様との差を確認し、残作業があれば追記して完了する。PR に実機で後日確認する項目を記録する。

## 依存関係

T001→T002→US1→US2→US3→T018–T020。T003、T008、T013 は各 story の実装に先行できる。各 story は前の段階の表示・視点状態を利用するが、独立した画面手順で検証できる。

## 実装方針

最初に遠近の表示判定を完成させ、次に視点の移動、最後に検索を重ねる。純粋な判定関数と Android の操作テストを組み合わせ、表示だけの変更が保存データへ波及しないことを各段階で確認する。
