# Windows Android / Gradle Managed Device 検証

Codex と Claude Code は、対象 worktree にある同じ launcher を使います。判断の根拠は
[Issue #52 本文・コメント](https://github.com/reitojike/think-canvas/issues/52)、
[Issue #40](https://github.com/reitojike/think-canvas/issues/40)、
[PR #51](https://github.com/reitojike/think-canvas/pull/51) です。
runner / profile / CI infrastructure は [#53](https://github.com/reitojike/think-canvas/issues/53)
の別 scope とし、この手順で product、test、Gradle configuration、security 設定を変更しません。

## 実行

Windows の PowerShell 7.3 以上で実行します。既存の Java 25、Android SDK、API 37.0 の
platform / google_apis x86_64 system image、build-tools 36.0.0、adb、emulator、
wrapper distribution が必要です。launcher は SDK / Gradle / JDK を自動導入しません。

```powershell
# Full: filter なし、現在の suite は66件
./scripts/run-windows-gmd.ps1 -ExpectedTestCount 66

# Focused: single Class#method、expected count は常に1
./scripts/run-windows-gmd.ps1 `
  -Test 'com.thinkcanvas.canvas.InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws'
```

`-Test` の空値、comma-separated multi-method、`tests_regex`、class-only selector は拒否します。
Full と Focused の引数は同時指定できません。別の Gradle command を組み立てず、Claude にも
この呼び出しをそのまま渡します。launcher は実行ごとに1回だけ GMD を起動し、retry しません。

## Path / environment / source の authority

- launcher 所属の worktree、HEAD、branch、dirty state を記録します。linked worktree でも
  `git rev-parse --path-format=absolute --git-common-dir` から共有 repository root を求めます。
- `GRADLE_USER_HOME=<repo-root>\.gradle-user`、
  `ANDROID_USER_HOME=<repo-root>\.gradle-user\android-user` に固定します。
- SDK は既存 `ANDROID_HOME`、既存 `ANDROID_SDK_ROOT`、
  `%LOCALAPPDATA%\Android\Sdk` の順です。最初の指定が壊れていたら、黙って fallback せず停止します。
  child の両 SDK 変数は同じ値にします。`local.properties` が SDK を上書きし得る worktree は拒否します。
- Java は `JAVA_HOME\bin\java.exe`、なければ Path の `java.exe` を probe し、実際の
  `java.home` と version を確認します。未確認の JVM option は拒否します。
- child environment は case-insensitive な map に正規化してから `ProcessStartInfo.Environment`
  に1回だけコピーします。重複時は canonical spelling の `Path` を `PATH` より優先し、
  適用した正規化を記録します。他の key の値が矛盾した場合は停止します。
  `SystemRoot` / `windir` を保証します。
- Full は `class` / `tests_regex` の entry 自体を `Remove` します。Focused は
  `ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.class=<Class#method>` を設定し、
  `CLASS_EXACT=True` / `TESTS_REGEX_UNSET=True` を必須にします。`SetEnvironmentVariable(...,$null,...)`
  を unset の authority にしません。競合する `gradle.properties` selector は停止します。
- 現在の worktree の `gradle-wrapper.jar` を、`gradlew.bat` と同じ
  `org.gradle.wrapper.GradleWrapperMain` / default JVM options で直接起動します。
  `ProcessStartInfo.ArgumentList` を使い、shell quoting と cwd の executable lookup を避けます。
  実行 task は `:app:pixel7Api37DebugAndroidTest`、task の `--rerun` で古い成功を再利用しません。
  GPU / GMD profile は変更しません。
- wrapper URL に対応する canonical cache の `.zip.ok`、展開済み lib と executable を確認します。
  missing なら wrapper を起動せず停止します。wrapper / SDK / JDK の新規 download を検証の一部にしません。
- fingerprint は Git 自身に `git diff --output=<temp-file>` で unstaged / staged の binary diff
  を書かせ、その file bytes の SHA-256 を取ります。PowerShell pipeline の text を hash しません。
  changed-file / staged / untracked directory census、untracked source / test / repository configuration
  file bytes の SHA-256、HEAD / branch / dirty state
  を合成し、実行前後の source state が一致することも確認します。diff 本文は診断に保存しません。
  hash 対象は現行 settings の app 全体（source / schema / module build 設定）、Gradle が
  自動読み込みする buildSrc、scripts、docs、specs、gradle、.specify、.agents/skills、
  .github と root の build / settings（Groovy / Kotlin 両形式）/ wrapper / authority 設定です。
  build outputs を除外します。別 worktree、Android userdata、
  個人ファイルの content は読みません。
- canonical `.gradle-user/gradle.properties` と active wrapper distribution root の
  `gradle.properties` は、normalized path、present / absent、byte length、file-byte SHA-256
  だけを追加します。存在自体では拒否せず、本文・property 値は保存しません。
  root / canonical home / distribution の properties に `systemProp.gradle.user.home` がある場合は
  canonical home の redirect として停止します。Java properties の escape / 継続行も検査します。
- canonical home の `init.gradle` / `init.gradle.kts` / `init.gradle.dcl`、canonical home と
  active distribution の `init.d` 直下の `*.gradle` / `*.gradle.kts` / `*.gradle.dcl` は
  presence fail-closed です。外部 script の import closure を追跡する scope へ広げません。
  suffix は current Gradle と同じ case-sensitive 照合です。README、非 script suffix、nested file、
  distribution 直下の init candidate は自動実行されないため拒否しません。
- ignored automatic source/config は presence fail-closed です。有限集合は `app/src`、`app/schemas`、
  `buildSrc/src`、`gradle`、`buildSrc/gradle` と root / app / buildSrc 直下の
  build / settings（Groovy / Kotlin / DCL）、`gradle.properties`、`local.properties`。
  source root の外にある build outputs / caches は探索しません。Gradle config tree 内も
  build / `.gradle` / `.kotlin` / `.git` を除外します。source package / resource の名前が
  `build` であっても source として検査します。未対応の
  `build.gradle.dcl` / `settings.gradle.dcl` candidate は Git の ignore 状態によらず停止します。
- selector 以外の effective child `ORG_GRADLE_PROJECT_*` と、現行 app が読む
  `THINKCANVAS_VERSION_CODE` / `THINKCANVAS_VERSION_NAME` / `THINKCANVAS_INTERNAL_KEYSTORE_FILE` /
  `THINKCANVAS_INTERNAL_STORE_PASSWORD` / `THINKCANVAS_INTERNAL_KEY_ALIAS` /
  `THINKCANVAS_INTERNAL_KEY_PASSWORD` は key / presence / UTF-8 value-byte SHA-256 として捕捉し、
  deterministic order の environment digest を合成します。secret-bearing value は digest のみで、
  raw value は診断へ保存しません。`GRADLE_OPTS` / `JAVA_OPTS` / `JDK_JAVA_OPTIONS` /
  `_JAVA_OPTIONS` の nonempty は最初の Java probe より前に拒否します。
  `JAVA_TOOL_OPTIONS` は既知の exact NIO option だけという既存条件を維持します。
- 同じ source/config authority を実行前と実行後（途中の exception 時も）に捕捉します。
  file の作成・削除・content 変更、environment digest 変更は source/config mutation として
  fail-closed です。読取不能、capture failure、diagnostic write failure も PASS にしません。
  各 map は ordinal key order、array は定義順で deterministic serialization して合成します。
  これは2時点の比較であり、途中変更して元に戻す行為を検出する continuous 監視ではありません。
  同時の source/config 編集を避けます。
- source/config fingerprint と runtime census は別物です。JDK / SDK の path・version、
  tool probe、process / tracking、mitigation は runtime census で比較します。
  distribution / dependency cache / SDK / JDK 全体の bytes を固定する fingerprint ではありません。

## XML gate と診断

`build/diagnostics/windows-gmd/<run-id>/` は既存の `**/build/` rule で ignored です。
summary、source fingerprint、sanitized preflight、selector、wrapper cache、applied mitigation、
read-only process census、GMD tracking census、stdout / stderr / exit code / UTC timestamps、
実行前後の XML file census、集計した testcase names、final classification を保存します。
command-line census が取得できない場合は PID / name と取得制限を残し、orphan 不在と推定しません。
raw process command line、full environment、Android userdata、個人ファイル、diff 本文は保存しません。
ログは secrets を redaction し、各 stream の末尾2 MiB charactersまでに制限します。
診断を外部共有する前に内容を確認し、必要な census / summary のみ引用します。
途中で executor が終了し、`finishedUtc` / exit code / `classification.txt` が揃わない run は
terminal evidence 不足です。途中の summary を final classification や PASS として使いません。

PASS の authority は fresh GMD XML です。実行開始以降に更新され、実行前 census から変わった
XML のみ読みます。suite counters と testcase census の整合を確認します。
Full は `tests == ExpectedTestCount`、Focused は `tests == 1` と要求した exact
`classname#name` のみ、どちらも failures / errors / skipped が0、Gradle exit 0が必要です。
fresh XML なし、`tests=0`、partial selection は Gradle exit 0でも hard failure です。

| Final classification | 判断 |
| --- | --- |
| `WINDOWS_GMD_PASS` | exit / fresh XML / count / focused name の全 gate を通過 |
| `WINDOWS_GMD_PREFLIGHT_BLOCKED` | path、SDK、Java、environment、selector、external config presence gate、saturated tracking 等の不一致 |
| `WINDOWS_GMD_SOURCE_CONFIG_MUTATION` | before/after source/config の変更。product/test failure と分離 |
| `WINDOWS_GMD_SOURCE_CONFIG_CAPTURE_FAILURE` | source/config 読取・capture・後段 gate 失敗。result invalid |
| `WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE` | 診断の保存失敗。terminal evidence を PASS としない |
| `WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING` | canonical cache missing。download せず停止 |
| `WINDOWS_GMD_NETWORK_OR_WRAPPER_BLOCKED` | wrapper / network / TLS signature。test failure と分離 |
| `WINDOWS_GMD_GRADLE_FAILURE` | Gradle の失敗。既知 family でなければ STOP |
| `WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT` | `default_boot` loadability timeout、instrumentation 前 |
| `WINDOWS_GMD_SETUP_FAILURE` | device lock / setup の失敗 |
| `WINDOWS_GMD_RUNTIME_NON_EXECUTION` | fresh XML なし、zero-test、XML 不正等 |
| `WINDOWS_GMD_TEST_FAILURE` | XML に failure / error / skip が存在 |
| `WINDOWS_GMD_RESULT_COUNT_MISMATCH` | count / exact requested testcase の不一致 |

## Known family の bounded decision table

順序は **failure signature → known family → minimal probe → proven mitigation** です。
下表は #52 の記録を運用に絞ったものです。症状が一致しない workaround は追加せず STOP します。

| Signature / family | Minimal probe | 証明済みの対処・停止条件 |
| --- | --- | --- |
| `CLASS_UNSET=False` / empty runner entry | child selector census | entry の `Remove`。launcher が常時所有 |
| wrapper が cwd で見つからない、cmd quoting 破損 | wrapper JAR path / argv census | current worktree の absolute wrapper と直接 argv。command を再構築しない |
| implicit `.gradle` / worktree-local cache / `Permission denied: getsockopt` | canonical homes / wrapper-cache census | shared homes と cached distribution。missing は停止、network retry 禁止 |
| Winsock `WinError 10106` | child `SystemRoot` / `windir` | Windows system directory を明示。launcher が常時保証 |
| `Path` / `PATH` 重複 | case-insensitive key census | map 正規化、canonical `Path` を優先。別 key の矛盾は停止 |
| `Unable to establish loopback connection` / Java NIO AF_UNIX | 同じ Java と environment で小さな `Selector.open()` / `Pipe.open()` probe | probe が同 family の場合だけ `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%SystemRoot%\Temp` を child 起動前に1つ適用可。community-validated、公式修正とは扱わない。`--offline` / `--no-daemon` を対処として積まない |
| `default_boot ... is loadable` timeout | summary の到達 phase、tracking と process census | blind retry 禁止。diagnostics 保存、同 launcher / source / mode を Claude で1回比較。root cause を断定しない |
| device lock 600s timeout / `MDLockCount 4` | canonical tracking、owner / orphan census | saturated preflight で停止。既存 #52 手順に沿った supported `cleanManagedDevices` を1回。tracking recovery と process recovery は別 |
| `GMD_TRACKING_RECOVERED_ORPHAN_PROCESS_REMAINS` | exact GMD root PID、親子関係、開始時刻、owner Gradle 不在を再確認 | proven orphan だけ exact-PID tree termination。`/T` が `/F` 必須を返した時だけ再照合後に同 root の `/T /F`。manual AVD / broad `/IM` は対象外。launcher は自動 cleanup しない |
| `.lock` sidecar のみ残存 | canonical tracking effective count、live owner、必要時は read-only exclusive-open | `GMD_LOCK_RESIDUE_NON_BLOCKING` の条件一致なら存在だけで停止しない。lock file を削除しない |
| `/system/bin/sh: syntax error: unexpected '('` / `tests=0` | selector と fresh XML count/name | `tests_regex` 禁止、proven single `Class#method`。zero-test green を拒否 |
| `SEC_E_NO_CREDENTIALS` / TLS、policy / ACL、long path | 対象 path / process 開始 / log の最小確認 | 既証明の repository-level mitigation がなければ STOP。sandbox、global config、security を変更しない |

同じ family の blind retry をしません。runtime blocker 後はまず read-only census を残します。
症状に一致し、probe で確認した mitigation は最大1つ、bounded rerun は最大1回です。
複数の cleanup / workaround を同じ canary に積み重ねません。setup / runtime blocker では
product / Android test を変更せず、Claude の同 launcher comparison へ進みます。

## Codex → Claude differential と独立した CI 証拠

比較対象は同じ host / worktree、source fingerprint、canonical homes、mode / selector、到達 phase、
classification、fresh XML です。比較前に source を編集・commit・stage しません。
Claude も launcher を exactly once 実行し、別 Gradle command を組み立てません。
process / tracking の変化や cleanup を伴った場合は条件差として明記します。
material divergence があれば STOP し、追加 canary や speculative workaround を行いません。

Codex が `WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT`、Claude の同 launcher が instrumentation へ進んだ場合、
比較の分類は **`CODEX_RUNTIME_INTERACTION_NOT_ROOT_CAUSED`** です。root cause / sandbox 単独原因は
断定しません。対処は Codex blind retry 禁止、diagnostics 保存、same-launcher Claude comparison 1回、
merge authority に GitHub Actions GMD XML を利用可能とすることです。
これは #52 の documented reproducible mitigation の候補であり、Windows full PASS の証明とは分けます。

GitHub Actions の `android-17-gmd-test-results` artifact は、current PR head に対応する
workflow run / job を確認し、fresh XML の expected suite count / failures / errors / skipped / testcase
census を検証した場合だけ独立証拠に使います。古い head、artifact 不在、job の green だけでは
代用しません。Linux CI を Windows launcher の実行証拠とは扱いません。
PR の exact-head CI / review / unresolved thread / base freshness は
[PR 後の収束](post-pr-convergence.md) に従います。merge と Issue close は別 authorization です。
