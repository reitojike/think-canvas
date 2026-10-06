# Implementation Plan: 条件付きpan慣性

**Branch**: `codex/issue-102-conditional-fling` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

## Summary

現行pointer handlerに標準VelocityTrackerを追加し、正常UPの速いpanだけを標準spline decayへ渡す。既存ViewportAnimationBoundaryを共有し、frameと停止に同じgeneration/owner guardを使う。

## Technical Context

Kotlin/Compose、BOM2026.09.00/UI1.12.1、min26/target37。既存animation依存内のAnimationState<Offset>/splineBasedDecay、UIのVelocityTrackerを使用。依存・Room schema更新なし。研究agentが現行cache APIと公式sourceを確認済み。新しいphysics/gesture frameworkは導入しない。

## Constitution Check

I: #102と利用者の採用方向、merged Spec010がauthority。II: Android標準速度/減衰、platform min/maxを尊重。precision用の高い開始閾値と弱い上限は自由配置の精密操作を守る例外として限定。III: cameraだけ、content不変。IV: local-only。V: Spec Kit順序を守る。Phase0/1後とも違反なし。

## Design

- screen px/sをそのまま使用。viewport.scaleで割らない。速度vectorの長さがmax(platform minimum,400dp/s)未満なら即停止。方向を保ってmin(platform maximum,1800dp/s)へ上限制限する。標準摩擦の理論値では上限時の追加移動約539dp/856msだが実機値は別に記録する。
- DOWN、slop以前を含む全MOVE、UPをaddPointerInputChangeへ渡す。UPのpause resetにより停止後のreleaseに古い速度を使わない。pinchに入ったらtrackerをresetし、pan以外のmodeではflingしない。
- decayはAwaitPointerEventScopeの外のuiScopeで実行し、release時camera＋標準decayのoffsetだけを反映する。
- existing boundaryへfling flagと開始前focusを移す。正常UPでflingを開始した場合はrelease-time navigation.recordを省き、自然/途中停止で一度recordする。各frameは履歴を触らない。
- stopはgeneration/flagを先に無効化しJobをcancel。queued frameはactive/generation/flag/操作状態/source contentを照合してから代入する。新しいboard/editorへの差替えも拒否する。
- 最初のInitial-pass DOWNでchrome/editor早期returnより前にflingを止める。Back、cancelBlankTap/操作admission、別のviewport animation/history、lifecycle停止、save/editor/tool/source変更も同じ停止境界を使用する。
- existing regular animationの意味、edge auto-pan、gesture admission/slop、save/haptic/contentを変更しない。TalkBack操作は慣性を開始せず、操作開始時は旧慣性を止める。

## Project Structure

CanvasScreen.ktへtrackerとadmissionを統合し、新しいdecay/owner処理をPanFling.ktへ分ける。共有ViewportAnimationBoundaryも同fileに配置する。FlingGestureTest.ktで実Activity、Room、native入力、表示履歴、保存回数を観測する。specs/015配下が本featureのdesign authority。

## Verification

低速、高速、停止後release、cancel、touch、pinch、Back、editor、tool、lifecycle、historyをnative入力で確認する。遅延frameと表示位置・保存内容は独立して観測する。focused GMD、lint/unit/build/public boundary、全CI、最終head review後merge。実機比較はAPKを配布して記録し、未実施ならIssueをcloseしない。

## コンパイル上限のcheckpoint

初期統合はCanvasScreenのJVM method size上限（64KiB）でcompile失敗した。surfaceを新規decay、source/lifetime guard、既存animation boundaryとadmissionに限定してread-only確認した。authorityと責任はcamera-onlyのままで、BOUNDED_CORRECTIONとして新規処理を専用composable/controllerへ分離する。既存gesture frameworkや保存を再設計しない。検索中はeditorとして慣性開始を除外し、window focus・surfaceサイズ・selection変化もstale frameを拒否する。fling中は履歴controlsのspaceを予約し、停止DOWNでtool button位置が変わらないようにする。

専用controllerへの分離後も同じmethod上限でcompile失敗したため、3回目の補正前にfamily checkpointを再実施した。対象はCanvasScreenのCompose生成コード、新規motion組立、既存zoom表示leaf、停止/履歴/測定の有限surface。BOUNDED_CORRECTIONとして表示移動に属するzoom pillの描画だけを同fileのprivate composableへ抽出し、label/style/位置/semantics/callback/boundsを維持する。別責任の画面全体refactorや新しいframeworkは行わない。

zoom leaf抽出後も上限が再現したため、追加補正前に再checkpointした。変更前jarのjavapでCanvasScreenの最終offset64876を確認し、上限まで約659byteしかない。local cancel/stop関数のcaptureが既存多数のcallbackへ伝播する構造が原因と判断した。BOUNDED_CORRECTIONとして停止処理そのものを既存ViewportAnimationBoundaryへ移し、navigationをそのremember寿命へ固定する。generation/record/stopの順序と既存regular animationの意味を維持する。責任は同じcamera停止/履歴であり別featureへ分離しない。

停止境界抽出後はcompile成功したが、実測offset65506で上限まで29byteとなる。再checkpointで表示移動に属するback/forwardの描画leafを有限scopeとして追加抽出し、同じ48dp領域/label/callback/boundsを維持して生成コードの余裕を確保する。これはcamera/history責任のままのBOUNDED_CORRECTIONでありUIの意味を変えない。慣性coroutineは標準Foundation同様1xを指定しsystem scale0のジャンプを防ぐ。

## native toolbar fixture checkpoint

tool testは初回overlapping Gradle実行でownership failureとなり、その証跡は合格に使わない。単独診断でstopとnative tool操作を観測したが、NONE時に存在しないclose labelを期待していた。CanvasControlsの有限tool listはNONEを含まず、同じopen labelの＋でexpandedをtoggleする。BOUNDED_CORRECTIONはtestのみ、popupの四角ノードを確認し同じ＋で閉じる。editor fixtureは現在のcameraから可視位置を計算し、IMEの正当なrevealとstale flingを混同しない。以後GMDと基本検証を直列実行する。
