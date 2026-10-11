# 実装計画: 通常キャンバスへの復帰

**Branch**: `codex/issue-73-neutral-state` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

## Summary

CanvasScreenの有限なexit判定・cleanupを共通化し、Backのeditor破棄確認を追加する。Spec 006のoutside pointer admissionと保存authorityを保持する。

## Technical Context

Kotlin / Compose / Material 3、Android API 26〜37、JDK 25。既存dependencyを使用し、Room schema/Gradle/workflowは変更しない。BoardStateが内容・履歴・geometry、BoardSessionViewModelがsave/retry/ackを所有する。既存TextEditorSessionに囲み名editorを保持し、小さいtool/確認targetはrememberSaveable、確認はAlertDialogを使う。

## Constitution Check

I: #73とPO判断をspecに記録。II: BackHandler/AlertDialog/state preservationを優先。III: cleanupはworld配置・保存・履歴を変えない。IV: network/AIなし。V: spec/plan/tasksを先に確定。設計後も全項目PASS、標準例外なし。

## 開始時read-only census

authority: main `600d426ed94b345692fe35f9393f0bdeae33444a`、#71完了報告とPR #75 final head `38ce3c71da32e0fc068e423e180f5b2255533462`。final CI/editor21・total87、canonical clean、post-merge closureを確認。

| family | state / owner | entry / finalizationと判断 |
| --- | --- | --- |
| text editor | TextEditorSession.draft/pending ack/new id | blank/再編集、Done/Cancel/outside。#71保持、Backだけ追加 |
| region editor | regionNameId/name、UI remember | create/再tap/menu/accessibility、Done。同process sessionへ移しBack確認追加 |
| spatial one-shot | tool/spatialPreview | 4種類は既にUP/accessibility成功でNONE。共通cleanupへ接続 |
| lasso | tool/lassoPoints | 完了で既にNONE、selection保持 |
| ink persistent | inkTool/inkPreview/local gesture | stroke毎継続。Back/やめるでpreview取消 |
| move/handle/gap | 各previewとlocal gesture mode | UP確定/finally取消。Backでpointer continuationをcancel |
| context/selection | menuTarget/selectedIds/selectedId | Backでmenuを先に閉じ、別段階でselection解除 |
| search | searchOpen/query/position/animation | Backでsearchとfocus/IMEをcleanup |
| tools popup | toolsExpanded | Backで閉じる、既存DisposableEffectのbounds除去維持 |
| modal | attachmentEditor、外部share/guide | 標準dialog/別page authority保持 |

pointer順: editor Initial-pass outside → main draft/save/chrome guard → stylus/ink → spatial/lasso → handles/move/tap。previewはUI、確定はBoardStateが所有する。focusはtext/region/searchから要求、exitで共通解除。editor field/toolbarはDisposableEffect、menu/region/search/guidanceはeffect、tools/shareはDisposableEffectでboundsを除去する。

既存coverage: TextEditorDismissalTest21、InkGestureTest、LongPressGestureTest、SemanticNavigationTest、ConditionalChromeLifecycleTest、保存・board lifecycle・export。#74は独立scopeで再開しない。

## Project Structure

- `app/src/main/java/com/thinkcanvas/canvas/CanvasScreen.kt`: ordered Back、確認、cleanup、pointer cancellation。
- `app/src/main/java/com/thinkcanvas/canvas/TextEditorSession.kt`: RegionNameDraftと変更判定。
- `app/src/main/res/values/strings.xml`: confirmation文言。
- `app/src/test/java/com/thinkcanvas/canvas/EditorExitTest.kt`: 変更判定。
- `app/src/androidTest/java/com/thinkcanvas/canvas/NeutralInteractionTest.kt`: Back/editor/tool/save/recreation。

## 設計と依存順

1. textは保存済み要素とのtext/kind/color、regionはoriginalNameとの差分を判定する。確認targetはfamily/sessionIdにbindする。
2. requestEditorExitはlive guard→確認/closeだけを扱う。確認前にも入力focus/IMEを解除し、入力とsessionを保持する。text/regionの自動focusは確認targetがない時だけ受理し、確認再生成で背後のIMEを再表示しない。確認解除時は編集focusへ復帰する。text/regionでfocus/IME cleanupを共有し、保存は扱わない。
3. tool exitは世代を同期更新してpreviewを消す。pointerInput handler世代とDOWN時世代を一致させ、各event/Releaseでlive世代を照合して古いUPのcommitを拒否する。key変更によるcoroutine cancellationは後続cleanupとして併用する。one-shotとink連続描画を維持。ink blank tapはstrokeなのでexitにしない。
4. Backはspecの有限順で一段階だけ処理。IMEはplatformに委ね、app callbackでIME表示時もIMEだけ閉じる。
5. unit/lint/debug/androidTest build、focused/full instrumentation、public/diff/schemaを確認し、final headのCI/review/thread/baseをProcess #36で収束する。

