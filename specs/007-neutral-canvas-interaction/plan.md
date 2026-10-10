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

## Issue #112 bounded design / read-only census

開始mainは `71849bd94005ce986bcf3a884064e97b0df7b19b`。Issue #112、完了済み#107、Process #36、現行Spec006/007/009/010/015をauthorityとする。変更責務はoutside dismissal・pointer ownership・gesture admissionだけ。

有限surfaceは `CanvasScreen.kt` のInitial text outside loop、main DOWN/blank arbitration/mode/Release、launcher callback、live chrome hitとpalette dispose。標準modalは別window、image editor/pickerはmain admissionで拒否、regionは従来canvas admissionを保持する。text/region/image session・Back・save・Ink・history/layoutを変更しない。

標準Compose `awaitEachGesture` / `consume` と既存native release・世代guardを使う。mainのchrome判定後、editor/modal/save guardが許可したtouchだけをpalette tap候補にし、blank arbitrationより前にpendingを取消す。候補ではlong-press actionとcanvas tapを生成しない。slop超過では元のpan/move/handle modeへ、追加指/stylusでは既存分岐へ渡す。正常short UPだけでcollapseし、palette boundsを同期removeする。

第1ラウンドのbounded補正では、region editorとpaletteが併存する既存outside admissionを保持するため、`BlankTap` に展開状態をcaptureし、live状態との一致を照合する。無条件の `!toolsExpanded` 拒否は採用しない。launcherの開閉では既存pointer世代も同期失効し、開く前のDOWNや再展開前の古いUPが新しいpalette/canvas actionを所有しないようにする。native回帰ではregion併存、途中DOWNから展開、panの視点変化とpinchのscale変化を確認する。

標準Popupの別window/outside DOWN dismissalはshort-UP限定と展開中のviewport history操作（#107）に一致しないため採用しない。既存layout・48dp button・accessibility clickを保持する。根拠: [Compose gestureの標準APIとconsume](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)。新framework/dependency/CI/quarantine変更なし。Constitution I〜Vと公開境界を維持する。

AC1/2/5/6はnative外側tapと次gesture、AC3/4/9はnative/semantics controlと既存Back、AC7は既存editor回帰とlive priority、AC8は非tapのnative入力で照合する。AC10は責務ごとのfocused identityを追加しinventoryを同期する。AC11は実機、AC12はfresh PR basic/smoke66とmain basic/non-quarantined224、AC13はexact-head canonical reviewで別々に証明する。

## Canonical review後の技術補正

全CI成功後も、[P1 review](https://github.com/reitojike/think-canvas/pull/86#discussion_r4173360329)でrecomposition前のqueued Releaseが確定できる不足を確認した。gestureの内容・保存authorityは変えず、DOWN admissionと単一event loopの同期世代guardで全確定familyを保護する。BackとUPを同じUI turnでActivityのtouch dispatcherへ送り、frame待ちで競合を隠さない回帰にする。

## Recovery: round 2 のScope Integrity Gate

最初の有効Windows GMD sampleはexact1件・failure1・source unchanged。新設outside element tapのfixtureが既存の簡易MotionEvent.obtainを使用し、API37/AOSP sourceではTOOL_TYPE_UNKNOWNのまま生成されることを確認した。productionは1本指Touchだけをdismissにadmitするため、fixtureが正常fingerのoracleになっていなかった。round 2は新設native regressionのshort tap/panを既に追加済みのTOOL_TYPE_FINGER/SOURCE_TOUCHSCREEN fixtureへ限定して置換する。既存tap/pan helperや#123/#124 harness、production・authority・suite identity・quarantineは変更しない。frozen clock下では既存の明示frame advanceを保持し、追加fixtureのidle待ちだけをautoAdvance時に行う。根拠: [AOSP MotionEvent](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/MotionEvent.java)。補正予算はround 1消費済みから引き継ぎ、これでround 2。次のmaterial補正前はfamily checkpointを必要とする。

## Family checkpoint後の追加1 round

[Issue112 checkpoint](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6096737676)のBOUNDED_CORRECTIONに従う。新設outside element regressionだけで、native palette button/labelの可視boundsとpaddingを含む保守的envelope、launcher/history/zoomを観測する。native region中心と既存fixtureのshape寸法/現在scaleから有限9点を計算し、canvas内かつchrome外の点だけを選ぶ。候補なしはfixture failureとして数値を出す。前提成立後もdismissしなければ数値診断とdispatch wall timeを残してHOLDし、round4へ進まない。production、既存harness、selector、authorityは変更しない。tap wall timeは正常short UPの証明として扱わない。

## Issue112 Test Contract Redesign

[read-only全assertion mapping / fixture / inventory checkpoint](https://github.com/reitojike/think-canvas/issues/112#issuecomment-6098743250)に従う。#107の2既存identityを保ち、paletteのopening/outside/editor/cancel/long press/pan/pinch/stylus/native controlを10の独立PrSmoke identityへ分離する。private helperはこのclassのboard起動・palette bounds・native入力だけに限定し、#123/#124のharnessを変更しない。source/type/id/pressure/size/intervalはInkGestureTestへ合わせ、normal inputはlive canvas/chromeとsystem gesture inset外をassertする。text取消→pan/pinch→stylusは独立scenarioへ保持する。旧x6%/size0/30msとcancel/長押しstrict redは未解決観測であり、新scenarioのgreenを旧FAIL解消にしない。round1/2/3は消費済み。production byte不変で、新inventoryは66/224/38/262、filterとquarantine membership不変。実機でOS端とapp strokeの競合も確認する。
