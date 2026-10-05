# Implementation Plan: 操作の意味に合わせたハプティクス

**Branch**: `codex/issue-101-semantic-haptics` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

## Summary

CanvasScreenの6 call siteに限定。長押し成立のLongPressを維持し、create共通成功とnative端点変更成功にConfirmを使う。ink、region出入り、gap thresholdのLongPressを除去する。

## Technical Context

Kotlin / Compose compiler 2.4.20、現行BOM 2026.09.00 / UI 1.12.1、AndroidX Core 1.19.1。Android min26/target37のmobile app。依存・Room schema変更なし。native MotionEventとLocalHapticFeedback recorderで種類・回数・操作結果を観測する。gesture/frameごとの処理追加なし。

## Constitution Check

I: #101と既存merged specを出発点にfeedbackのみ上書き。II: Compose標準semanticとCore fallback。III: 配置不変。IV: 権限/network追加なし。V: Spec Kit順序を守る。Phase0/1前後とも違反なし。

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: feedbackのみ。
- `app/src/androidTest/java/com/thinkcanvas/canvas/LongPressGestureTest.kt`: recorderを既存sessionに差し込み、native操作と保存/Undoを観測。
- `specs/014-semantic-haptics/`: census、policy、仕様、検証記録。
- `specs/002-spatial-organization/contracts/interaction.md`: 新policyへの参照。

## Design

LocalHapticFeedbackを維持。Confirmは現行版で使用可能。Core標準fallbackに任せ、VibratorやAPI level分岐を追加しない。shape/region/arrowのcreated共通成功でConfirm一度、arrow内の旧feedbackは除去。endpointは既存changed境界を維持する。

inkのboard.addInkStrokeとsaveSnapshotを従来通り一度呼ぶ。regionのtransition計算と案内を残す。gapのthreshold・previewは維持。操作判定・保存順序・geometry/Undo/save authorityを変更しない。

## Verification and delivery

focused regressionでlong-press4経路、作成成功/失敗、touch/stylus strokeを観測。既存spatial/edge/ink回帰でregion/endpoint/取消を補う。lint/unit/debug/androidTest compile、公開境界、full CI GMD、現行head canonical review後merge。Pixel9a/Android17実機評価は利用者結果を別途記録し、未評価ならIssueをopenに保つ。
