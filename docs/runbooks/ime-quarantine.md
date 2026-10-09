# IME readiness family の temporary quarantine

owner は [#106](https://github.com/reitojike/think-canvas/issues/106)、CI architecture は
[#118](https://github.com/reitojike/think-canvas/issues/118)です。
[#125](https://github.com/reitojike/think-canvas/issues/125)は test-only helper commonization を扱い、
root-cause investigation は #106 に戻します。PR #131 の Slice C は実行eventとrunbookの最小同期だけを扱います。

## Fresh adjudication（2026-10-09）

main `c8b3b7a53bff247456acdcc26d604d247f582d95` の source を読み、
「入力可能になった後、actual IME visibility / positive IME inset を wait/assert する test」
を helper の呼び出し経路も含めて個別に裁定しました。exact identities は
[既存 receipt の quarantine.expected](../../scripts/android-test-suites.json)に保存します。
これは XML inventory を照合する静的 receipt であり、実行 selector を生成しません。

| Source surface | 該当件数 | 根拠 |
| --- | ---: | --- |
| NeutralInteractionTest | 22 | startNew / startExisting / renameRegion / waitEditor / dialogBack 等から waitEditorReady へ到達。native visibility と positive bottom を要求 |
| ViewportHistoryInteractionTest | 9 | openLowerDraft / search / waitSearchReady / waitEditableImeReady。Undo/Redo の検索再入力待機も含む |
| BoardListScreenTest | 1 | renameReloadsTheListAfterActivityRecreation の actual visibility と native Save bounds の安定待機 |
| EdgeAutoPanTest | 1 | editorAndDiscardDialogDoNotAdmitTheMoveTicker の IMM readiness 後の actual visibility |
| TextEditorDismissalTest | 1 | outsideDoubleTapSlopConfirmsBothSinglesInOrder の settledCamera(shown=true) |
| OffscreenIndicatorsTest | 1 | editorAndCreationToolSuppressIndicatorsAndRestoreOnlyCurrentTargets の settledCamera(shown=true) |
| ImageCanvasInteractionTest | 1 | descriptionCancelDirtyBackEmptyCommitUndoAndRecreationKeepExpectedText の focused dialog の visibility 待機 |
| ShareImportInteractionTest | 2 | editorAndImeKeepTheirDraftUntilTheOriginalEditFinishes / listRenameModalKeepsItsTextUntilSaveThenPresentsTheShare の actual visibility 待機 |

全252 identities と PrSmoke73 は既存 snapshot に完全一致し、該当38のうち PrSmoke は17です。
Neutral の tool-only / hidden-only 経路、TextEditorDismissal の focus-only awaitEditor、
Offscreen/SemanticNavigation の hidden-only cleanup は含めません。クラス単位では除外しません。
Claude census の source-comparable full 45 runs 中7 runs の `CONFIRMED_106` は再発の履歴であり、
この annotation は38件すべてで failure を再現したという主張ではありません。

## 実行契約

AndroidJUnitRunner 1.7.0 の compiled APK discovery と標準 annotation filtering が execution authority です。
[公式Runner reference](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)は
複数filterの積集合を定義します。[FlakyTest reference](https://developer.android.com/reference/androidx/test/filters/FlakyTest)に従い、
method-level `@androidx.test.filters.FlakyTest(bugId = 106)` を38件だけに付けます。
test body、production、timeout、assertion、retry は変更しません。

| Lane | 起動境界 | Runner arguments | Exact XML inventory |
| --- | --- | --- | ---: |
| PR smoke | pull_request、required process gate | annotation=PrSmoke、notAnnotation=FlakyTest | 56 |
| non-quarantined | main push、required process gate（PRはjob-level SKIPPED） | notAnnotation=FlakyTest | 214 |
| quarantine | default branch schedule / workflow_dispatch | annotation=FlakyTest | 38 |
| full | android-full.yml の workflow_dispatch のみ | filterなし | 252 |

完全修飾名は `com.thinkcanvas.test.PrSmoke` / `androidx.test.filters.FlakyTest` です。
「full」は unfiltered252 にだけ使います。PR required lane は full を起動しません。
[event matrix](pre-pr-verification.md#android-required-gmd-と-temporary-quarantine)に従い、main pushは
smoke56をSKIPし、quarantine38/full252を起動しません。214/38のgreenはfull252のgreenではありません。
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

- **valid sample**: expected38のcomplete fresh XMLがあり、exact identity/counter/device契約が成立し、skipped=0。
  XMLにtest failure/errorが記録されていても **valid red** です。
- **infra-invalid**: instrumentation開始前のfailure、XML missing/incomplete/stale、inventory mismatch等。
  complete XMLに記録されたtest failureをinfra-invalidへ分類しません。
- failure時は #106 に run URL / head / tests・failures・errors・skipped / sample判定を1コメント通知します。
  未取得counterはUNKNOWNと表示します。run IDのmarkerをbot自身の既存commentへ照合し、
  同一runの再実行でも通知を重複させません。通知成功でGMD/job failureをgreenにしません。
- `android-quarantine-verification.json` とXMLをartifactで確認します。valid greenもartifactで記録します。
  manual dispatchはscheduled sample数に加算しません。

## Review gate: 2026-10-23

14日目のreviewでは **valid scheduled run >=14** を要求します。green/redの両方を数え、
infra-invalid、manual、cancelled、未完了runは数えません。同一run IDはattemptが増えても1 sampleです。
GitHub Actionsのschedule run一覧と各artifactのverification receipt / XMLを照合して、
run URL、head、counter、valid/infra-invalidを #106 のreview commentに記録します。
21日retentionの期限内に保存・確認してください。

14 samples未満なら根拠不足として記録し、quarantineを自動解除しません。
14 samples以上でも自動解除は禁止です。owner #106 が原因調査と失敗分布を根拠に
**continue / narrow / remove quarantine** のいずれかを明示判断し、対象identitiesと理由を記録します。
範囲やfilterを変える場合は別の承認済み変更で、current sourceとexact inventoryを再検証します。

quarantine導入時のPR #133ではmerge後に1回manual dispatchし、exact38 / strict red / artifact /
failure通知を確認済みです。Slice Cのrouting確認ではquarantineを追加dispatchしません。
manual full252はdefault branchの定義とActions registrationでavailabilityを確認し、
selection契約を示すだけの追加GMDを起動する必要はありません。
