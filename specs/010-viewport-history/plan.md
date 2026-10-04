# Implementation Plan: 視点履歴と編集変更の表示補助

**Branch**: `codex/issue-77-viewport-history` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

## Summary
現在cameraをboard sessionへ移し、world中心/倍率の有限back/forwardを内容履歴と独立に保持する。既存navigationをanimation終了境界で、manual pan/pinchを正常releaseで一度記録する。Undo/Redo差分から画面外の変更対象だけ表示する。BoardStateのChange/保存/schemaは変更しない。

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