## Complexity Tracking

Constitution違反なし。generic coordinator/callback bagは作らず、2 editorのadmission/cleanupとspatial/inkのpreview cleanupだけを共有する。

## Issue #112: palette outside dismissal

変更責務はoutside dismissal・pointer ownership・gesture admissionだけ。`CanvasScreen.kt` のmain DOWN/blank arbitration/mode/Release、launcher callback、live chrome boundsを対象とし、text/region/image session・Back・save・Ink・history/layoutの既存authorityを維持する。

標準Compose `awaitEachGesture` / `consume` と既存native release・世代guardを使う。editor/modal/save/chrome guardの後、neutralな1本指Touchをpalette tap候補にする。pending blankを取消し、正常short UPだけでcollapseとbounds解放を行う。slop超過は元のpan/move/handleへ、追加指・stylusは既存pinch/inkへ渡す。region併存の既存outside admissionは、BlankTapがcaptureした展開状態とlive状態の一致で保持する。launcher開閉は古いpointer continuationを同期失効する。

標準Popupの別window/outside DOWN dismissalはshort-UP限定と展開中のviewport history操作に一致しないため採用しない。既存layout・button・accessibility clickを保持する。根拠: [Compose標準gesture API](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)。generic frameworkは追加しない。

長押しhandoff補正は、`canvasMode == "tap"` のpalette候補だけを既存tapと同じtimeout待機へ入れる。timeout後の`longPressPending`、pickup/haptic、menu/選択追加、move/gap、native release・世代・取消・第二指/stylus・cleanupを再利用する。明示grip/handleのtimeout admissionは変えない。新状態機械・別detector・待機時間を追加しない。既存`LongPressGestureTest`のnative fixtureとassert本文を通常/展開状態で共有し、要素hold→UP、要素hold→drag、blank hold→dragをPR回帰にする。palette保持・viewport不変と既存の取消・保存・Undo/Redo・振動assertを維持する。

明示ownerの補正は初期modeのpalette上書きを`canvasMode == "tap"`へ限定する1行だけ。既存hit優先・threshold・timeout/handoff・native取消・世代・第二指/stylus・保存authorityは変更しない。最小回帰は既存`withBoard(image=true)`とnative fixtureによる画像resize一件とし、callback未指定で描画されない画像追加行を要求せず、実際の七palette行・他chromeのgeometryと入力非重複を確認する。Window.Callbackで委譲前にscalarを記録してfinally復元し、全配送MOVE/historyがslop内、FINGER/SOURCE_TOUCHSCREEN、正常UPを確認する。durationは記録だけとする。幅増加を第一製品assertとし、identity/asset/alt/origin/aspect・他content・viewport・palette保持・一回のmatching保存まで検証する。取消previewや全handle matrixの動的証明には広げない。

同一test bytesの旧幅RED・補正全GREENを対応付ける。過去の広範囲候補と入力前提失敗は保全し、製品REDには流用しない。利用者が明示承認したPRスコープ内の小さなfixture補正・再検証は、各試行の証拠分類と仕様/Android/テスト担当の実コード協議を根拠に進める。blind retry・assert緩和・待機追加は行わず、別familyの製品修正・大幅な複雑性増加・原因不明で進展がない場合はHOLDする。

PR smokeはoutside短tap/次tap、palette展開時の世代、region editor priority、native control/旧boundsの代表回帰。main regressionは取消・長押し・pan・pinch・stylusを独立初期状態で検証する。#107の既存回帰を維持し、text/image/Backは既存回帰とadmission guardで照合する。native fixtureはlive canvas/chrome/system gesture inset、tool/source/ID/time、正常releaseと保存をassertする。FLAG_CANCELEDはACTION_POINTER_UP/ACTION_CANCELの正式contractで扱う。

検証はcurrent-sourceの期待identity集合、receipt、fresh XML/counter/device、現行headのCI/reviewと実機証拠を分ける。過去の診断と補正履歴は[Issue #112](https://github.com/reitojike/think-canvas/issues/112)で保持する。

## Canonical review後の技術補正

全CI成功後も、[P1 review](https://github.com/reitojike/think-canvas/pull/86#discussion_r4173360329)でrecomposition前のqueued Releaseが確定できる不足を確認した。gestureの内容・保存authorityは変えず、DOWN admissionと単一event loopの同期世代guardで全確定familyを保護する。BackとUPを同じUI turnでActivityのtouch dispatcherへ送り、frame待ちで競合を隠さない回帰にする。
