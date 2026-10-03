# 実装計画: ドラッグ中のedge auto-pan

**Branch**: `codex/issue-78-edge-auto-pan` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

## Summary

成立したmove / handle MOVEだけに、一時的なmove sessionとCompose frame clockによるviewport panを追加する。pointer/frame/releaseの差分は同じworld anchorで求め、既存translatedSelectionのpreviewとBoardState.moveSelectionの一回commitを維持する。新しいsave/geometry authorityやgesture coordinatorを作らない。

## Technical Context

- Kotlin、JDK25、Java target17、Android API26〜37。既存Compose BOM 2026.09.00、Activity1.13.0、Room3 3.0.3、Ink1.0.0を維持。
- Storage: 既存BoardSnapshot/Room。UI sessionとviewportは保存schemaに追加しない。
- Testing: JUnit、Compose instrumentation、既存Windows GMD launcherとCI Pixel9/API37。frameのdeterministic testはmanual Compose clock。
- Scope: CanvasScreen、pureなEdgeAutoPan helper、unit/instrumentation、Spec008/README。依存・workflow・schema更新なし。
- Performance: active moveのframeごとにO(1)の速度/差分計算。BoardSnapshotのpreviewは既存描画だけ。一回のreleaseだけrecord/saveする。
- Constraints: local-only、倍率15〜300%、world配置保持、既存save guard/accessibility、Process #36。

## Constitution Check

| 原則 | 確認 |
| --- | --- |
| I authority | Issue78とspecに観測契約、ここに技術判断を記録。PRD/モックを公開しない。Spec007 WIPを現行authorityにしない |
| II standard-first | Compose frame clock/LaunchedEffectと既存pointer ownerを使用。独自gestureや常設chromeを増やさない |
| III 空間配置 | viewport.panは内容非変更。preview/commitのworld差分を共有、包含/矢印は既存translatedSelection |
| IV local-first | BoardState/BoardSessionViewModel/Roomへ既存の一回commit。ネットワーク機能なし |
| V spec先行 | specify/clarify後にこのplan、checklist/tasks/analyzeを作り、#73のmerge後にcurrent censusを再確認してからcode |

Phase0/Phase1の設計は同じ責務に閉じ、違反なし。技術調査は下位モデルの独立read-only調査と公式AndroidX APIを照合した。prototypeの値は調整可能な設計値で、scopeや停止・保存の契約を変えない。

## 開始時のfinite authority census

調査baselineはmain `600d426ed94b345692fe35f9393f0bdeae33444a`。以下は計画時のinventoryであり、実装前に#73 merge後のSHAと差分を追記する。

| family | admission/owner | #78判断 |
| --- | --- | --- |
| text/shape/region/inkの長押しmove | longPressPending→touchSlop→move、CanvasScreen main pointer loop | 成立後だけ対象。対象IDsを固定 |
| selected text grip | selectedGrip→move、既存touchSlop | 同じmove sessionへ統合 |
| shape MOVE handle | handle MOVE、既存pointer loop | slop超過後だけauto-pan。tapは既存zero move |
| multi selection/region包含/矢印 | translatedSelection、BoardState.moveSelection | 一回のdelta/commitを使用。別の包含計算なし |
| normal pan/zoom | tap→pan、2本指→zoom | 対象外。2本指化でmove sessionを同期取消 |
| touch/stylus ink | drawingKind優先、inkPreview | 対象外。stylus優先へ移る際にもmove session取消 |
| create/lasso/gap | spatialPreview/lassoPoints/gapPreview | 対象外 |
| resize/arrow FROM/TO/BEND | handle preview、既存arrow geometry | 対象外 |
| text/region/modal/chrome | admission guards、focus/bounds | move session開始不可 |
| Back/save/lifecycle | #73終了primitive、live saveState、Composition/Lifecycle | 同期owner失効＋frame/preview cleanup。内容を自動確定しない |

