# Windows Android / Gradle Managed Device 検証

Codex を通常の executor とし、Claude Code も同じ repository-owned launcher を呼びます。
[Issue #52](https://github.com/reitojike/think-canvas/issues/52) の
[scope reset](https://github.com/reitojike/think-canvas/issues/52#issuecomment-5931464913) と
[Process #36](https://github.com/reitojike/think-canvas/issues/36) に従います。
実行経路の既知 evidence は [#40](https://github.com/reitojike/think-canvas/issues/40) と
[PR #51](https://github.com/reitojike/think-canvas/pull/51) にあります。

## Read-only salvage inventory

開始 main は `c0014ba1fc291fef93ab9679a791c36e2aca54f3`。
以下の closed/unmerged head を読取のみで確認しました。cherry-pick は行いません。

| Reference | KEEP | DROP / SIMPLIFY |
| --- | --- | --- |
| [#56](https://github.com/reitojike/think-canvas/pull/56) `c7bd95c` | shared root、canonical homes、SDK/JDK probe、SystemRoot/windir、Path 正規化、ProcessStartInfo、selector Remove、wrapper cache、XML gate、phase/census | full environment inheritance と Git diff を execution bytes の代理にする fingerprint を DROP |
| [#57](https://github.com/reitojike/think-canvas/pull/57) `be8e29c` | Java properties の escape/継続行処理、external init/config 拒否、Java probe 前の option gate | environment/private properties digest を DROP。external config は存在時停止へ SIMPLIFY |
| [#58](https://github.com/reitojike/think-canvas/pull/58) `d6f0245` | actual relevant worktree bytes、hidden index flag 拒否、before/after の比較、元の失敗理由保持 | generic manifest/axis model は有限 snapshot と単一 summary へ SIMPLIFY |
| [#59](https://github.com/reitojike/think-canvas/pull/59) `07af2f4` | fresh run namespace、fixed init による root/app output isolation、device/variant/count/name/counter gate、DTD 拒否、JSON と text の分離 | completion consumer、artifact sealing、attestation を DROP。Full names は source census へ SIMPLIFY |
| [#60](https://github.com/reitojike/think-canvas/pull/60) `021f745` | finite child environment、signing/token/project env の drop、version default 固定、unsafe stream suppression、diagnostic-only text | private filename/value verifier、secret equality、persistent secret hashes、全診断 seal を DROP |

参照 PR の branch、state、thread は変更しません。対象は trusted local machine での通常の
stale/foreign result と異なる入力の誤認防止です。malicious post-run 改竄対策は追加しません。

## 実行契約

Windows の PowerShell 7.3 以上、Java 25、既存の Gradle wrapper distribution、Android SDK の
platform 37.0 / build-tools 36.0.0 / google_apis x86_64 system image / adb / emulator が必要です。
Codex と Claude Code は対象 worktree の同じ script を次のように呼びます。

```powershell
./scripts/run-windows-gmd.ps1 `
  -Test 'com.thinkcanvas.canvas.InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws'

./scripts/run-windows-gmd.ps1 -ExpectedTestCount 66
```

Focused は単一 `Class#method` だけで expected count は1です。空値、class-only、comma list、
regex、任意の追加 Gradle argv は受け付けません。Full は current instrumentation source の
plain `@Test fun` census を count / 全 testcase 名の期待集合にします。未対応 runner 形式や
caller count との不一致は実行前に停止します。現行 main は66件です。

launcher の所在を cwd とし、linked worktree の共通 Git directory から shared repository root
を解決します。既存 dirty source を変更しません。canonical homes は
`<shared-repo-root>/.gradle-user` とその `android-user` に固定します。
SDK は `ANDROID_HOME`、`ANDROID_SDK_ROOT`、`LOCALAPPDATA/Android/Sdk` の順で決め、壊れた
先行指定から fallback しません。Java は `JAVA_HOME/bin/java.exe` または Path の先頭候補を
probe し、実際の Java home / version を確認します。

child は `ProcessStartInfo.Environment.Clear()` 後に有限集合だけを持ちます。
Windows の system/profile/temp/path、Java/SDK、canonical homes、default version 1 / 0.1.0、
focused selector だけを構築します。`SystemRoot` と `windir` は OS から解決し、Path/PATH は
canonical `Path` 優先の1 entry に正規化します。signing、token、credentials、proxy、任意の
`ORG_GRADLE_PROJECT_*`、無関係な project environment は継承しません。親環境は変更しません。
Full の class と常時の tests_regex は map から実際に Remove します。

任意の JVM options は最初の Java child 前に拒否します。既報の exact
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<windows>/Temp` だけは、signature / minimal probe
の既存証拠を持つ場合に許可し、適用を記録します。追加 workaround を自動選択しません。

wrapper JAR を Java の `ArgumentList` で直接起動するため、CMD quoting を caller が再構築する
必要はありません。固定 task は `:app:pixel7Api37DebugAndroidTest`。task の `--rerun`、
`--no-daemon`、`--offline`、SDK/JDK auto-download 禁止を launcher が所有します。
wrapper URL に対応する canonical distribution の `.zip.ok`、選択した distribution directory が
ちょうど 1 つであること、`lib/` 直下にちょうど 1 つの通常 file `gradle-launcher-*.jar` (case-sensitive)、
`bin/gradle.bat` を read-only で先に確認し、missing / 曖昧 / 読めない / reparse の場合は fail closed で
wrapper を起動せず、download も cache の削除・修復もせず `WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING`
(`WRAPPER_CACHE_MISSING_NO_DOWNLOAD`) で停止します。JAR の暗号学的な完全性検証は行いません。offline は network retrieval を避ける契約であり、
NIO / default_boot の mitigation とは扱いません。

Gradle には `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` を必ず 1 回だけ
渡します。これは Windows GMD の既知の good configuration で、GitHub CI (`android.yml`) と同じです。
AGP 既定の `-gpu auto-no-window` では、この host で boot 後も emulator が adb `offline` のまま
test task が timeout しました。別の GPU mode への移行は #52 の scope 外です。profile/CI は
変更しません。

## Source/config と result ownership

root build/settings/properties/wrapper、`gradle/**`、`app/**`、launcher の実 bytes と membership
を前後で比較します。HEAD も記録・比較します。既知の app/build / cache roots を除外し、
source package に含まれる `build` directory は除外しません。Git の hidden index flags、
ignored active inputs、reparse paths、local.properties、DCL、buildSrc、外部 build/source redirect、
競合する runner/system/JDK properties は fail-closed です。新 topology を暗黙に許可しません。
canonical home / distribution の外部 properties と automatic init は存在時停止し、秘密値や
private filename の hash を作りません。Gradle に無関係な docs/specs 全体の hashing はしません。
Git の読取には対象 worktree だけの command-scoped `safe.directory` を指定します。
global Git config や Windows security の設定変更はありません。

各 invocation は新しい ignored `build/diagnostics/windows-gmd/<run-id>/` を確保します。
#59 由来の fixed init は root/app build directory をその run の `gradle-build/root` / `app` へ
移し、Gradle 側で GMD task の device、debug variant、configured result/output path を検査します。
compile outputs の増加と incremental reuse の減少を伴います。Room schema destination は
既存のままなので、source snapshot で変化を検出します。

shared `app/build` や他 run の XML は採用しません。今回の空 namespace、child の開始後の
生成、Gradle の run binding、期待する XML の exact path/device を確認します。DTD / external
resolver は禁止し、suite counters と testcase children、unique identities、全期待名を照合します。
PASS は fresh XML の exact expected tests、failures/errors/skipped=0、child exit 0、前後入力の
一致が揃った場合だけです。XML なし、zero-test、partial/wrong testcase は失敗です。
`BUILD SUCCESSFUL` や exit 0だけで PASS にしません。

process/GMD tracking の read-only census を保存し、明確な active overlap や saturated locks は
停止します。census が取得できない場合はその制限を記録します。result isolation を ownership
の中心とし、process census の完全性から producer を推測しません。manual AVD、orphan、
lock/cache を自動 kill/delete/recreate しません。retry はありません。

## 診断と判定

summary に run ID、phase、classification/reason、UTC timestamps、child exit、XML census/count/name、
source comparison を保存します。selector、argv、runtime paths の logical summary、process/GMD
census も保存します。stdout/stderr は diagnostic-only、各 stream は末尾2 MiB characters に
制限します。credential の疑いがある stream は保存せず `SUPPRESSED_SECRET_SAFETY` にします。
保存失敗は availability へ記録し、ログを PASS authority にしません。structured JSON を serialize
後に redaction しません。raw credentials、全 environment、process command line は保存しません。

terminal の `summary.json` の complete/finished、`classification.txt`、fresh XML を確認します。
中断された run の途中 summary は PASS ではありません。diagnostics は local に保持し、外部共有
前に公開情報境界を確認します。artifact seal、completion verifier、evidence ledger は作りません。

## 現在確認した Windows blocker

final consolidation の最初の同一 source focused 比較では、Codex は adb `version` probe の
`Cannot mkdir '/.android': Permission denied` / exit `-1073740791` で停止しました。
#56 の既報と同じ family です。canonical Android home は存在し、native profile と環境の
USERPROFILE は一致していました。この観測だけでは root cause を断定できません。
[Codex Windows sandbox](https://developers.openai.com/codex/windows) と
[Android environment variables](https://developer.android.com/tools/variables) を照合しましたが、
この signature に対する実証済みの repository-level mitigation は未確立です。
security/sandbox 設定は変更していません。

Claude の同 launcher は adb を通過しましたが、salvage した init の狭い result-directory prefix
gate で停止しました。configuration-only dry-run 1回で device/variant と run 内の declared outputs
を確認し、gate を run namespace containment に補正しました。exact XML gate は維持しています。

その後、Claude Code の Windows では `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect`
を渡す補正後の launcher を実証済みです (`ISSUE_52_WINDOWS_GMD_RUNTIME_PROVEN`)。

- focused `InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws`: 1/0/0/0、exact testcase
- full `-ExpectedTestCount 66` を 2 回: いずれも 66/0/0/0、unique testcase 66、run ごとの fresh
  owned XML (2 回目は 1 回目と別の XML)
- launcher の判定は `WINDOWS_GMD_PASS` / `FRESH_EXACT_XML`、実際の emulator argv は
  `-gpu swiftshader_indirect`

`swiftshader_indirect` がこの issue で実証済みの Windows GMD の GPU contract です (`SWIFTSHADER_INDIRECT_WINDOWS_GMD_CONTRACT_PROVEN`)。
同じ環境で property なしの場合は、emulator が `-gpu auto-no-window` で起動し、boot 後に
`emulator-5554` が adb `offline` のまま test task が timeout しました。property だけを追加した
単一変数の比較で fresh focused PASS になったため、この launcher path についてはこの
`auto-no-window` の adb offline / timeout 系統は閉じています。Windows 26H2 はこの失敗の原因と
しては扱いません。

Codex native Windows は引き続き別の runtime/profile-context blocker
(`CODEX_WINDOWS_PROFILE_CONTEXT_BLOCKER`、外部 Codex runtime dependency) です。Codex の Windows GMD
そのものが実証されたわけではなく、補正後の launcher が未検証であることの根拠でもありません。
最新の詳細な run evidence / AC mapping は Issue #52 の completion report を参照します。

| Classification | 意味 |
| --- | --- |
| `WINDOWS_GMD_PASS` | fresh exact XML と exit/source gates が成立 |
| `WINDOWS_GMD_PREFLIGHT_BLOCKED` | environment/source/selector/SDK/JDK/process/tracking の拒否 |
| `WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING` | wrapper cache missing。download 未実行 |
| `WINDOWS_GMD_GRADLE_FAILURE` | wrapper/Gradle child の開始・実行失敗 |
| `WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT` | 既報 default_boot loadability timeout |
| `WINDOWS_GMD_SETUP_FAILURE` | device setup / lock failure |
| `WINDOWS_GMD_RUNTIME_NON_EXECUTION` | zero-test / instrumentation 非実行 |
| `WINDOWS_GMD_RESULT_COUNT_MISMATCH` | count/name/device/counters 不一致 |
| `WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE` | owned XML/binding 不成立、overlap |
| `WINDOWS_GMD_TEST_FAILURE` | actual testcase failure/error/skip |
| `WINDOWS_GMD_SOURCE_CONFIG_MISMATCH` | 前後の実行入力不一致または再取得失敗 |
| `WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE` | terminal structured diagnostics を保存できない |

## Bounded verification / failure handling

```powershell
./scripts/run-windows-gmd.ps1 -VerifyStatic
```

`-VerifyStatic` は launcher 自身が所有する fixture / static check だけを行います。ignored fixture
namespace で selector、wrapper cache の readiness (合成 fixture 上の distribution directory 数、
launcher JAR の個数・型・case・場所、reparse、列挙失敗)、zero/wrong/stale/foreign XML、
source bytes mismatch、hidden flags、external config、secret 非継承、GPU property がちょうど 1 回
であることを確認します。Gradle/GMD は起動しません。`STATIC_PASS` は、次の repository 検証を実行した
ことを意味しません。

PR 前の独立した必須 check として、次を別々に実行して結果を記録します。`-VerifyStatic` の
`STATIC_PASS` だけで repository の検証が満たされたとは扱いません。

- PowerShell parser diagnostics (`scripts/run-windows-gmd.ps1` の parse error が 0 件)
- `pwsh -File scripts/check-public-boundary.ps1`
- `git diff --check`
- `git diff --check <fresh-main-SHA>...HEAD`
- base から head への exact 3-file census (`scripts/run-windows-gmd.ps1`、
  `docs/runbooks/windows-android-gmd.md`、`docs/runbooks/device-verification.md`)

static floor の後、Codex focused を exactly once 実行します。environment/runtime blocker なら
exact signature を #52 の既存 evidence と current official/upstream reports に照合し、minimal
probe を行います。signature に一致する known mitigation は最大1つです。blind retry や
workaround stacking はしません。default_boot は同 launcher/source/mode の Claude 比較を1回
行い、Codex runtime の原因を推測で断定しません。Claude 専用 Gradle command は使いません。

Claude Code の Windows での focused 1 回と full x2 consecutive は、同 launcher で実証済みです
(上記)。fresh XML、executor、HEAD/source、count/name、manual intervention なしを記録します。
Codex native Windows の focused/full は外部 runtime dependency により未実証のままで、運用上は
(1) Codex は fail-fast、(2) 同 launcher での Claude differential、(3) GitHub Actions の GMD XML を
独立した merge の根拠にします。Linux CI は Windows proof の代用にしません。

focused 実証後に final PR を作り canonical `@codex review` を依頼します。execution-essential
finding の bounded correction は最大1回。hardening-only finding はこの scope の merge blocker
にしません。追加 material finding は同 PR / #52 で `ISSUE_52_FINAL_CONSOLIDATION_HOLD`。
successor chain は作りません。runtime 未達は `ISSUE_52_WINDOWS_GMD_STABILITY_HOLD` です。
全 runtime / current-head CI / canonical review / material threads / fresh base が成立した場合だけ
`ISSUE_52_FINAL_PR_MERGE_READY` で停止します。merge と #52 close は別 phase です。
