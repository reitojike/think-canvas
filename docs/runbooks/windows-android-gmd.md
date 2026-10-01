# Windows Android / Gradle Managed Device 検証

実装 authority は [#52 の Secret-safe diagnostics checkpoint](https://github.com/reitojike/think-canvas/issues/52#issuecomment-5930123525)
の12決定です。[Completion Evidence checkpoint](https://github.com/reitojike/think-canvas/issues/52#issuecomment-5929157213) と
[Execution Authority checkpoint](https://github.com/reitojike/think-canvas/issues/52#issuecomment-5927944400)
で確定した Option C の byte manifest、external presence authority、hidden-index fail-closed、pure policy / capture /
comparison と classification precedence を維持します。
PR [#56](https://github.com/reitojike/think-canvas/pull/56) / [#57](https://github.com/reitojike/think-canvas/pull/57) /
[#58](https://github.com/reitojike/think-canvas/pull/58) / [#59](https://github.com/reitojike/think-canvas/pull/59) は freeze した reference です。
current main から successor を作り、proven components を選択的に再利用します。
共有 XML の freshness と serialize 後の JSON redaction は completion authority に使いません。
product / tests / Gradle / workflow / device-verification / #53 / security は変更対象外です。
selector と Windows transport の前提は [Issue #40](https://github.com/reitojike/think-canvas/issues/40) と
[PR #51](https://github.com/reitojike/think-canvas/pull/51) の evidence を参照します。

## Review-first の実行順

1. `pwsh -NoProfile -File scripts/run-windows-gmd.ps1 -VerifyStatic` を実行します。
   synthetic repository 内で完結し、Java / Gradle / GMD は起動しません。
2. PowerShell parser、`git diff --check`、`scripts/check-public-boundary.ps1`、exact 2-file diff を確認します。
3. commit / push / PR 作成後、その exact head に top-level `@codex review` を1回依頼します。
   material finding があれば `SECRET_SAFE_SUCCESSOR_HOLD` で停止し、
   correction loop に進みません。canonical review clean までは canary を実行しません。
4. clean 後に Focused Codex を exactly once 実行します。run-owned XML が exact testcase の 1 / 0 / 0 / 0
   であることを確認します。environment blocker は下表に沿って census を残します。
5. 必要なら Claude Code が同じ launcher / source-config authority / focused mode を exactly once 実行します。
   exact fingerprint は init bytes・run binding を含むため run ごとに異なります。共通 source / external /
   policy components と固定 template の一致、各 run 内の before / after fingerprint 一致を別々に確認し、
   異なる run の exact fingerprint が同一だとは報告しません。
   別 Gradle command は使いません。material divergence では停止します。
6. Focused PASS 後だけ Full を実行し、Windows owned XML の 66 / 0 / 0 / 0 と有限 testcase 集合を確認します。
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

schema は `ExecutionAuthorityManifest/v2`、scope は `think-canvas-app-buildSrc/v1` です。
filesystem を直接列挙し、Git の tracked / untracked / ignored 表示で file discovery を絞りません。
map keys と path / environment key records は ordinal order、SHA-256 は uppercase hex、
canonical serialization は compressed JSON の UTF-8 bytes です。null / boolean を固定します。
absolute path は `<worktree>` / `<shared-repo-root>` / `<wrapper-distribution>` に正規化します。

| component | 有限 scope / 保存する内容 |
| --- | --- |
| workingTreeFiles | root の build / settings（Groovy / Kotlin の全候補）、gradle.properties、gradle.lockfile、gradlew / gradlew.bat、wrapper JAR / properties、gradle/**、buildSrc/**、app/**、launcher 自身。各 file の normalized path / present / length / SHA-256。固定候補の不存在も記録 |
| directoryMembership | gradle / buildSrc / app の directory presence / type。actual files を別にhash |
| externalConfig | canonical GRADLE_USER_HOME と active wrapper distribution の gradle.properties。logical path / presenceのみ。存在時拒否し、本文・length・SHA-256を取得しない |
| externalEnvironment | FINITE_ALLOWLIST/v1 policyとsecretValuesRequired=0。除外した親値・key一覧・digestを保存せず、比較対象にしない |
| policyObservations | hidden index flags、init presence / membership、ignored active inputs、DCL / local.properties、propertiesのredirect / selector / unsupported-format facts、JVM option facts、environment collision、selector ownership、wrapper selection |
| invocationContract | mode / single selector / expected count / fixed task argv / canonical home binding、fixed version CODE=1 / NAME=0.1.0、owned init の exact UTF-8 bytes / length / SHA-256 / template、runId、root / app output mapping |

root の build / .gradle / .kotlin、app と buildSrc の build / .gradle / .kotlin / .cxx、
gradle直下の同種cache、.git、別worktree、IDE、diagnostics、Android userdata は探索しません。
source / resources の中の `build` package名は除外しません。
Git-visibleでない source edit、fileの作成・削除、length / hash変更、external properties presence変更、
authority set / policy facts変更を比較で検出します。
新しい includeBuild / apply / projectDir / source redirect 等は scope再確定が必要であり、
既知の外部読込構文を policy で拒否します。任意の build script の読込closureを自動証明する仕組みではありません。
同時 source/config 編集を避けます。before / after の2点観測は途中変更して戻す操作や読取競合を捕捉する
lock / continuous monitor ではありません。

HEAD と branch / git status / staged・unstaged census の digests は sibling provenance に保存し、execution digestには含めません。
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
| canonical home / active distribution の gradle.properties | EXTERNAL_PRIVATE_PROPERTIES_PRESENT / SECRET_SAFETY_POLICY_BLOCK。存在時拒否、読取・hashなし |
| root / module / buildSrc properties の systemProp.gradle.user.home | canonical home redirectとして拒否。Java propertiesのescape / continuationも解釈 |
| project properties のsecret-bearing key | SECRET_SAFETY_POLICY_BLOCK。本文・enclosing hashを保存しない。unsupported formatもhash前に拒否 |
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
child environmentはclear後、次の有限allowlistだけを設定します。Git / probe / Gradleの全childが同じ
`New-Child` を使います。親全体のコピー、未知keyのforward、full inheritanceへのfallbackはありません。

| 分類 | keys / effective values |
| --- | --- |
| Windows runtime path inputs | Path、TEMP、TMP、USERPROFILE、HOMEDRIVE、HOMEPATH、APPDATA、LOCALAPPDATA、ProgramData。path構文を検証し、raw path/value hashをstructured diagnosticsに保存しない |
| Windows fixed | OS-resolved SystemRoot / windir、ComSpec=System32/cmd.exe、PATHEXT=.COM;.EXE;.BAT;.CMD、OS=Windows_NT |
| Tool runtime paths | JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT。上記probe / resolutionで正規化 |
| Launcher homes | GRADLE_USER_HOME、ANDROID_USER_HOME。shared-rootの固定homes |
| Debug version | THINKCANVAS_VERSION_CODE=1、THINKCANVAS_VERSION_NAME=0.1.0。親値をinherit/hashしない |
| Selector | Focusedのexact single classのみ。FullはclassをRemove、tests_regexは常にRemove。その他ORG_GRADLE_PROJECT_*は一律drop |
| Conditional public option | JAVA_TOOL_OPTIONSの既知exact NIO mitigationだけを明示設定。任意親値と4禁止JVM optionsはinheritせず、nonempty inputをJava前にpolicy拒否 |

internal signing 4 keys（THINKCANVAS_INTERNAL_KEYSTORE_FILE / INTERNAL_STORE_PASSWORD /
INTERNAL_KEY_ALIAS / INTERNAL_KEY_PASSWORD）はdebug GMDに不要なので渡しません。
generic token / secret / password / credential / api-key、未知THINKCANVAS、proxy、CI/agent、Android overrideも
allowlist外としてdropします。除外した親secretだけではblockせず、presence/equality/digestもauthorityにしません。
secret-bearing valueのSHA256、enclosing private config hash、salt/keyed digestはpersistent diagnosticsに保存しません。
必要secret inputはこの契約で0件です。追加が必要なら実行前に有限authorityを再確定します。
null assignmentをunset証拠にしません。

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

## RunResultOwnership

各 run は random 128-bit ID を含む新規 directory を確保します。
`build/diagnostics/windows-gmd/<run-id>/gradle-build/root` と `.../app` が root / app の build directory です。
既存 directory、reparse point、namespace 非empty は再利用・削除せず ownership failure にします。
Gradle child 開始直前にも `gradle-build` の empty gate を確認します。

owned init script は launcher の固定 Groovy template から生成し、唯一の `--init-script` argv に渡します。
caller の arbitrary init / extra argv の入口はありません。external user / distribution init 拒否は維持します。
[Gradle 9.8 init script](https://docs.gradle.org/current/userguide/init_scripts.html) の `beforeProject` と
[ProjectLayout.buildDirectory](https://docs.gradle.org/current/dsl/org.gradle.api.file.ProjectLayout.html) を使い、
project evaluation 前に root / app の directory を設定・finalize します。
source sets、dependencies、device、variant、selector、task actions は変更しません。
新しい buildSrc / included build は mapping 未確定として fail closed にします。

task graph 確定時に root / app mapping、expected managed-device task、device / debug variant、
resultsDir / optional xmlResultsDirectory と対象 task の declared outputs の containment を検証します。
固定 `WINDOWS_GMD_OUTPUT_BOUND|<run-id>|:app:pixel7Api37DebugAndroidTest` record を child stdout に出し、
launcher は今回の record を要求します。binding 失敗を shared output へ fallback しません。
AGP 9.4.1 の directory properties に依存する有限契約であり、新構成・AGP変更では再検証が必要です。

読み取る XML は今回 namespace の
`app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml` のみです。
shared `app/build/outputs/androidTest-results/managedDevice/**` は completion authority にしません。
namespace 内 managed-device result の XML が別path・複数・不存在なら ownership failure です。
祖先と descendants の reparse/escape を拒否し、child開始前の不存在、creation / modification time が
child開始後であることを要求します。timestamp / SHA-256 は補助証拠です。
同じ byte array を XML parse / SHA-256 に使い、logical path / length / digest を記録します。
DTD / external resolver を禁止し、suite counters、actual testcase / outcome elements、parent counters、
suite name / device property、duplicate / zero / partial result を検証します。
Focused は exact single Class#method、Full は current main の固定66件の testcase 集合まで一致させます。
unknown testcase / arbitrary XML text は structured diagnostics に保存しません。

pre-run census の明確な same-worktree active Gradle / GMD は `WINDOWS_GMD_PREFLIGHT_BLOCKED` です。
idle daemon、manual `ThinkCanvas_API37` は対象外です。command line はメモリ内で判定し、保存するのは
PID / kind / sameWorktree / activeInvocation / conflict の facts のみです。
census incomplete / unavailable は記録しますが、それだけでは自動blockしません。
post-run に明確な overlap を観測した場合も completion を invalid にします。
自動 kill / cleanup / retry はありません。Android userdata / caches と Room の `app/schemas` は共有されるため、
output relocation を完全 runtime isolation と扱いません。Room schema の bytes 変更は recapture で mutation です。

## StructuredDiagnosticWrite / TerminalCompletionEvidence

structured JSON は `{schemaVersion:1, artifact:<固定file名>, data:<typed metadata>}` の envelope です。
object 構築時に allowlisted metadata、logical path、presence、length、digest、reason、safe testcase identity
のみを入れます。raw secret values、raw process command line、exception/log本文、全environmentは入れません。
serialize 後の JSON redaction と SafeMetadata bypass はありません。
stdout / stderr の plain-text logs だけに secret / path redaction と truncation を適用します。
短い secret と同じ文字が public metadata に偶然含まれることは、secret field の保存とは区別します。
secret-bearing value/verifierはlocalでも保存禁止です。parent secretと偶然同じpublic JSON文字列を
leak oracleにせず、secret-bearing field・hashの不存在とfixed public profileの一致を検証します。
filtering first / redaction secondです。既知private/config valueを長さに関係なくoriginal textのintervalで
redactし、overlapとplaceholder再置換を避けます。短い/boolean/numeric値がstream内にある場合は
保存・streamせずSUPPRESSED_SECRET_SAFETY、通常log I/O failureはUNAVAILABLE_IOをtyped process metadataへ
記録します。textは最大2 MiBのtailに限定し、structured JSONにserialize後のglobal replacementはしません。

各 required JSON は unique temp write → close → strict JSON parse → artifact別 minimum key/type validation →
final publish → final bytes parse/hash validation を通します。timestamp は string のまま保持します。
wrong shape、serialize/depth、parse、write、read-back、digest failure は成功artifactにしません。
namespace は新規で、completion commit 後の再writeを拒否します。power-loss durability は保証しません。

required set は phase の write/process obligation を事前登録し、policy / comparison / provenance / summary と、
成立した before / after manifest、到達した preflight / child metadata / invocation / ownership / XML を含みます。
writer が成功したfileだけに集合を縮めません。未到達 phase は applicability を NOT_APPLICABLE とします。
child process metadataは必須です。stdout / stderrはdiagnostic-onlyで、requiredLogsとexistence-based PASS gateは
ありません。optional logの不存在・削除・改変はcompletion validityに影響しません。log integrityはsealしません。

terminal set は `summary.json` / `classification.txt` / `completion.json` です。
completion は最後の commit marker で、runId / finishedUtc / classification / summarySha256 /
required JSON の path・length・SHA-256 manifest、classification.txtのexact UTF-8 bytes binding /
writeComplete=true を含みます。completion自身をself-hashせず、text logsをmanifestに含めません。
consumer は全参照JSONの existence / parse / minimum schema / digest、summaryとclassificationの整合、
phase applicability、PASSのauthority / init / XML bytes binding を再検証します。
completion 単体、partial PASS、missing/corrupt artifact、digest mismatch は INVALID です。
全 validation 後だけ PASS表示とexit 0を許します。

最優先は `WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE` / INVALID で、original policy、provisional execution、
authority comparison を保持します。result binding / namespace failure は
`WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE` / INVALID で、zero-test / count/name failure と区別します。
known setup / Gradle failure の観測は保持し、診断再保存で execution を再開しません。

`-VerifyStatic` は既存 Execution Authority 14項目 / 8分類ケースに加え、Completion Evidence の
必須17項目、namespace reuse、bindingなし、wrong device、duplicate / partial / DTD XML、
固定66 testcase、short-secret全種、init tamper、incomplete census、stale marker、classification不一致、
wrong schema、corrupt参照 / digest mismatch / completion-last / diagnostic failure precedence を確認します。
synthetic fixtures は ignored build 配下に残し、自動cleanupしません。runtime成功の代用にはしません。

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
| default_boot loadability timeout | phase / tracking / process census保存。同launcher / source-config・固定template / focused modeのClaude comparisonを1回。run固有bindingを区別。Codex blind retry禁止 |
| device lock 600s / MDLockCount 4 | saturated preflightで停止。既存#52のsupported cleanManagedDevices手順は別の明示操作。launcherは自動実行しない |
| tracking recovered / orphan remains | root PID / 親子 / start / owner不在を再確認する既存のmanual recovery手順。broad kill / manual AVD / automatic cleanupは禁止 |
| .lock sidecarのみ | effective count / live ownerのread-only census。residue存在だけで拒否せず、fileを削除しない |
| unexpected '(' / tests=0 | tests_regex禁止、single Class#method、owned XML count/name gate |
| TLS / SEC_E_NO_CREDENTIALS / policy / ACL / long path | 最小probeの既証明mitigationがなければSTOP。sandbox / global config / securityを変更しない |

canaryはexecutorごとにexactly onceであり、workaround / cleanup / retryを積み重ねません。
Codex default_boot timeoutとClaude instrumentation到達が観測された場合は
`CODEX_RUNTIME_INTERACTION_NOT_ROOT_CAUSED` とし、root cause / sandbox単独原因を断定しません。
再現可能なmitigationはdiagnostics保存、Codex blind retry禁止、same-launcher Claude比較1回、
exact-head GitHub ActionsのGMD XMLを独立merge evidenceに使う既存方針です。
Linux CIをWindows Focused / Full PASSやsame-launcher differentialの代用にはしません。

#52のfinal AC mappingではcommon launcher、manifest、hidden-index safety、classification、
Windows Focused / Full XML、Codex/Claude比較、default_boot mitigationを個別に判定します。
全AC成立ならISSUE_52_SECRET_SAFE_PR_MERGE_READY、runtime evidenceだけ不足なら
ISSUE_52_SECRET_SAFE_READY_RUNTIME_FOLLOW_UP_REQUIRED、reviewのmaterial findingなら
SECRET_SAFE_SUCCESSOR_HOLDです。
