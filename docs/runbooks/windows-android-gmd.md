# Windows Android / Gradle Managed Device 検証

実装 authority は [#52 の Execution Authority Model checkpoint](https://github.com/reitojike/think-canvas/issues/52#issuecomment-5927944400)
の12決定と8分類ケースです。Option C の `ExecutionAuthorityManifest` を採用します。
PR #56 / #57 は freeze した reference です。launcher の runtime components のうち、
shared root、homes、selector、wrapper-cache、ProcessStartInfo、XML gate、runtime census、
sanitization だけを再利用し、旧 Git-diff fingerprint と policy を再throwする比較は使いません。
product / tests / Gradle / workflow / device-verification / #53 / security は変更対象外です。

## Review-first の実行順

1. `pwsh -NoProfile -File scripts/run-windows-gmd.ps1 -VerifyStatic` を実行します。
   synthetic repository 内で完結し、Java / Gradle / GMD は起動しません。
2. PowerShell parser、`git diff --check`、`scripts/check-public-boundary.ps1`、exact 2-file diff を確認します。
3. commit / push / PR 作成後、その exact head に top-level `@codex review` を1回依頼します。
   source-authority family の material finding があれば `EXECUTION_AUTHORITY_SUCCESSOR_HOLD` で停止し、
   correction loop に進みません。canonical review clean までは canary を実行しません。
4. clean 後に Focused Codex を exactly once 実行します。fresh XML が exact testcase の 1 / 0 / 0 / 0
   であることを確認します。environment blocker は下表に沿って census を残します。
5. 必要なら Claude Code が同じ launcher / manifest fingerprint / mode を exactly once 実行します。
   別 Gradle command は使いません。material divergence では停止します。
6. Focused PASS 後だけ Full を実行し、Windows fresh XML の 66 / 0 / 0 / 0 を確認します。
7. [Process #36](https://github.com/reitojike/think-canvas/issues/36) と
   [PR 後の収束](post-pr-convergence.md) に従い、exact-head CI / current-head review /
   unresolved threads 0 / fresh base / exact 2 files を確認します。merge / #52 close は行いません。

```powershell
# Focused: single Class#method、expected count は1
./scripts/run-windows-gmd.ps1 `
  -Test 'com.thinkcanvas.canvas.InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws'

# Full: Focused PASS後、filterなし
./scripts/run-windows-gmd.ps1 -ExpectedTestCount 66
```

Windows PowerShell 7.3 以上、Java 25、Android SDK 37.0、google_apis x86_64 image、
build-tools 36.0.0、adb、emulator、cached Gradle distribution が必要です。
launcher は導入、download、cleanup、retry を自動実行しません。
Full / Focused は排他的です。空 selector、class-only、comma lists、`tests_regex` は拒否します。

## ExecutionAuthorityManifest

schema は `ExecutionAuthorityManifest/v1`、scope は `think-canvas-app-buildSrc/v1` です。
filesystem を直接列挙し、Git の tracked / untracked / ignored 表示で file discovery を絞りません。
map keys と path / environment key records は ordinal order、SHA-256 は uppercase hex、
canonical serialization は compressed JSON の UTF-8 bytes です。null / boolean を固定します。
absolute path は `<worktree>` / `<shared-repo-root>` / `<wrapper-distribution>` に正規化します。

| component | 有限 scope / 保存する内容 |
| --- | --- |
| workingTreeFiles | root の build / settings（Groovy / Kotlin の全候補）、gradle.properties、gradle.lockfile、gradlew / gradlew.bat、wrapper JAR / properties、gradle/**、buildSrc/**、app/**、launcher 自身。各 file の normalized path / present / length / SHA-256。固定候補の不存在も記録 |
| directoryMembership | gradle / buildSrc / app の directory presence / type。actual files を別にhash |
| externalConfig | canonical GRADLE_USER_HOME と active wrapper distribution の gradle.properties。normalized path / present / length / file-byte SHA-256 |
| externalEnvironment | selector以外の effective ORG_GRADLE_PROJECT_*、THINKCANVAS_VERSION_CODE / VERSION_NAME / INTERNAL_KEYSTORE_FILE / INTERNAL_STORE_PASSWORD / INTERNAL_KEY_ALIAS / INTERNAL_KEY_PASSWORD。key / presence / UTF-8 value-byte SHA-256 のみ |
| policyObservations | hidden index flags、init presence / membership、ignored active inputs、DCL / local.properties、propertiesのredirect / selector / unsupported-format facts、JVM option facts、environment collision、selector ownership、wrapper selection |
| invocationContract | mode / single selector / expected count / fixed task argv / canonical home binding |

root の build / .gradle / .kotlin、app と buildSrc の build / .gradle / .kotlin / .cxx、
gradle直下の同種cache、.git、別worktree、IDE、diagnostics、Android userdata は探索しません。
source / resources の中の `build` package名は除外しません。
Git-visibleでない source edit、fileの作成・削除、length / hash変更、external properties / env digest変更、
authority set / policy facts変更を比較で検出します。
新しい includeBuild / apply / projectDir / source redirect 等は scope再確定が必要であり、
既知の外部読込構文を policy で拒否します。任意の build script の読込closureを自動証明する仕組みではありません。
同時 source/config 編集を避けます。before / after の2点観測は途中変更して戻す操作や読取競合を捕捉する
lock / continuous monitor ではありません。

HEAD / branch / git status / staged・unstaged census は sibling provenance に保存し、digestには含めません。
docs / specs / agent instructions / .github / .gitignore / .gitattributes は provenance only です。
Git状態の一致を execution bytes の代用にしません。

## Policy と runtime ownership

authority対象の tracked path に `assume-unchanged` / `skip-worktree` があれば、欠落fileを含め
`HIDDEN_INDEX_FLAG_PRESENT` で BLOCKED にします。bytesをhashできても許可しません。
scope外の docs等のflagsは拒否対象にしません。flag解除やindex refreshは自動実行しません。
authority path と祖先の symlink / junction / reparse point は targetを辿らずpresence/typeを観測して拒否します。
type・存在・列挙・byte readを安全に取得できない場合は capture failure です。

| 外部候補 | 契約 |
| --- | --- |
| canonical home init.gradle / init.gradle.kts / init.gradle.dcl | PRESENCE_FAIL_CLOSED |
| canonical home / active distribution の init.d直下の *.gradle / *.gradle.kts / *.gradle.dcl | case-sensitive suffixでPRESENCE_FAIL_CLOSED。README / nested / non-script / distribution直下initは自動入口外 |
| ignored active source/config、build.gradle.dcl / settings.gradle.dcl、root / app / buildSrc local.properties | PRESENCE_FAIL_CLOSED。private本文をhashするためにscopeを拡張しない |
| root / module / buildSrc / canonical home / distribution properties の systemProp.gradle.user.home | canonical home redirectとして拒否。Java propertiesのescape / continuationも解釈 |
| propertiesのclass / tests_regex | launcher selectorと競合するため拒否 |
| JAVA_OPTS / GRADLE_OPTS / JDK_JAVA_OPTIONS / _JAVA_OPTIONS | nonemptyを最初のJava child前に拒否。empty / absentは許可 |
| JAVA_TOOL_OPTIONS | absent / emptyまたは既存のexact NIO optionのみ。新workaroundを追加しない |

linked worktreeは `git rev-parse --path-format=absolute --git-common-dir` からshared rootを解決します。
`GRADLE_USER_HOME=<shared-repo-root>/.gradle-user`、
`ANDROID_USER_HOME=<shared-repo-root>/.gradle-user/android-user` に固定します。
SDKはANDROID_HOME → ANDROID_SDK_ROOT → LOCALAPPDATAのAndroid/Sdkの順です。
最初の指定が壊れていれば停止し、childの両SDK変数は同じ値にします。
JavaはJAVA_HOMEまたはPathからprobeし、実際のjava.home / versionを確認します。
Path / PATHはcase-insensitive mapに正規化しcanonical spellingのPathを優先します。
他keyの矛盾は拒否し、SystemRoot / windirを保証します。
child environmentをclear後1回コピーし、Fullはclass / tests_regex entryをRemove、
Focusedはexact classをsetします。null assignmentをunset証拠にしません。

wrapper URLとbase/path設定からcanonical cacheを同定し、zip.ok / 展開lib / executableを確認します。
unsupported selection / missing cacheで別home探索やdownloadはしません。
current worktreeのwrapper JARをProcessStartInfo.ArgumentListから直接起動し、shell / cwd lookupを避けます。
taskの `--rerun`、SDK / Java auto-download禁止、既存profileを維持します。
JDK / SDK / distribution binaries / cachesはmanifestのbyte authorityへ追加せず、runtime censusに残します。

## Pure stages と classification

`Capture-ExecutionAuthority` → `Evaluate-ExecutionAuthorityPolicy` → `Execute-Gmd` →
同じcapture → `Compare-ExecutionAuthority` → `Resolve-FinalClassification` → terminal write。
captureはmanifestとprivate redaction materialを返し、policy rejectionをthrowしません。
policyはcapture済みfactsだけを評価してALLOWED / BLOCKEDとspecific reason codesを返します。
comparisonはcanonical manifestsだけを比較し、policyやfilesystemの再読取をしません。
baselineが成立したexitではpolicy blockも含めrecaptureします。同じinit拒否は正常captureです。
baselineがなければafterを捏造しません。

優先順位は **write integrity → capture integrity → mutation → valid policy block →
valid execution failure → fully validated PASS** です。catch / finally の実行順で決めません。
original policyとprovisional executionはprimary分類と別に保存します。

| checkpointの8ケース | final decision |
| --- | --- |
| 1 initial capture failure | WINDOWS_GMD_SOURCE_CONFIG_CAPTURE_FAILURE、phase=initial、INVALID |
| 2 capture success + init present | WINDOWS_GMD_PREFLIGHT_BLOCKED / EXTERNAL_INIT_PRESENT、execution未開始 |
| 3 同じpolicy block + 正常recapture | 元のspecific block / reasonを保持。capture failureへ変換しない |
| 4 PASS candidate + mutation | WINDOWS_GMD_SOURCE_CONFIG_MUTATION、INVALID |
| 5 execution failure + mutation | WINDOWS_GMD_SOURCE_CONFIG_MUTATION、INVALID。test / setup / runtime failureをprovisional observed outcomeとして保持 |
| 6 PASS candidate + recapture failure | WINDOWS_GMD_SOURCE_CONFIG_CAPTURE_FAILURE、phase=recapture、INVALID |
| 7 必須diagnostic write failure | WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE、INVALID、nonzero exit |
| 8 XML PASS + unchanged | 両policy allowed / runtime preflight / child exit 0 / fresh exact XML / 必須write成功でのみWINDOWS_GMD_PASS |

新hidden flag等のpost-policy違反もmutationです。実際のrecapture failure / mutation / write failureは
既存blockより優先し、そのblock reasonをsecondary evidenceとして保持します。
authority unchangedなら既存のDEFAULT_BOOT_TIMEOUT / SETUP_FAILURE / NETWORK_OR_WRAPPER_BLOCKED /
WRAPPER_DISTRIBUTION_MISSING / GRADLE_FAILURE / RUNTIME_NON_EXECUTION / TEST_FAILURE /
RESULT_COUNT_MISMATCHを保持します。未完了状態はPASSにしません。

## XML / diagnostics / fixtures

fresh XMLはchild開始以降に更新され、before XML censusから変わったものだけを読みます。
DTD / external resolverを禁止し、suite countersとtestcase censusを照合します。
Focusedはexact classname#nameのみ1件、FullはExpectedTestCount件、failures / errors / skippedは0、
child exitは0が必要です。fresh XMLなし / zero-test / partial selectionはexit 0でも失敗です。

`build/diagnostics/windows-gmd/<run-id>/` にmanifest-before / applicable after、policy、comparison、
provenance、sanitized runtime census / logs / XML、phase axes、final classification / reasonを保存します。
raw properties / scripts / environment values / full environment / raw process command line / personal contentは保存しません。
properties / effective environment valuesはメモリ内のredactionに使い、diagnosticsへ返しません。
logsはredaction後にstream末尾2 MiB charactersへ制限します。
digestsはlocal evidenceに保持可能ですが、匿名化や秘密性の保証とは扱いません。
外部共有前に内容を確認し、secret-bearing digestを公開レポートへ自動転載しません。

terminal summaryにはrunId / started / finished / axes / finalを保存します。
`completion.json` は全必須write後の最後に書き、同runId / finished / classification / summarySha256 /
writeCompleteが一致する一組だけをcompletion evidenceにします。
途中のPASS candidateやcompletion markerがないpartial PASS fileは利用禁止です。
write failureでは非zero exitとterminal出力で通知し、best-effortでINVALIDを保存します。
診断再保存はGMD retryではありません。

`-VerifyStatic` は必須14ケースとcheckpoint全8分類を明示的に検証します。
same bytes / 両hidden flags / hidden edit / init capture-policy分離 / 同block保持 /
initial・final capture failure / PASS+mutation / failure+mutation / write failure /
external property・env mutation / raw secret非保存 / 別PowerShell fingerprint一致を含みます。
さらにmissing flagged input、scope外flag、source package名build、create/delete、docs除外、
ignored active input、redirect、forbidden option、実際のterminal writer失敗も確認します。
fixtureはignored build配下に残し、自動cleanupしません。

## Known family のbounded decision

順序はsignature → known family → minimal probe → proven mitigationです。

| Signature / family | 最小確認と既存対応 |
| --- | --- |
| empty selector / CLASS_UNSET=False | child entryをRemove。launcherが常時所有 |
| wrapper cwd / quoting破損 | absolute wrapper JAR / argv。別commandを組み立てない |
| implicit home / cache / getsockopt | canonical homes / cache census。missingで停止、network retry禁止 |
| Winsock WinError 10106 | SystemRoot / windir保証 |
| Path / PATH重複 | canonical Path優先。別key矛盾は停止 |
| Unable to establish loopback connection / Java NIO AF_UNIX | 同Java/environmentでSelector.open / Pipe.openの小probe。同familyが確認済みの場合のみ既存exact JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdirのSystemRoot/Temp指定を1つ適用可。community-validatedで公式修正とは扱わない |
| default_boot loadability timeout | phase / tracking / process census保存。同launcher / manifest / modeのClaude comparisonを1回。Codex blind retry禁止 |
| device lock 600s / MDLockCount 4 | saturated preflightで停止。既存#52のsupported cleanManagedDevices手順は別の明示操作。launcherは自動実行しない |
| tracking recovered / orphan remains | root PID / 親子 / start / owner不在を再確認する既存のmanual recovery手順。broad kill / manual AVD / automatic cleanupは禁止 |
| .lock sidecarのみ | effective count / live ownerのread-only census。residue存在だけで拒否せず、fileを削除しない |
| unexpected '(' / tests=0 | tests_regex禁止、single Class#method、fresh XML gate |
| TLS / SEC_E_NO_CREDENTIALS / policy / ACL / long path | 最小probeの既証明mitigationがなければSTOP。sandbox / global config / securityを変更しない |

canaryはexecutorごとにexactly onceであり、workaround / cleanup / retryを積み重ねません。
Codex default_boot timeoutとClaude instrumentation到達が観測された場合は
`CODEX_RUNTIME_INTERACTION_NOT_ROOT_CAUSED` とし、root cause / sandbox単独原因を断定しません。
再現可能なmitigationはdiagnostics保存、Codex blind retry禁止、same-launcher Claude比較1回、
exact-head GitHub ActionsのGMD XMLを独立merge evidenceに使う既存方針です。
Linux CIをWindows Focused / Full PASSやsame-launcher differentialの代用にはしません。

#52のfinal AC mappingではcommon launcher、manifest、hidden-index safety、classification、
Windows Focused / Full XML、Codex/Claude比較、default_boot mitigationを個別に判定します。
全AC成立ならISSUE_52_FINAL_SUCCESSOR_PR_MERGE_READY、runtime evidenceだけ不足なら
ISSUE_52_EXECUTION_AUTHORITY_READY_RUNTIME_FOLLOW_UP_REQUIRED、reviewのmaterial findingなら
EXECUTION_AUTHORITY_SUCCESSOR_HOLDです。
