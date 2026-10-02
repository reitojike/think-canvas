# 実装計画: テキスト入力の外側取り消し

**Branch**: `codex/issue-71-text-dismiss` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

## Summary

CanvasScreen の draft、pointer admission、BasicTextField focus、chrome bounds、保存 continuation を有限集合として確認した。外側タップは dismiss-only とする。編集 state と保存 acknowledgement は既存 BoardSessionViewModel のボードごとの Session に保持し、保存処理の所有権は変更しない。

## Technical Context

- Kotlin、Jetpack Compose、Android API 26～37。依存版・SDK・schema の変更なし。
- テスト: focused instrumentation（実 pointer、Room 照合、ActivityScenario）、既存単体テスト、Android CI の Pixel 9 API 37 GMD。
- 制約: 未確定変更を保存しない。world 座標と履歴は変更しない。process death の draft 復元は追加しない。
- 範囲: CanvasScreen、ボードごとの editor holder、ViewModel と MainActivity の受け渡し、回帰テスト。

## Constitution Check

| 原則 | 設計前・設計後の確認 |
| --- | --- |
| I authority | #71 の明示的変更と現行 001/005 spec の cancel/save 契約を照合。非公開資料を追加しない。 |
| II standard-first | Compose の focus/IME、ViewModel の構成変更時の state 維持を利用。自由配置の複合 gesture に限定して pointer の Initial pass で dismiss gesture を消費する。入力欄・toolbar の標準処理と semantics を維持する。 |
| III 空間配置 | draft の取り消しと再生成で board の snapshot/history を変更しない。 |
| IV ローカル | 新規通信・AI・アカウント要件を追加しない。 |
| V 仕様先行 | spec→clarify（重要な曖昧さなし）→plan→要件品質 checklist→tasks→analyze→implement→converge の順。 |

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt`: Draft と editor の3つの observable state。
- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: 外側タップ判定・共有 cancel・focus 解除・bounds cleanup。
- `app/src/main/java/com/thinkcanvas/BoardSessionViewModel.kt`: Session に editor を保持する getter。
- `app/src/main/java/com/thinkcanvas/MainActivity.kt`: 対象ボードの editor を渡す。
- `app/src/androidTest/java/com/thinkcanvas/canvas/TextEditorDismissalTest.kt`: 利用者の操作と保存内容の回帰。
- `specs/006-text-editor-dismissal/`: この変更の仕様、設計、品質確認、作業。

## 設計と implementation checkpoint

1. draft / pendingDraftAcknowledgement / pendingNewElementId は TextEditorSession にまとめる。ViewModel.Session が同じ process で保持する。Deferred を Bundle 保存する仕組みは追加しない。
2. 外側タップの handler は既存 canvas handler と別の pointerInput に置く。Initial pass の down で最新 draft と field/toolbar bounds を判定し、外側だけ gesture 全体を消費する。単一 pointer、touchSlop 以下、longPress timeout 未満、正規 Release だけ cancel。Compose の合成 release と native MotionEvent の cancellation flag は取り消しにしない。native event はキャンセル metadata の照合だけに使う。drag/multitouch/cancel は draft を維持する。
3. 元の canvas handler は draft の外側の down を double tap 判定より先に拒否する。最初の空白タップ位置から始まる入力欄内の既存 double tap は維持する。dismiss 後の同じ up から通常操作を始めない。lastBlankTap は取消で消去する。
4. editor と toolbar の bounds は通常 chrome と別に表示時の boundsInParent を登録し DisposableEffect で消去する。field の scale と offset、toolbar の IME padding を反映する。旧 bounds の登録を残さない。
5. 共通 cancel は Running/Failed と pending ack を拒否し、focusManager.clearFocus と keyboard.hide と draft=null を行う。一般 focus loss / dispose では cancel しない。
6. commitDraft の作成/編集、対象 acknowledgement の await と identity guard、retry の分岐は維持する。recreation で同じ ack を再 await し、二重作成を避ける。

## 検証

[quickstart.md](quickstart.md) に従う。新規空・文字あり、既存内容/種類/色、次の blank tap、入力欄/toolbar、旧 chrome、double tap 競合、drag/長押し/cancel、再生成・一時停止/再開・IME back、running/failed/retry を focused instrumentation で確認する。通常 CI 全体を通す。

## 検証で判明した待機の補正

全78件の local GMD で、既存 BoardListScreenTest の明示閉じるテストだけが失敗した。一覧は failure dialog の背面に残るため、一覧 semantics の存在だけでは非同期復帰の完了を証明しない。アプリの動作は変更せず、隣接の system back テストと同様に BoardListActionState.Idle を待ってから一覧復帰と acknowledgement を確認する。対象は app/src/androidTest/java/com/thinkcanvas/board/BoardListScreenTest.kt の1ケースに限る。

## Review convergence: 内容更新と編集セッション

外側 gesture の DOWN/UP 間にも IME の確定や hardware keyboard の入力は届きうる。Draft.copy による内容・種類・色の更新を新しい編集と誤認しないよう、Draft の生成時に sessionId を作り、copy では維持する。新しい Draft を生成したときだけ識別子が変わる。外側 UP は同じ sessionId を照合し、最新 draft を共有 cancel で取り消す。識別子は process 内の editor state だけに存在し、snapshot/history/Room に保存しない。DOWN 後の onValueChange と UP の間隔が long press timeout 未満の回帰テストで確認する。
