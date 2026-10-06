# Implementation Plan: 視点履歴と編集変更の表示補助

**Branch**: `codex/issue-77-viewport-history` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

## Summary
現在cameraをboard sessionへ移し、world中心/倍率の有限back/forwardを内容履歴と独立に保持する。既存navigationをanimation終了境界で、manual pan/pinchを正常完了で一度記録する。Spec015の速いpanは標準decayの継続を含め停止時に一度、低速pan/pinchは正常releaseで記録する。Undo/Redo差分から画面外の変更対象だけ表示する。BoardStateのChange/保存/schemaは変更しない。

## Technical Context
**Language/Version**: Kotlin 2.4.20 / Java target17
**Primary Dependencies**: 既存Compose BOM 2026.09.00 / lifecycle。追加更新なし
**Storage**: BoardSessionViewModelのprocess sessionのみ、永続stackなし
**Testing**: JUnit / Compose instrumentation / 標準Windows focused GMD / Android CI両job
**Target Platform**: Android min26 / target37
**Project Type**: Android mobile app
**Performance Goals**: MOVE/frameごとの履歴追加0、stack上限80
**Constraints**: 内容Redo/配置/query/選択/保存への副作用0。cancel/IME/save ack/hidden chrome維持
**Scale/Scope**: ViewportHistory、ContentHistoryFocusと既存三ファイルの限定統合。依存/CI/launcher変更なし

## Constitution Check
| 原則 | 研究前後の判定 |
| --- | --- |
| I authority | #77のPO承認をspecに記録、PRD/mockを公開しない |
| II standard-first | Compose Button/48dp/説明。二種類の履歴は別control、system Back維持 |
| III 配置 | cameraだけ変更、model座標とsnapshotへ書き込まない |
| IV local-first | session内、network/AIなし |
| V spec先行 | spec→clarify→plan→checklist→tasks→analyzeをコード前に完了 |
| 公開/技術 | 既存構成保持、新version選定なし、非公開/ローカルpathを追跡しない |

Phase0/1 PASS。Constitution例外なし。

## Project Structure
```text
specs/010-viewport-history/
  spec.md plan.md research.md data-model.md quickstart.md tasks.md
  checklists/requirements.md checklists/ux.md contracts/navigation.md
app/src/main/java/com/thinkcanvas/
  BoardSessionViewModel.kt MainActivity.kt
  canvas/ViewportHistory.kt canvas/ContentHistoryFocus.kt canvas/CanvasScreen.kt
app/src/test/java/com/thinkcanvas/canvas/
  ViewportHistoryTest.kt ContentHistoryFocusTest.kt
app/src/androidTest/java/com/thinkcanvas/canvas/
  ViewportHistoryInteractionTest.kt
```
既存single Android appを維持する。

## Complexity Tracking
違反なし。新永続化/履歴基盤/入力dispatcherは導入しない。

## Issue #104: IME表示補助の限定補正

公開main `5190c2a`のread-only checkpointで、ManifestのadjustResize、edge-to-edge、rootのsafeDrawingPadding、canvasSize、resize、editor終了、#91のfocus lifecycle、history、placement、animationを照合した。resizeは旧sizeのworld中心を新sizeへ写す。一方、入力表示補助effectはIME表示中にcameraを直接panし、hidden/draft終了時はearly returnする。この非対称なviewport writerを補正対象とする。alpha.10実機の直接再現とplatform固有原因は未確認。

- IME中の入力欄だけを、測定済みcanvas内の表示範囲へ一時的に配置する。166dp幅、150dp最大高さ、54dp toolbarと倍率から表示範囲を求め、world座標・cameraへ書き戻さない。画面より大きい欄は上端/左端に置く。
- IME hiddenでは通常のworldToScreen位置へ戻す。履歴のrestore、独自keyboard offset、固定pixel補正、復帰delay、永続状態を追加しない。
- `ViewportHistory.resize`と#91のinput ownershipは維持する。保存と選択は元のworld座標、hit判定は既存の実表示boundsを用いる。Activity再生成・window focusは別経路として検証する。
- native IME/insets、canvas測定、world focus/pan、固定要素位置を同時期に観測する。empty cancel、Done、Back/確認、既存編集、反復5回と内容/Room/Undo/historyを照合する。実機再確認はCIから推定しない。

Constitution I〜V: Issue #104のworld focus復帰と既存Spec006/007/010をauthorityとする。既存Compose入力・insetsを維持し、表示欄の一時位置と保存配置を分離する。依存/Room/schema/CI/launcherを変更しない。旧UX checklistはreviewer-ownedのまま、今回の限定修正継続は利用者承認済み。

### Issue #106: native dispatch境界の補正

PR105のfull CIで確認dialog復帰後のIME readinessが失敗した。mainにも同signatureがあるが、具体的なCI hide/show因果は未確定。observational focusedでCompose frame後のshow時にnative `active=true / accepting=false`を観測したため、frameだけをnative input準備の境界とは扱わない。

既存window ownership → focus → IME control → Compose frameの後に、native `View.post`によるdispatchの完了を待つ。Androidの[window focus後にpostする標準手順](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility?hl=ja)に従い、最後にcurrent requestとnative window ownershipを再判定してから一度showする。取消時はposted callbackを除去する。#91の未完了request寿命、accepted IME Backの取消、session交代、read-only/save block、完了したrequestの再表示禁止を維持する。native polling、retry-show、timeout延長、独自input connection ownershipは導入しない。

責務は既存CanvasScreenのinput request内に閉じる。診断用interceptor/logを除去したfocusedと全native suite、final head CI/canonicalを検証する。利用者からMERGE_READYまでの継続と条件達成後のmergeが許可された。
