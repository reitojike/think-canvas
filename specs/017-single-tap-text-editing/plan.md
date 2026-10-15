# 実装計画: テキストの1タップ編集

**Branch**: `codex/issue-111-single-tap-text-editing` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)

## Summary

通常/複数選択のprimary actionを小さい純粋な判定にまとめ、CanvasScreenのpointer tapと対象を明示したsemanticsから同じ動作へ接続する。既存Draft・保存・outside-finalize・gesture所有者を再利用する。長押しメニューへ明示「選択」を追加する。

## Technical Context

- Language/Version: 既存Kotlin、JDK 25。依存版を変えない。
- Primary Dependencies: 既存Jetpack ComposeとAndroid入力/semantics。
- Storage: 既存の端末内保存。schema・保存所有者・Undo/Redoは変更しない。
- Testing: JUnitの純粋状態判定と、既存Android native/Composeテスト。新規のtest frameworkやCI laneは追加しない。
- Target Platform: minSdk 30 / target・compileSdk 37の既存Android app。
- Scope: textのprimary action、明示選択の導線、対応する回帰fixtureと仕様。gesture router全面変更は対象外。
- Performance: 既存のtap releaseで判定し、追加timer・IO・描画や保存を発生させない。

## Constitution Check

- 承認された#111の操作方針をspecへ先に記録済み。未決の製品判断はない。
- Androidの既存Compose操作・custom accessibility actionと既存編集sessionを使う。
- 保存された位置と内容を編集開始や選択変更で変えない。ネットワーク・新しい権限・依存は不要。
- 公開資料は日本語。非公開PRD・会話ログ・ローカルパス・認証情報を公開しない。
- design後も違反なし。spec品質checklistは実装完了とは別に確認する。

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/TextPrimaryAction.kt`: EDIT / ADD_TO_SELECTION / REMOVE_FROM_SELECTIONのpure判定。
- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: primary activation、semanticsの名前と対象、メニュー選択。
- `app/src/test/java/com/thinkcanvas/canvas/TextPrimaryActionTest.kt`: 承認された選択状態の有限matrix。
- `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt`: 既存requiredケースで1タップ・明示選択・複数選択・検索の回帰を確認する。
- `app/src/androidTest/java/com/thinkcanvas/canvas/*Test.kt`等の既存helper: 旧2タップ編集/1タップ選択の前提だけを新契約へ移す。保存・dismissalのassertionは維持する。
- `specs/001-canvas-foundation/spec.md`と`specs/002-spatial-organization/spec.md`: 直接矛盾するtap表現を同期する。

## 設計

- `textPrimaryAction(id, selectedIds)` は選択2件以上なら対象の追加/解除、0/1件なら編集を返す。別のmodeや保存状態は作らない。
- CanvasScreenのtext activationはguard・対象の存在と可視性を確認し、pointerとsemanticsで共用する。支援技術の操作を別要素の座標hit-testへ変換しない。
- 編集開始は同じDraft経路を使い、対象を単独選択して開始する。複数選択では選択集合だけを変える。明示menu編集は既存の明示編集として保持する。
- primary action名は同じ判定から決める。明示の選択追加/解除・move custom actionは維持する。
- メニュー「選択」は対象を単独選択してmenuを閉じ、既存grip・共有へつなぐ。別要素の長押し追加と投げ縄は既存動作を使う。
- editor外側のDOWN所有・finalize-onlyやblank double tap判定には変更を入れない。

## 検証と停止境界

純粋JVMのmatrix、関連Android sourceのcompile、lint・全JVM・build・公開境界を確認し、通常PR CIの既存smokeでnative回帰を実行する。固定署名APKのhead・digest・識別子を確認する。実機確認は未実施として手順を提示し、Draft PRで停止する。merge・Issue closeは行わない。
