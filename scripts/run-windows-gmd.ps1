#Requires -Version 7.3
[CmdletBinding(DefaultParameterSetName = 'Full')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Full')][ValidateRange(1, 100000)][int]$ExpectedTestCount,
    [Parameter(Mandatory, ParameterSetName = 'Focused')][AllowEmptyString()][string]$Test
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$classKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.class'
$regexKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.tests_regex'
$started = [DateTime]::UtcNow
$worktree = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runId = $started.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$diagnostics = Join-Path $worktree "build/diagnostics/windows-gmd/$runId"
[IO.Directory]::CreateDirectory($diagnostics) | Out-Null
$summary = [ordered]@{
    runId = $runId; startedUtc = $started.ToString('o'); mode = $PSCmdlet.ParameterSetName
    requestedTest = $Test; expectedTestCount = $(if ($PSCmdlet.ParameterSetName -eq 'Focused') { 1 } else { $ExpectedTestCount }); phase = 'preflight'
    classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'; gradleExitCode = $null
    appliedMitigation = @(); reason = ''; xml = @()
}
$repoRoot = $worktree
$childEnv = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
$environmentConflict = $false
# Clear ProcessStartInfo.Environment below and copy this case-insensitive map once.
$inheritedEntries = @([Environment]::GetEnvironmentVariables().GetEnumerator())
# Windows canonical spelling wins deterministically over PATH; enumeration order is irrelevant.
foreach ($entry in ($inheritedEntries | Sort-Object { if ($_.Key -ceq 'Path') { 1 } else { 0 } })) {
    $key = if ($entry.Key -ieq 'PATH') { 'Path' } else { [string]$entry.Key }
    if ($childEnv.ContainsKey($key) -and $childEnv[$key] -cne [string]$entry.Value) {
        if ($key -ieq 'Path') {
            $summary.appliedMitigation += 'PATH_CASE_NORMALIZATION: canonical Path wins over PATH'
        } else { $environmentConflict = $true }
    }
    $childEnv[$key] = [string]$entry.Value
}
$redactions = @($childEnv.GetEnumerator() | Where-Object {
    $_.Key -match '(?i)token|secret|password|credential|api.?key' -and $_.Value.Length -ge 4
} | ForEach-Object { $_.Value })

function Protect-Text([string]$Value) {
    foreach ($secret in $redactions) { $Value = $Value.Replace($secret, '<redacted>') }
    foreach ($pair in @(@($worktree, '<worktree>'), @($repoRoot, '<repo-root>'), @($env:USERPROFILE, '<user-profile>'))) {
        if ($pair[0]) {
            $Value = $Value.Replace($pair[0], $pair[1]).Replace($pair[0].Replace('\', '/'), $pair[1])
            $Value = $Value.Replace($pair[0].Replace('\', '\\'), $pair[1])
        }
    }
    $Value = $Value -replace '(?i)(?:ghp_|gho_|github_pat_)[a-z0-9_]+', '<redacted>'
    $Value = $Value -replace '(?i)((?:password|token|secret|api.?key)\s*[=:]\s*)\S+', '$1<redacted>'
    $Value = $Value -replace '(?i)(https?://)[^\s/@]+:[^\s/@]+@', '$1<redacted>@'
    $Value = $Value -replace '(?i)[A-Z]:\\(?:\\)?Users\\(?:\\)?[^\\\r\n]+', '<user-profile>'
    $Value = $Value -replace '(?m)^\s*user\.name = .+$', '    user.name = <redacted>'
    return $Value
}
function Save-Json([string]$Name, $Value) {
    Protect-Text ($Value | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath (Join-Path $diagnostics $Name) -Encoding utf8
}
function New-Child([string]$Executable, [string[]]$Arguments) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $Executable
    $psi.WorkingDirectory = $worktree
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment.Clear()
    foreach ($entry in $childEnv.GetEnumerator()) { $psi.Environment.Add($entry.Key, $entry.Value) }
    foreach ($argument in $Arguments) { $psi.ArgumentList.Add($argument) }
    return $psi
}
function Invoke-Child([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-Child $Executable $Arguments
    $begin = [DateTime]::UtcNow
    try {
        [void]$process.Start()
        # Both pipes are drained concurrently; no cmd.exe or PowerShell native-output pipeline.
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $outTask.GetAwaiter().GetResult()
        $stderr = $errTask.GetAwaiter().GetResult()
        $result = @{ exitCode = $process.ExitCode; stdout = $stdout; stderr = $stderr }
        if ($LogName) {
            foreach ($stream in @('stdout', 'stderr')) {
                $value = Protect-Text $result[$stream]
                $truncated = $value.Length -gt 2MB
                if ($truncated) { $value = '<tail only: log exceeded 2 MiB characters>' + "`n" + $value.Substring($value.Length - 2MB) }
                [IO.File]::WriteAllText((Join-Path $diagnostics "$LogName.$stream.log"), $value)
                $result["${stream}Truncated"] = $truncated
            }
            Save-Json "$LogName.process.json" @{
                startedUtc = $begin.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o')
                exitCode = $result.exitCode; stdoutTruncated = $result.stdoutTruncated; stderrTruncated = $result.stderrTruncated
            }
        }
        return $result
    } finally { $process.Dispose() }
}
function Git([string[]]$Arguments) {
    $result = Invoke-Child 'git.exe' (@('-c', 'core.excludesfile=', '-C', $worktree) + $Arguments) ''
    if ($result.exitCode -ne 0) { throw 'Git source-state capture failed' }
    return $result.stdout.TrimEnd("`r", "`n")
}
function Get-Fingerprint {
    $hashes = @{}
    foreach ($kind in @('unstaged', 'staged')) {
        $temp = Join-Path $diagnostics ([Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            $arguments = @('diff', '--no-ext-diff', '--no-textconv', '--binary', '--full-index', "--output=$temp")
            if ($kind -eq 'staged') { $arguments += '--cached' }
            [void](Git $arguments)
            # Git writes file bytes directly; never hash a PowerShell text pipeline.
            $hashes[$kind] = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash
        } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp } }
    }
    # Census untracked directories without reading Android userdata or other personal content.
    $untrackedCensus = @((Git @('ls-files', '--others', '--directory', '--exclude-standard', '-z')) -split "`0" | Where-Object { $_ })
    $sourcePaths = @('app/src', 'app/schemas', 'scripts', 'docs', 'specs', 'gradle', '.specify', '.agents/skills',
        '.github', 'AGENTS.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', '.gitignore')
    $untracked = @(Git (@('ls-files', '--others', '--exclude-standard', '-z', '--') + $sourcePaths))
    $untrackedPaths = @($untracked -split "`0" | Where-Object { $_ })
    $untrackedHashes = @($untrackedPaths | Sort-Object | ForEach-Object {
        [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $worktree $_) -Algorithm SHA256).Hash }
    })
    $state = [ordered]@{
        head = Git @('rev-parse', 'HEAD'); branch = Git @('branch', '--show-current')
        unstagedSha256 = $hashes.unstaged; stagedSha256 = $hashes.staged
        changedFiles = @( (Git @('diff', '--name-status', '-z')) -split "`0" | Where-Object { $_ })
        stagedFiles = @( (Git @('diff', '--cached', '--name-status', '-z')) -split "`0" | Where-Object { $_ })
        untrackedCensus = $untrackedCensus; untrackedFiles = $untrackedHashes
        dirtyState = Git @('status', '--porcelain=v1', '-z')
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($state | ConvertTo-Json -Depth 10 -Compress))
    $state.fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    return $state
}
function Get-XmlCensus {
    $resultRoot = Join-Path $worktree 'app/build/outputs/androidTest-results/managedDevice'
    if (Test-Path -LiteralPath $resultRoot) {
        @(Get-ChildItem -LiteralPath $resultRoot -Filter '*.xml' -Recurse -File | ForEach-Object {
            @{ path = [IO.Path]::GetRelativePath($worktree, $_.FullName); length = $_.Length
                modifiedUtc = $_.LastWriteTimeUtc.ToString('o'); sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash }
        })
    }
}

try {
    if (-not $IsWindows) { throw 'This launcher requires Windows PowerShell 7.3+' }
    if ($PSCmdlet.ParameterSetName -eq 'Focused') {
        if ($Test -cnotmatch '^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*#[A-Za-z_$][A-Za-z0-9_$]*$') {
            throw 'Focused mode requires one nonempty Class#method; comma lists and tests_regex are rejected'
        }
        $ExpectedTestCount = 1
        $summary.expectedTestCount = 1
    }
    if ((Git @('rev-parse', '--show-toplevel')).Replace('/', '\') -ine $worktree.Replace('/', '\')) {
        throw 'Launcher must belong to the current worktree root'
    }
    $commonDir = Git @('rev-parse', '--path-format=absolute', '--git-common-dir')
    $repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $commonDir))
    if ((Split-Path -Leaf $commonDir) -ne '.git') { throw 'Unsupported shared repository layout' }
    $childEnv['GRADLE_USER_HOME'] = Join-Path $repoRoot '.gradle-user'
    $childEnv['ANDROID_USER_HOME'] = Join-Path $childEnv['GRADLE_USER_HOME'] 'android-user'
    $systemRoot = [Environment]::GetFolderPath('Windows')
    if (-not (Test-Path -LiteralPath (Join-Path $systemRoot 'System32/kernel32.dll'))) { throw 'Windows system directory unavailable' }
    $childEnv['SystemRoot'] = $systemRoot
    $childEnv['windir'] = $systemRoot
    [void]$childEnv.Remove($classKey)
    [void]$childEnv.Remove($regexKey)
    if ($Test) { $childEnv[$classKey] = $Test }
    $selector = @{
        CLASS_EXACT = $(if ($Test) { $childEnv[$classKey] -ceq $Test } else { $false })
        CLASS_UNSET = -not $childEnv.ContainsKey($classKey)
        TESTS_REGEX_UNSET = -not $childEnv.ContainsKey($regexKey)
        value = $Test
    }
    if (-not $selector.TESTS_REGEX_UNSET -or ($Test -and -not $selector.CLASS_EXACT) -or (-not $Test -and -not $selector.CLASS_UNSET)) {
        throw 'Child selector gate failed'
    }
    Save-Json 'selector.json' $selector
    $beforeSource = Get-Fingerprint
    Save-Json 'source-fingerprint.json' $beforeSource
    $summary.sourceFingerprint = $beforeSource.fingerprint
    $summary.worktree = $worktree
    $summary.sharedRepositoryRoot = $repoRoot
    $preflight = [ordered]@{
        worktree = $worktree; head = $beforeSource.head; branch = $beforeSource.branch
        sharedRepositoryRoot = $repoRoot; gradleUserHome = $childEnv['GRADLE_USER_HOME']; androidUserHome = $childEnv['ANDROID_USER_HOME']
        SystemRoot = $childEnv['SystemRoot']; windir = $childEnv['windir']; environmentKeysCaseInsensitive = $true
        selector = $selector; completed = $false
    }
    Save-Json 'preflight.json' $preflight
    if ($environmentConflict) { throw 'Conflicting case-insensitive environment keys other than Path' }

    $processCensus = @{ completeness = 'command-line'; processes = @() }
    try {
        $allProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop)
        $processCensus.processes = @($allProcesses | Where-Object { $_.Name -match '^(javaw?|emulator|qemu.*)\.exe$' } | ForEach-Object {
            @{ pid = $_.ProcessId; parentPid = $_.ParentProcessId; name = $_.Name; started = $_.CreationDate
                kind = $(if ($_.CommandLine -match 'gradle-managed|dev37_google_apis') { 'GMD' } elseif ($_.CommandLine -match 'Gradle') { 'Gradle' } else { 'other' })
                parentPresent = $_.ParentProcessId -in $allProcesses.ProcessId }
        })
    } catch {
        $processCensus.completeness = 'PID/name only; command-line census unavailable'
        $processCensus.processes = @(Get-Process -ErrorAction Stop | Where-Object { $_.ProcessName -match '^(javaw?|emulator|qemu.*)$' } | ForEach-Object {
            @{ pid = $_.Id; name = $_.ProcessName }
        })
    }
    Save-Json 'pre-run-process-census.json' $processCensus
    $tracking = Join-Path $childEnv['ANDROID_USER_HOME'] 'avd/gradle-managed/active_gradle_devices'
    $lockCount = 0
    if (Test-Path -LiteralPath $tracking) {
        $trackingText = [IO.File]::ReadAllText($tracking)
        if ($trackingText -notmatch '^MDLockCount\s+(\d+)\s*$') { throw 'Unknown GMD tracking format; stop without cleanup' }
        $lockCount = [int]$Matches[1]
    }
    Save-Json 'gmd-state.json' @{ trackingPresent = (Test-Path -LiteralPath $tracking); MDLockCount = $lockCount
        lockSidecarPresent = (Test-Path -LiteralPath "$tracking.lock"); automaticCleanup = $false }
    if ($lockCount -ge 4) { throw 'GMD device limit saturated; read-only owner census required before supported recovery' }

    $sdk = $null
    foreach ($key in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        if ($childEnv.ContainsKey($key) -and $childEnv[$key]) { $sdk = $childEnv[$key]; break }
    }
    if (-not $sdk) {
        if (-not $childEnv.ContainsKey('LOCALAPPDATA') -or -not $childEnv['LOCALAPPDATA']) { throw 'LOCALAPPDATA unavailable for SDK fallback' }
        $sdk = Join-Path $childEnv['LOCALAPPDATA'] 'Android/Sdk'
    }
    $sdk = [IO.Path]::GetFullPath($sdk)
    $childEnv['ANDROID_HOME'] = $sdk
    $childEnv['ANDROID_SDK_ROOT'] = $sdk
    $preflight.androidSdk = $sdk
    Save-Json 'preflight.json' $preflight
    $required = @('platform-tools/adb.exe', 'emulator/emulator.exe', 'platforms/android-37.0/android.jar',
        'build-tools/36.0.0/aapt2.exe', 'system-images/android-37.0/google_apis/x86_64/system.img')
    foreach ($relative in $required) { if (-not (Test-Path -LiteralPath (Join-Path $sdk $relative) -PathType Leaf)) { throw "Required SDK component missing: $relative" } }
    $localProperties = Join-Path $worktree 'local.properties'
    if (Test-Path -LiteralPath $localProperties) { throw 'local.properties may override SDK environment; use a worktree without this local override' }

    $javaExe = if ($childEnv.ContainsKey('JAVA_HOME') -and $childEnv['JAVA_HOME']) {
        Join-Path $childEnv['JAVA_HOME'] 'bin/java.exe'
    } else { (Get-Command java.exe -CommandType Application -ErrorAction Stop).Source }
    $java = Invoke-Child $javaExe @('-XshowSettings:properties', '-version') 'java-version'
    if ($java.exitCode -ne 0 -or $java.stderr -notmatch '(?m)^\s*java.home = (.+)\r?$') { throw 'Java version/home probe failed' }
    $javaHome = $Matches[1].Trim()
    $javaExe = Join-Path $javaHome 'bin/java.exe'
    $childEnv['JAVA_HOME'] = $javaHome
    $preflight.javaExecutable = $javaExe
    $preflight.javaHome = $javaHome
    Save-Json 'preflight.json' $preflight
    if ($java.stderr -notmatch '(?m)^\s*java.specification.version = 25\s*$') { throw 'Current repository validation requires Java 25' }
    foreach ($key in @('JAVA_OPTS', 'GRADLE_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
        if ($childEnv.ContainsKey($key) -and $childEnv[$key]) { throw "Unreviewed JVM options in $key; stop before invocation" }
    }
    if ($childEnv.ContainsKey('JAVA_TOOL_OPTIONS') -and $childEnv['JAVA_TOOL_OPTIONS']) {
        # The single proven mitigation is opt-in only, after matching the NIO signature and probe.
        $provenOption = '-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp')
        if ($childEnv['JAVA_TOOL_OPTIONS'] -cne $provenOption) { throw 'Unreviewed JAVA_TOOL_OPTIONS; stop without stacking workarounds' }
        $summary.appliedMitigation += 'JAVA_NIO_UNIXDOMAIN_TMPDIR; operator must retain matching signature and minimal probe evidence'
        if ($summary.appliedMitigation.Count -gt 1) { throw 'Multiple mitigations would be stacked; stop before invocation' }
    }
    foreach ($tool in @(@('adb', 'platform-tools/adb.exe', 'version'), @('emulator', 'emulator/emulator.exe', '-version'))) {
        $version = Invoke-Child (Join-Path $sdk $tool[1]) @($tool[2]) "$($tool[0])-version"
        if ($version.exitCode -ne 0) { throw "$($tool[0]) executable probe failed" }
    }
    $preflight.requiredSdkComponents = $required
    $preflight.processCensusCompleteness = $processCensus.completeness
    Save-Json 'preflight.json' $preflight

    $wrapperProperties = Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties'
    $properties = [IO.File]::ReadAllText($wrapperProperties)
    if ($properties -notmatch '(?m)^distributionUrl=(.+)\r?$') { throw 'Wrapper distribution URL missing' }
    $url = $Matches[1].Trim().Replace('\:', ':')
    if ($url -notmatch '^https://services\.gradle\.org/distributions/(gradle-[\d.]+-bin)\.zip$') { throw 'Unsupported wrapper distribution URL; stop before download' }
    $distributionName = $Matches[1]
    # Gradle PathAssembler: positive MD5(URL bytes), base 36.
    $md5 = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($url))
    $number = [Numerics.BigInteger]::new($md5, $true, $true)
    $base36 = ''
    while ($number -gt 0) { $base36 = '0123456789abcdefghijklmnopqrstuvwxyz'[[int]($number % 36)] + $base36; $number = [Numerics.BigInteger]::Divide($number, 36) }
    $distDir = Join-Path $childEnv['GRADLE_USER_HOME'] "wrapper/dists/$distributionName/$base36"
    $versionName = $distributionName -replace '-bin$', ''
    $cached = (Test-Path -LiteralPath (Join-Path $distDir "$distributionName.zip.ok")) -and
        (Test-Path -LiteralPath (Join-Path $distDir "$versionName/lib") -PathType Container) -and
        (Test-Path -LiteralPath (Join-Path $distDir "$versionName/bin/gradle.bat"))
    Save-Json 'wrapper-cache.json' @{ distributionUrl = $url; cacheDirectory = $distDir; extractedAndVerified = $cached }
    if (-not $cached) {
        $summary.classification = 'WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING'
        throw 'Canonical wrapper distribution missing; no download attempted'
    }
    $wrapperJar = Join-Path $worktree 'gradle/wrapper/gradle-wrapper.jar'
    if (-not (Test-Path -LiteralPath $wrapperJar)) { throw 'Current worktree wrapper JAR missing' }
    # Reject competing selector properties rather than secretly rewrite user/repository configuration.
    foreach ($path in @((Join-Path $worktree 'gradle.properties'), (Join-Path $childEnv['GRADLE_USER_HOME'] 'gradle.properties'))) {
        if ((Test-Path -LiteralPath $path) -and [IO.File]::ReadAllText($path) -match '(?m)^\s*android\.testInstrumentationRunnerArguments\.(class|tests_regex)\s*[=:]') {
            throw 'Competing runner selector in gradle.properties; launcher must own selection'
        }
    }
    $baselineXml = @(Get-XmlCensus)
    Save-Json 'xml-before.json' $baselineXml
    $argv = @('-Dfile.encoding=UTF-8', '-Xmx64m', '-Xms64m', '-Dorg.gradle.appname=gradlew', '-classpath', $wrapperJar,
        'org.gradle.wrapper.GradleWrapperMain', ':app:pixel7Api37DebugAndroidTest', '--rerun', '--no-daemon', '--console=plain',
        '-Pandroid.builder.sdkDownload=false', '-Porg.gradle.java.installations.auto-download=false')
    Save-Json 'invocation.json' @{ executable = $javaExe; argv = $argv; wrapperJarSha256 = (Get-FileHash -LiteralPath $wrapperJar).Hash
        wrapperPropertiesSha256 = (Get-FileHash -LiteralPath $wrapperProperties).Hash; appliedMitigation = $summary.appliedMitigation }
    $summary.phase = 'wrapper-start'
    $preflight.completed = $true
    Save-Json 'preflight.json' $preflight
    $summary.gradleStartedUtc = [DateTime]::UtcNow.ToString('o')
    Save-Json 'summary.json' $summary
    Write-Output "GMD running once; diagnostics: $diagnostics"
    $gradle = Invoke-Child $javaExe $argv 'gradle'
    $summary.gradleExitCode = $gradle.exitCode
    $summary.gradleFinishedUtc = [DateTime]::UtcNow.ToString('o')
    $log = $gradle.stdout + "`n" + $gradle.stderr
    $summary.phase = 'gradle'
    if ($log -match ':app:pixel7Api37Setup') { $summary.phase = 'gmd-setup' }
    if ($log -match 'Starting \d+ tests|INSTRUMENTATION_|\d+ tests completed') { $summary.phase = 'instrumentation' }
    $afterXml = @(Get-XmlCensus)
    Save-Json 'xml-after.json' $afterXml
    $freshXml = @($afterXml | Where-Object {
        $entry = $_
        $old = @($baselineXml | Where-Object { $_.path -ceq $entry.path })
        [DateTime]::Parse($entry.modifiedUtc).ToUniversalTime() -ge [DateTime]::Parse($summary.gradleStartedUtc).ToUniversalTime() -and
            ($old.Count -eq 0 -or $old[0].modifiedUtc -cne $entry.modifiedUtc -or $old[0].sha256 -cne $entry.sha256)
    })
    $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0; testcaseNames = @(); freshFiles = @($freshXml | ForEach-Object { $_.path }) }
    foreach ($entry in $freshXml) {
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create((Join-Path $worktree $entry.path), $settings)
        try { $xml = [Xml.XmlDocument]::new(); $xml.Load($reader) } finally { $reader.Dispose() }
        $suites = @($xml.SelectNodes('//testsuite[not(testsuite)]'))
        if ($suites.Count -eq 0) { throw 'Fresh XML has no test suite' }
        foreach ($suite in $suites) {
            foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
                $attribute = $suite.GetAttribute($key)
                if ($attribute -notmatch '^\d+$') { throw "Fresh XML missing/invalid $key" }
                $totals[$key] += [int]$attribute
            }
            $cases = @($suite.SelectNodes('testcase'))
            if ($cases.Count -ne [int]$suite.GetAttribute('tests')) { throw 'XML testcase census disagrees with suite tests' }
            foreach ($case in $cases) {
                $totals.testcaseNames += $case.GetAttribute('classname') + '#' + $case.GetAttribute('name')
                # A malformed report must not hide failure/skip child elements behind zero counters.
                foreach ($pair in @(@('failure', 'failures'), @('error', 'errors'), @('skipped', 'skipped'))) {
                    if ($case.SelectNodes($pair[0]).Count -gt 0 -and [int]$suite.GetAttribute($pair[1]) -eq 0) { throw 'XML outcome counters disagree with testcase children' }
                }
            }
        }
    }
    $summary.xml = $totals
    Save-Json 'xml-results.json' $totals
    if ($freshXml.Count -gt 0) { $summary.phase = 'xml-results' }
    $xmlGatePassed = $false
    if ($log -match 'Timed out trying to check default_boot .* is loadable') {
        $summary.classification = 'WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT'
    } elseif ($log -match 'Could not acquire device lock|Failed to setup|pixel7Api37Setup FAILED') {
        $summary.classification = 'WINDOWS_GMD_SETUP_FAILURE'
    } elseif ($log -match 'Permission denied: getsockopt|SEC_E_NO_CREDENTIALS|Downloading https.*gradle|Could not (GET|resolve)|UnknownHostException|SSLHandshakeException') {
        $summary.classification = 'WINDOWS_GMD_NETWORK_OR_WRAPPER_BLOCKED'
    } elseif ($freshXml.Count -eq 0 -and $gradle.exitCode -ne 0) {
        $summary.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } elseif ($totals.tests -eq 0) {
        $summary.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'
    } elseif ($totals.tests -ne $ExpectedTestCount -or ($Test -and ($totals.testcaseNames.Count -ne 1 -or $totals.testcaseNames[0] -cne $Test))) {
        $summary.classification = 'WINDOWS_GMD_RESULT_COUNT_MISMATCH'
    } elseif ($totals.failures -gt 0 -or $totals.errors -gt 0 -or $totals.skipped -gt 0) {
        $summary.classification = 'WINDOWS_GMD_TEST_FAILURE'
    } elseif ($gradle.exitCode -ne 0) {
        $summary.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } else { $xmlGatePassed = $true }
    $afterSource = Get-Fingerprint
    Save-Json 'source-fingerprint-after.json' $afterSource
    if ($afterSource.fingerprint -cne $beforeSource.fingerprint) {
        $summary.classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'
        $summary.reason = 'Source state changed during run; comparison/result invalid'
    } elseif ($xmlGatePassed) { $summary.classification = 'WINDOWS_GMD_PASS' }
} catch {
    $summary.reason = Protect-Text $_.Exception.Message
    if ($summary.phase -ne 'preflight' -and $summary.classification -eq 'WINDOWS_GMD_PREFLIGHT_BLOCKED') {
        $summary.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'
    }
} finally {
    $summary.finishedUtc = [DateTime]::UtcNow.ToString('o')
    Save-Json 'summary.json' $summary
    [IO.File]::WriteAllText((Join-Path $diagnostics 'classification.txt'), $summary.classification + "`n")
}
Write-Output $summary.classification
Write-Output "Diagnostics: $diagnostics"
if ($summary.classification -eq 'WINDOWS_GMD_PASS') { exit 0 }
exit 1
