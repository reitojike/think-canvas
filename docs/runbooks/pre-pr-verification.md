# PR 前の検証

この手順は PR 作成前の検証を扱います。PR 後の CI とレビューの収束は別の手順です。

1. Issue、依頼内容、Constitution、変更対象の現行 spec と plan を確認し、Task Contract の
   停止条件と変更範囲を確定します。受け入れ条件ごとに、実装と観測可能な検証結果を対応付けます。
2. リポジトリの root で、現行 Android CI に対応する基本検証を実行します。

   ```powershell
   .\gradlew.bat :app:lintDebug
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleDebug
   pwsh -File scripts/check-public-boundary.ps1
   git diff --check
   git diff --check origin/main...HEAD
   ```

   macOS / Linux では Gradle コマンドを `./gradlew` に置き換えます。Room schema を変更した
   場合は生成 schema と追跡済み schema の一致も確認します。保存、gesture、アクセシビリティ
   などの変更では、該当する instrumentation test と Android emulator の確認を追加します。
   実装の形だけをなぞるテストではなく、利用者に見える振る舞いを検証します。
3. 差分と変更ファイル一覧を読み、秘密情報、個人情報、ローカルパス、非公開サービス情報、
   会話ログ、PRD の本文、HTML モックの内容が公開されないことを確認します。script は典型的な
   パターンを補助的に検出するもので、目視確認を置き換えません。
4. PR の説明に Issue、関連 spec、変更内容、標準から外れた判断、検証結果、未確認事項を記載します。

JDK、SDK、network、権限などの環境要因で検証を完走できないときも、実行可能な項目はすべて
実行します。未実行項目、理由、代替確認を PR に記録し、degraded verification を全項目の
成功と同一視しません。再現するコードの不具合は修正し、解消できなければ `HOLD` にします。

## Android execution boundary（Issue #118 Slice C）

ordinary PR の standard device gate は、strict `@PrSmoke` GMD の exact73 です。
lint/unit/build/公開境界と smoke を `MERGE_READY` の process authority とします。
ordinary PR では unfiltered full GMD を要求しません。full coverage は main push で毎回実行し、
同じ `android.yml` の `workflow_dispatch` から explicit に実行できます。

| Event | lint/unit/build/公開境界 | PR smoke | unfiltered full GMD |
| --- | --- | --- | --- |
| pull_request | RUN | RUN | SKIP |
| push main | RUN | SKIP | RUN |
| workflow_dispatch | RUN | SKIP | RUN |

manual full は default branch にある workflow の標準 dispatch を使います。必要な ref を指定する場合は:

```bash
gh workflow run android.yml --ref <ref>
```

high-risk / cross-cutting change の convergence / diagnosis では、必要な class / class#method を
既存 `android-targeted.yml` で直接実行するか、manual full を追加できます。
すべての PR に manual full を要求しません。workflow-only 変更ではローカル GMD を無理に追加しません。

「required」は workflow / convergence process 上の authority です。GitHub ruleset の named required
status checks とは別です。Slice C 時点の ruleset `24034192 / Protect main` の required_status_checks は
空であり、今回変更しません（`SERVER_REQUIRED_STATUS_CHECKS_UNCHANGED`）。現行 settings は判定時に再確認します。

smoke execution authority は、compiled test APK を発見する AndroidJUnitRunner と、method-level
`com.thinkcanvas.test.PrSmoke` annotation です。marker は androidTest source set に置き、
`AnnotationTarget.FUNCTION` / `AnnotationRetention.RUNTIME` を指定します。class-level smoke は使いません。
既存 targeted workflow の direct `@Test` → `fun` preflight を保つため、`@PrSmoke` は `@Test` の前に置きます。

`scripts/android-test-suites.json` の `smoke` は移行時の exact73 identity receipt です。
`full_only`170 / `jvm_candidate`9 / `review_required`0 は dated planning metadata であり、
Kotlin source から actual inventory や4-way partitionを独自に再構築する runtime gate にはしません。
migration中のsnapshot countsとJSONのidentity形式・sort・duplicateは検証します。
actual discovery は runner に委ねます。markerがないtestも unfiltered full に残り、J9も移管まで保持します。
runtime上の unmarked complement179とplanning FULL_ONLY170は異なります。

ローカルで receipt / planning snapshot の self-consistency を確認する場合:

```bash
python3 scripts/check-android-test-suites.py validate
```

PR-only smoke invocation は [Android公式のannotation filtering](https://developer.android.com/training/testing/different-screens/tools#test-filtering-with-the-test-runner) を使用します。

```bash
./gradlew :app:pixel9Api37DebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.annotation=com.thinkcanvas.test.PrSmoke \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect --no-daemon
```

JSONからselectorを生成せず、long regexやremote shell用quote workaround、Python Gradle launcherは使いません。
filterはsmoke invocationだけに指定し、defaultConfig/global arguments/full/targetedへ設定しません。
fresh XMLのactual Class#method CounterをS73 receiptと比較し、0件、同数の別集合、full誤実行、
missing/extra/duplicate、stale/missing XML、malformed counters、wrong device、skippedをfail-closedにします。

smokeは **strict red の PR device gate** です。testcase failure/errorもnon-test Gradle failureもjob failureを保持し、
continue-on-errorや独自failure classifierでgreen化しません。failure後もverificationとartifactを収集します。
cancelledや証跡不足はsuccessとして使いません。

main/manual full の unfiltered invocation は変更せず維持します。

```bash
./gradlew :app:pixel9Api37DebugAndroidTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect --no-daemon
```

各laneの証跡を確認し、testcase failure は [Issue #118 convergence policy](https://github.com/reitojike/think-canvas/issues/118#issuecomment-6030890580) に沿って人間/agentが分類・記録します。
selector/receipt/freshness/device/infraの不成立やcause不明のmixed failureはHOLDです。
smokeのunrelated single failureも自動green化しません。分類後のsame-head bounded confirmationは最大1回、
Task Contractで禁止されていれば実行しません。greenまでのrerunは禁止です。
同signature再発はfollow-up、別のunrelated testへの移動はsuite-level flakinessとして裁定します。

Slice C は event boundary と process の移行です。targeted workflow、S73/F170/J9/R0、PrSmoke marker、
test本体、production、Gradle dependency、timeout、retry、ignoreは変更しません。
