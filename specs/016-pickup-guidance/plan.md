# Implementation Plan: 長押しpickupの案内

**Branch**: `codex/issue-103-pickup-guidance` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

## Summary

採択済みpickup-firstを、gesture寿命の一時案内と画像のpickup描画で実装する。既存release/menu・drag/move/gap、selection、保存、Undoを維持する。#102 merge後のmainを基準にする。

## Technical Context

- Language/Version: 既存Kotlin/Gradleの固定版を維持。
- Dependencies: 既存Compose、標準ViewConfiguration、LocalHapticFeedback、semantics。追加依存なし。
- Storage: 既存Room。pickupは保存しない。
- Testing: LongPressGestureTestのnative入力、画像描画観測、既存Android full回帰、lint/unit/build。
- Target Platform: Android API37。実機Pixel 9a / Android17。
- Project Type: Android mobile app。
- Performance: フレームごとの通知を追加せず、成立/終了でのみ案内を更新。
- Constraints: CanvasScreenのJVM methodサイズに余裕が少ない。必要なら案内表示だけprivate leafへ分ける。
- Scope: 全element、blank、集合、選択追加、取消。generic gesture frameworkやresource変更を含めない。

## Constitution Check

設計前・設計後とも適合。公開Issueと現行specの操作契約を根拠とし、非公開資料を追加しない。空間配置と保存はpreviewから変更しない。localのみ、AI不要。

Android標準は成立時にcontext menuを開くが、canvasではrelease/menuとdrag/moveを分岐する既存契約を守る限定例外を採る。標準timeout/slop/hapticを維持し、pickup案内だけpolite live regionとする。既存accessibility custom actionsを維持する。

## Project Structure

- `specs/016-pickup-guidance/`: spec、plan、research、data-model、contracts、quickstart、checklists、tasks。
- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: 案内とgesture寿命。
- `app/src/main/java/com/thinkcanvas/canvas/ImageElements.kt`: moving枠。
- `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt`: native回帰。
- `app/src/androidTest/java/com/thinkcanvas/canvas/{OffscreenIndicatorsTest,ViewportHistoryInteractionTest}.kt`: 停止位置を期待するpan helperは静止後releaseとし、現行fling契約と区別する。

既存generation/owner guardを再利用し、pointerInputのkeyには案内stateを加えない。

## Complexity Tracking

新永続entity/service/gesture abstractionなし。サイズ制約が実際に発生した場合は有限の表示責務だけ抽出する。
