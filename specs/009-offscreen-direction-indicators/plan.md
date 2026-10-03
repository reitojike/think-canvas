# Implementation Plan: 画面外対象の方向インジケーター

**Branch**: `codex/issue-76-offscreen-indicators` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Spec009、Issue76とPO承認済みの2対象。

## Summary

CanvasScreenの現在のsearch matchとselectedIdsのrendered boundsから最大2個のUI-local markerを導出する。既存のfocusMatch、fittedViewport、animateViewportを使い、BoardState/Room/historyへ変更を渡さない。

## Technical Context

**Language/Version**: Kotlin 2.4.20、既存AGP 9.4.1 / Gradle 9.8.0 / Java17互換
**Primary Dependencies**: 既存Compose BOM2026.09.00 / Material3。追加・更新なし
**Storage**: 既存Room3、schema・保存path変更なし
**Testing**: JUnit4のpure geometry、Compose/native MotionEvent instrumentation、現行Android CI
**Target Platform**: Android min26 / target37、Pixel9/API37 CI
**Project Type**: Android mobile app
**Performance Goals**: markerは最大2個。pan時に既存geometry参照と選択IDsだけを集約し、全要素の新たな探索・測定を追加しない
**Constraints**: offline、48dp touch、viewportだけの移動、source freeze後に検証
**Scale/Scope**: 検索1件＋選択1group。独立navigation state/settings/historyなし

## Constitution Check

| 原則 | Phase0 / Phase1照合 |
| --- | --- |
| I authority | Issue76とPO承認した有限scopeをspecへ記録。PRD/reference非公開、Spec009はmergeまでWIP |
| II standard-first | 標準clickable/semantics、48dp。minimapではなく条件付きedge表示はIssue76の意図に限定 |
| III spatial | viewportだけ変更。BoardSnapshot・selection・Undo/save不変を検証 |
| IV local-first | 新network/AI/storageなし |
| V spec先行 | specify/clarify/plan/checklist/tasks/analyze完了後にcode、convergeで残差照合 |

設計前後とも違反なし。

## Finite authority checkpoint

baseline main `d33ed2e7b6a5242c5821169c642e169a882c1b52`。#73/PR86と#78/PR87はmerge/closure済み。現行Spec007/008を使用する。

| Surface | Authority / bounded change |
| --- | --- |
| 検索 | currentMatch/searchPosition、既存focusMatchをそのまま呼ぶ |
| 選択 | selectedIdsとResolvedRenderedGeometry.union。単一textは既存focus math、他は既存fittedViewportへ選択だけのgeometry/labelsを渡す |
| 視点 | Viewport.worldToScreen、既存animateViewportだけでnavigation |
| 判定/配置 | 新OffscreenIndicators.kt pure helper。canvasとのinclusive交差判定、safe rectangleへのray projection、同じedge上のcollision回避 |
| chrome | 現行chromeBoundsのtop/bottom control実測から安全な端を作る。marker自身は配置用obstacleに戻さず、現在のlayout rectをpointer admissionで直接読む |
| 入力寿命 | conditional composableとDisposableEffectでbounds登録/cleanup。pointer loopは古い登録を使わず最新derived marker boundsでadmission。callbackもlive guard/identityを再検証 |
| preview/save/IME | editorSession、discard/menu/attachment、tool/inkTool、全preview、saveState/pendingack、IMEの現行guardを使用 |
| accessibility | stable kind key、search→selection traversalIndex、48dp Button role、意味のあるdescription、onClick label。decorative arrowは読み上げ除外 |
| lifecycle | markerはrememberSaveableへ保存せず対象/viewportから導出。既存画面破棄とSTOPのgesture cleanupを維持 |

## 設計

1. 対象はkind/ids/bounds/description。selectedIdsの単一IDがcurrentMatchと同じならselection markerを省く。空/無効boundsは省く。
2. 判定はcurrent viewportでcanvas全体とのinclusive交差。配置rectは左右8dp、上はboard/search/searchButton/guidance/shareSelectionの最大bottom＋8dp、下はhistory/zoom/toolsの最小top−8dp、48dpのcircleを完全に収める。寸法不足時は表示しない。
3. canvas中心→対象中心のrayをsafe rectへ投影。arrowは実際の対象方向。衝突時は同じedge上で最も近い空き48dp領域へずらす。安全な空きがなければ省略。searchを先に配置する。
4. marker rectの最新導出値をrememberUpdatedStateへ渡す。chromeBoundsはonGloballyPositioned/disposalで登録するが、pointer admissionではmarker prefixを除外し最新rectを使う。recomposition後・layout前も古いhitを拒否する。
5. navigation callbackは現在の対象IDs/kind、offscreen状態とinteraction guardを照合する。検索はfocusMatch(searchPosition)。selection単一textはfocus mathを共通化して使い、groupは選択geometryのみの既存fitを呼ぶ。追加camera writer、selection変更、saveなし。

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/OffscreenIndicators.kt`: pure target/projection/collision helper
- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: derived marker / chrome / focus統合
- `app/src/test/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.kt`: geometry matrix
- `app/src/androidTest/java/com/thinkcanvas/canvas/OffscreenIndicatorsTest.kt`: current search/selection、nativepan/pinch、cleanup、保存不変
- `specs/009-offscreen-direction-indicators/`: design/checklists/tasks/validation

## Complexity Tracking

違反なし。大きなpointer handlerをrewriteせず、admissionと条件付きchromeだけ変更する。
