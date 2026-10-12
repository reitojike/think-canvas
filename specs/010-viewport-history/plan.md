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

## Issue #156: J4の下位層coverageとAndroid保持境界

[監査J4](https://github.com/reitojike/think-canvas/issues/151#issuecomment-6111312983)と
[#118 J4/L24・Slice D](https://github.com/reitojike/think-canvas/issues/118#issuecomment-6032824867)の残余を扱う。
基準mainは `71df1e1a2628f19b4921267f15052d927cf59c7d`。FR-005/006/007、SC-002/003の製品契約を変更しない。
今回のJ4は旧matrixのtext／rectangle／free arrow／pen inkの4-familyに限定する。
imageは元matrixにも追加2本にも含めず、このPRのcoverage対象外とする。
現在のBoardSnapshotにはimageもあるため、この旧4-familyの証拠をSC-003の全要素familyの成立とは扱わない。
既存JUnitへ不足2本を先に追加し、既存のaffected IDs、bounds選択、Back/Forwardは重複追加しない。
text boundsは明示fixture値であり、Android実測geometryの証明には使わない。

### 元Android assertionの対応表（実装前）

元 `ViewportHistoryInteractionTest#everyFamilyDeletionAndMultipleUndoTargetsUseOneDisplayNavigation`
L708–762の各assertionを以下へ対応付ける。Dは追加する
`deletionUndoRedoKeepsDisplayTargetsForEveryFamily`、Mは追加する
`multipleUndoTargetsUseRestoredBoundsForFit`。行番号は基準mainを指す。

| 元assertion | 下位層authority／不足coverage | 縮小する場合の保持Android oracle |
| --- | --- | --- |
| L711 対象delete成功 | D: 独立BoardState、exact削除snapshot、canUndo/canRedo | textの既存delete入力前提 |
| L714、L719 visible Undo/Redoのcamera不変 | stateだけでは証明しない | textの両sameFocus |
| L715、L725 Undo後exact initial | D: 4-family Undo復元、M: move Undo復元 | textのvisible/offscreen exact snapshot |
| L716、L720 保存1→2 | JVMから保存数を推定しない | textのvisible Undo/Redo保存数 |
| L718、L734 Redo後initialと異なる | D: 対象だけ削除したexact snapshotへ強化 | textのexact削除snapshot |
| L721–723 native pan前提 | JVMからnative入力を推定しない | textの既存4回pan、distant取得 |
| L726、L735 保存3→4 | JVMから保存数を推定しない | textのoffscreen Undo/Redo保存数 |
| L727、L736 offscreen Undo/Redoでcamera移動 | D: Undo後／Redo消失前の対象bounds | textのassertNotEquals |
| L729、L738 各1回の視点Backでdistant復元 | 既存ViewportHistoryTest L31–59: 往復とrestore非記録 | textの両sameFocus、実UI結合 |
| L730 canRedo、L731 内容不変、L732/L739 Back後保存不変 | D: Undo後canRedoとexact snapshot | textのBack後canRedo／内容／保存数 |
| L743 moveSelection(ids,90,40)成功、L744–746 native pan前提 | M: 同じmove量、2対象、exact moved snapshot | multiの既存moveと4回pan |
| L748 Undo後exact initial | M: 復元とcanRedo、未変更要素除外 | multiのexact snapshot |
| L749–756 実canvas/densityで両bounds包含 | M: 復元後boundsを既存fit/worldToScreenへ渡し固定canvasで包含 | multiの実測canvas/densityによる包含 |
| L759 1回のBackでdistant復元、L760 内容不変、L761 保存1回 | M: fit計算前後の内容／Redo不変。既存Back/Forward | multiのsameFocus／exact snapshot／保存1回 |

既存 `ContentHistoryFocusTest` L29–66は旧4-familyのaffected IDsとadded/removed、L99–123は
survivor-after／deleted-before fallback／複数boundsを保持する。D/MはこれらをBoardStateの実Undo/Redoへ接続する不足だけを補う。
CanvasScreen L845–896の実測geometry→focus/fit→navigation→save結合はAndroidに保持する。
FlakyTest(106)付きcaseは通常laneの代替に数えない。

positive JVM2本、関連history/geometry、基本検証と既存suite validatorを順に確認する。
negativeはbefore fallback除去、multi boundsを1対象、pending history focusだけrecord=falseの最大3件。
mutationは一時ローカルのみで復元し、commit/pushしない。Android negativeが実行不能なら
Android matrixとmanifestを保持し、JVM追加だけのDraft PRに限定する。
縮小採用時だけtext/multiの2 identitiesをfull_onlyへ置き、既存exact-method経路のfresh XML／receipt／
source identity／counters／deviceを確認する。production、Gradle、依存、workflow、既存helperとframeworkは変更しない。
速度・不安定率の改善は測定していないため主張しない。mergeは独立レビュー後の判断とする。

### 実行結果と採用境界

初回focused JVMは追加2 identitiesだけのfresh XML **2/0/0/0**。XML SHA256は
`1bf70a139e4826aa95db5885ce25fbb6cec0dc8a8966277c10ca6bf3b37d95c0`。
以下の一時ローカルnegativeは各1回だけ実行し、両方とも狙ったassertion failureを検出した。

| control | exact JVM identity／失敗位置 | tests/failures/errors/skipped | XML SHA256 |
| --- | --- | --- | --- |
| before fallback除去 | D: Redo後の消失前text boundsが空、L178 | 1/1/0/0 | `34ab6176ed7321be893436d6cc6149b455bb1515b59c0ee961d1c29bead334af` |
| multi boundsを最初の1対象だけへ限定 | M: ink復元bounds欠落、L209 | 1/1/0/0 | `169b8f67e8899f425157ee1d72f15a6817374205dfabfbc4e254516ae8953fb0` |

両control終了時に `ContentHistoryFocus.kt` を元bytesへ復元し、SHA256
`4bc87e6ebbe0be8d38575d2645083f047498921c26566de7746af1c15c959c1a` とproduction差分0を確認した。
復元後のContentHistoryFocus／ViewportHistory／SpatialGeometry／InkElement／BoardGeometryは **52/0/0/0**。
既存suite validatorはsmoke67／non-quarantined230／quarantine38／full268でPASS、script testsは13/13。
script tests初回はsandboxの一時領域への書込み権限で停止し、書込み可能な作業用一時領域へ限定して実行した。
test helperやrunnerの変更はない。

pending history focusのnavigationだけrecord=falseにするAndroid negativeは未実行。
[#67の既知Windows runtime制約](https://github.com/reitojike/think-canvas/issues/67)に対する新しい解消証拠がなく、
同じadb/GMD probeを繰り返さない。remoteへproduction mutationをpublishする代替も採らない。
したがって今回は**JVM不足2本だけを採用し、Android縮小は保留**する。
元Android matrix、全assertion、入力／待機／IME helper、annotation、manifestのidentityと分類はそのまま保持する。
text/multiの新Android2 identitiesはまだ作らず、同等性成立や移管完了を主張しない。

local lint／全unit／assembleは成功、全unitはfresh XML **191/0/0/0**（25 classes）。
公開境界326候補、diff check、Room schema差分0を確認した。
最終採用差分は `ContentHistoryFocusTest.kt`（103行追加）、本planとtasksの3 files。
production、Android test、manifest、Gradle、依存、workflow、既存helperの差分は0。
候補freeze後のexact-head CI、保持した元Android identityのtargeted evidence、canonical reviewはDraft PRへ記録する。
