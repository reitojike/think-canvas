# IME readiness family の temporary quarantine

owner は [#106](https://github.com/reitojike/think-canvas/issues/106)、CI architecture は
[#118](https://github.com/reitojike/think-canvas/issues/118)です。
[#125](https://github.com/reitojike/think-canvas/issues/125)は test-only helper commonization を扱い、
root-cause investigation は #106 に戻します。PR #131 の Slice C は実行eventとrunbookの最小同期だけを扱います。

## 導入時の裁定（2026-10-09）

main `c8b3b7a53bff247456acdcc26d604d247f582d95` の source を読み、
「入力可能になった後、actual IME visibility / positive IME inset を wait/assert する test」
を helper の呼び出し経路も含めて個別に裁定しました。現在のexact identitiesは
[既存 receipt の quarantine.expected](../../scripts/android-test-suites.json)に保存します。
これは XML inventory を照合する静的 receipt であり、実行 selector を生成しません。

| Source surface | 根拠 |
| --- | --- |
| NeutralInteractionTest | startNew / startExisting / renameRegion / waitEditor / dialogBack 等からwaitEditorReadyへ到達。native visibilityとpositive bottomを要求 |
| ViewportHistoryInteractionTest | openLowerDraft / search / waitSearchReady / waitEditableImeReady。Undo/Redoの検索再入力待機も含む |
| BoardListScreenTest | renameReloadsTheListAfterActivityRecreationのactual visibilityとnative Save bounds安定待機 |
| EdgeAutoPanTest | editorAndDiscardDialogDoNotAdmitTheMoveTickerのIMM readiness後のactual visibility |
| TextEditorDismissalTest | outsideDoubleTapSlopConfirmsBothSinglesInOrderのsettledCamera(shown=true) |
| OffscreenIndicatorsTest | editorAndCreationToolSuppressIndicatorsAndRestoreOnlyCurrentTargetsのsettledCamera(shown=true) |
| ImageCanvasInteractionTest | descriptionCancelDirtyBackEmptyCommitUndoAndRecreationKeepExpectedTextのfocused dialog visibility待機 |
| ShareImportInteractionTest | editorAndImeKeepTheirDraftUntilTheOriginalEditFinishes / listRenameModalKeepsItsTextUntilSaveThenPresentsTheShareのactual visibility待機 |

dated census、実行件数、再発分布は[#106](https://github.com/reitojike/think-canvas/issues/106)と
[導入PR #133](https://github.com/reitojike/think-canvas/pull/133)の証拠を参照します。
Neutralのtool-only / hidden-only、TextEditorDismissalのfocus-only awaitEditor、
Offscreen/SemanticNavigationのhidden-only cleanupは含めません。クラス単位では除外しません。
annotationはmembership全体でfailureを再現したという主張ではありません。

## 承認済みの縮小（2026-10-30）

#106の既存scheduled観測を見直し、導入時の38 methodsから33 methodsをrequired laneへ戻し、
再発した次の5 methodsだけをmethod単位で隔離します。10/23の34件復帰・4件隔離案は、
後続failureを含めたこの33件復帰・5件隔離の判断で置き換えます。
manifestの`baseline_main`はこの裁定のsource、`review_date`は裁定日です。
新たな調査期限や自動延長を意味しません。

| Class | 隔離を継続するexact method |
| --- | --- |
| com.thinkcanvas.board.BoardListScreenTest | renameReloadsTheListAfterActivityRecreation |
| com.thinkcanvas.canvas.NeutralInteractionTest | backDuringOutsideDownKeepsChangedDraftInConfirmationWithoutCommitting |
| com.thinkcanvas.canvas.NeutralInteractionTest | changedNewTextContinueDialogBackAndOutsideKeepDraft |
| com.thinkcanvas.canvas.NeutralInteractionTest | lateWindowLossDuringInputFrameResumesTextRegionAndSearch |
| com.thinkcanvas.canvas.ViewportHistoryInteractionTest | staleViewActionRejectsSaveEditorToolAndNextGestureUsesCurrentState |

10/30の監査はvalid scheduled 42 runs、32 PASS / 10 RED、失敗は5 methodsです。
直近8 PASSを過去failureの解消証拠にはしません。
5番目の[run40221191770](https://github.com/reitojike/think-canvas/actions/runs/40221191770)は
`changedNewTextContinueDialogBackAndOutsideKeepDraft`のContinue editing後のIME-visible待機timeoutです。
製品原因や保存破損の証拠ではありません。renameの初期待機失敗やpan量の不一致も、
同じ原因と証明された扱いにはしません。原因分類の未達ACと過去REDは保持します。

この変更はannotation、静的receipt、通知、手順の同期だけです。
production、test body、assertion、timeout、retry、採用済み診断は変更しません。
追加dispatch／rerun／新診断／原因調査の拡張は行いません。required laneへの復帰は原因解決や
統計的安定性の主張ではなく、既存観測に基づく隔離範囲の明示判断です。

## 実行契約

AndroidJUnitRunner 1.7.0 の compiled APK discovery と標準 annotation filtering が execution authority です。
[公式Runner reference](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)は
複数filterの積集合を定義します。[FlakyTest reference](https://developer.android.com/reference/androidx/test/filters/FlakyTest)に従い、
method-level `@androidx.test.filters.FlakyTest(bugId = 106)` を承認済みquarantine identitiesだけに付けます。
test body、production、timeout、assertion、retry は変更しません。

| Lane | 起動境界 | Runner arguments |
| --- | --- | --- |
| PR smoke | pull_request、required process gate | annotation=PrSmoke、notAnnotation=FlakyTest |
| main regression | main push / main指定の手動起動、required process gate（PRはjob-level SKIPPED） | notAnnotation=FlakyTest |
| quarantine | default branch schedule / workflow_dispatch | annotation=FlakyTest |
| unfiltered full | android-full.ymlのworkflow_dispatchのみ | filterなし |

完全修飾名は `com.thinkcanvas.test.PrSmoke` / `androidx.test.filters.FlakyTest` です。
「full」はunfiltered laneだけに使います。PR required laneはfullを起動しません。
[event matrix](pre-pr-verification.md#android-required-gmd-と-temporary-quarantine)に従い、main pushは
PR smokeをSKIPし、quarantine/unfiltered fullを起動しません。
main regression/quarantineのgreenはunfiltered fullのgreenではありません。
件数を固定せず、current-sourceのmanifest/receiptから期待identity集合・expected countを取得し、
runnerのfresh XMLでexact一致を確認します。receipt自己申告だけでは成功になりません。
targeted workflow の class / class#method grammar、source preflight、device、artifact、strictness は不変です。
annotation は `@Test` より前に置き、既存 targeted preflight の direct `@Test` → `fun` を保持します。

既存 `check-android-test-suites.py` の XML verifier は各laneのexact identities、重複、freshness、
counter、device、skippedを確認します。source classifier / parser、独自filterは追加しません。
Gradle/test failure は strict red のままです。既知 #106 failure を SUCCESS に変換しません。
required/server enforced check は別の概念であり、この変更は repository ruleset を書き換えません。

## Quarantine の観測と通知

[android-quarantine.yml](../../.github/workflows/android-quarantine.yml)は毎日03:17 / 15:17 UTC
（12:17 / 翌00:17 JST）に default branch 上で起動します。top-of-hourを避け、cancel-in-progress=falseとします。
manual dispatch も default branch を指定します。runごとのartifactを21日間保存します。

- **valid sample**: current-sourceのquarantine期待集合に一致するcomplete fresh XMLがあり、exact identity/counter/device契約が成立し、skipped=0。
  XMLにtest failure/errorが記録されていても **valid red** です。
- **infra-invalid**: instrumentation開始前のfailure、XML missing/incomplete/stale、inventory mismatch等。
  complete XMLに記録されたtest failureをinfra-invalidへ分類しません。
- failure時は #106 に run URL / head / tests・failures・errors・skipped / sample判定を1コメント通知します。
  未取得counterはUNKNOWNと表示します。run IDのmarkerをbot自身の既存commentへ照合し、
  同一runの再実行でも通知を重複させません。通知成功でGMD/job failureをgreenにしません。
- `android-quarantine-verification.json` とXMLをartifactで確認します。valid greenもartifactで記録します。
  manual dispatchはscheduled sample数に加算しません。

## 導入時のreview gateと見直し判断

導入時の2026-10-23 reviewでは **valid scheduled run >=14** を要求しました。green/redの両方を数え、
infra-invalid、manual、cancelled、未完了runは数えません。同一run IDはattemptが増えても1 sampleです。
GitHub Actionsのschedule run一覧と各artifactのverification receipt / XMLを照合して、
run URL、head、counter、valid/infra-invalidを #106 のreview commentに記録します。
21日retentionの期限内に保存・確認してください。

この条件を満たした10/23の判断と、後続failureを含む10/30の承認済み縮小は#106と上記に記録します。
sample数による自動解除は禁止です。owner #106 が既存証拠と失敗分布を根拠に
**continue / narrow / remove quarantine** のいずれかを明示判断し、対象identitiesと理由を記録します。
範囲やfilterを変える場合は別の承認済み変更で、current sourceとexact inventoryを再検証します。

quarantine導入時のrouting/strict red/artifact/failure通知の検証は
[PR #133](https://github.com/reitojike/think-canvas/pull/133)を参照します。
routing確認のための追加quarantine dispatchは行いません。
manual fullはdefault branchの定義とActions registrationでavailabilityを確認し、
selection契約を示すだけの追加GMDを起動する必要はありません。
