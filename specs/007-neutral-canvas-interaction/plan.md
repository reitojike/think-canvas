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
2. requestEditorExitはlive guard→確認/closeだけを扱う。確認前にも入力focus/IMEを解除し、入力とsessionを保持する。text/regionでfocus/IME cleanupを共有し、保存は扱わない。
3. tool exitは世代を同期更新してpreviewを消す。pointerInput handler世代とDOWN時世代を一致させ、各event/Releaseでlive世代を照合して古いUPのcommitを拒否する。key変更によるcoroutine cancellationは後続cleanupとして併用する。one-shotとink連続描画を維持。ink blank tapはstrokeなのでexitにしない。
4. Backはspecの有限順で一段階だけ処理。IMEはplatformに委ね、app callbackでIME表示時もIMEだけ閉じる。
5. unit/lint/debug/androidTest build、focused/full instrumentation、public/diff/schemaを確認し、final headのCI/review/thread/baseをProcess #36で収束する。

## Complexity Tracking

Constitution違反なし。generic coordinator/callback bagは作らず、2 editorのadmission/cleanupとspatial/inkのpreview cleanupだけを共有する。

## Canonical review後の技術補正

全CI成功後も、[P1 review](https://github.com/reitojike/think-canvas/pull/86#discussion_r4173360329)でrecomposition前のqueued Releaseが確定できる不足を確認した。gestureの内容・保存authorityは変えず、DOWN admissionと単一event loopの同期世代guardで全確定familyを保護する。BackとUPを同じUI turnでActivityのtouch dispatcherへ送り、frame待ちで競合を隠さない回帰にする。