hard dependencyは同じpointer owner/preview cancellationに触れることだけ。#73をmerge済みcurrent authorityとして取り込み、Task ContractのPO stop boundaryを維持する。

## 設計

1. `EdgeAutoPan.kt`にcanvas寸法、pointer screen位置、density、profileから有限速度を求めるpure helperと、Viewport.screenToWorldからworld deltaを求めるhelperを置く。bandは各辺48dpを第一prototype候補とし、幅/高さの1/4以下へclampして中央停止帯を保つ。normalized proximityは0〜1、速度はquadratic。第一候補A:48dp/360dp毎秒、比較候補B:64dp/540dp毎秒。ユーザーsettingsを作らず、候補比較の結果をこのplanへ追記して採用する。
2. UI-local move sessionはimmutable IDs/world anchor/generationとlatest pointer、active/cancelledを持つ。pointer handlerはDOWN anchorを記録し、成立後にownerを作る。frame jobはrestricted AwaitPointerEventScopeの外、CompositionのLaunchedEffectで動かす。Frameごとに現在ownerのidentity/世代/guardを再確認する。
3. `withFrameNanos`の時刻差を積分する。最初のframeは時間基準だけ設定、dtは0〜50msへclampし、停止/再開の大きな時間差を飛び移りにしない。右/下edgeはpanX/panYを負へ動かしてその方向のworldを見せる。pointer→world変換はpan後に行う。
4. pointer更新、frame更新、UPは同じ`currentViewport.screenToWorld(pointer) - downWorldAnchor`を使う。screen delta/scaleへcamera deltaを加算する方式を残さない。move/handle MOVEのpreviewとreleaseを同じdeltaへ揃える。contentsはframeごとに変えず、releaseだけ既存moveSelection/saveSnapshotを一回呼ぶ。
5. UP/CANCEL/2本指/stylus takeover/Back/save blockはownerを同期失効させる。frame側とRelease側は失効後に更新・commitできない。#73のlive gesture generation guardとfinishToolInteractionをcleanupとして再利用する。Composition破棄とLifecycle STOPで継続を停止し、resume/recreationでownerを復元しない。finallyはそのgestureのownerだけを除去する。
6. 対象外gestureにはownerを作らず、既存geometry、save/Undo、consume/handoff、accessibilityを維持する。viewport animationは既存DOWN時cancelを使用する。

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/EdgeAutoPan.kt`: bounded pure geometry/速度、UI-local move session。
- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: frame owner、move/handle MOVEへの統合、同期終了。
- `app/src/test/java/com/thinkcanvas/canvas/EdgeAutoPanTest.kt`: 四辺/角/中央/小画面/速度上限/dtとworld差分。
- `app/src/androidTest/java/com/thinkcanvas/canvas/EdgeAutoPanTest.kt`: stationary pointer、preview/commit、multi/region、停止/handoff/lifecycle、次の操作。
- `specs/008-drag-edge-auto-pan/`: spec/plan/research/data-model/contracts/quickstart/checklists/tasks。

## 検証とprototype

pure検証は倍率.15/1/3、密度1/3、pointer移動とcamera移動の単独/組合せ、dt分割、small canvas、四辺/角の単調性と上限を確認する。instrumentationはframe clockをmanualにし、stationary pointer保持でも固定要素だけがpanで動き、対象はfinger offsetを保ち、releaseまでBoardState不変であることを確認する。ticker稼働中のwaitForIdle依存を避け、停止後にidleを待つ。

Pixel9相当で候補A/Bを有限の右/下/角/中央復帰、複数選択dropの操作に比較する。one gestureで幅/高さを超える移動、中央復帰/停止の誤確定0、指とのずれ、制御しやすさを記録する。実機固有のUX判断はparent81へ分け、PO判断が必要になればdependentな実装を止める。

## Complexity Tracking

違反なし。既存の大きなpointer handlerは全面rewriteせず、移動の差分とframe lifecycleだけをboundedに追加する。保存・履歴・囲み包含の責務は既存ownerに残す。