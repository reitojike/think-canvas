#Requires -Version 7.3
[CmdletBinding(DefaultParameterSetName = 'Full')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Full')][ValidateRange(1, 100000)][int]$ExpectedTestCount,
    [Parameter(Mandatory, ParameterSetName = 'Focused')][AllowEmptyString()][string]$Test,
    [Parameter(Mandatory, ParameterSetName = 'Static')][switch]$VerifyStatic,
    [Parameter(ParameterSetName = 'Static')][string]$FixtureRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$classKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.class'
$regexKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.tests_regex'
$worktree = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot = $worktree
$systemRoot = [Environment]::GetFolderPath('Windows')
$childEnv = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
$environmentConflict = $false
$appliedMitigation = @()
foreach ($entry in (@([Environment]::GetEnvironmentVariables().GetEnumerator()) | Sort-Object { if ($_.Key -ceq 'Path') { 1 } else { 0 } })) {
    $key = if ($entry.Key -ieq 'PATH') { 'Path' } else { [string]$entry.Key }
    if ($childEnv.ContainsKey($key) -and $childEnv[$key] -cne [string]$entry.Value) {
        if ($key -ieq 'Path') { $appliedMitigation += 'PATH_CASE_NORMALIZATION' } else { $environmentConflict = $true }
    }
    $childEnv[$key] = [string]$entry.Value
}
$redactions = @($childEnv.GetEnumerator() | Where-Object {
    $_.Key -match '(?i)token|secret|password|credential|api.?key' -and $_.Value.Length -ge 4
} | ForEach-Object { $_.Value })
$configRedactions = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$writeState = @{ failed = $false }
$diagnostics = $null

# Runtime components below are selectively salvaged from frozen #57. The authority
# capture, policy, comparison, resolver and terminal protocol are new for Option C.

function Write-Artifact([string]$Name, $Value, [switch]$SafeMetadata) {
    try {
        $json = ConvertTo-Json -InputObject $Value -Depth 60
        if (-not $SafeMetadata) { $json = Protect-Text $json -ConfigValues }
        [IO.File]::WriteAllText((Join-Path $diagnostics $Name), $json, [Text.UTF8Encoding]::new($false))
        return $true
    } catch { return $false }
}
function Save-Json([string]$Name, $Value) {
    if (-not (Write-Artifact $Name $Value)) {
        $writeState.failed = $true
        throw 'Required diagnostic write failed'
    }
}
function Invoke-Child([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-Child $Executable $Arguments
    $begin = [DateTime]::UtcNow
    try {
        [void]$process.Start()
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $result = @{ exitCode = $process.ExitCode; stdout = $outTask.GetAwaiter().GetResult(); stderr = $errTask.GetAwaiter().GetResult() }
        if ($LogName) {
            foreach ($stream in @('stdout', 'stderr')) {
                $value = Protect-Text $result[$stream] -ConfigValues
                $truncated = $value.Length -gt 2MB
                if ($truncated) { $value = '<tail only>' + "`n" + $value.Substring($value.Length - 2MB) }
                try { [IO.File]::WriteAllText((Join-Path $diagnostics "$LogName.$stream.log"), $value) }
                catch { $writeState.failed = $true; throw 'Required child log write failed' }
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

function Sort-Ordinal([string[]]$Values) {
    $sorted = [string[]]@($Values)
    [Array]::Sort($sorted, [StringComparer]::Ordinal)
    return ,$sorted
}
function Sort-PathRecords([object[]]$Records) {
    $byPath = @{}
    foreach ($record in $Records) {
        if ($byPath.ContainsKey($record.path)) { throw 'Authority path collision' }
        $byPath[$record.path] = $record
    }
    return ,@(foreach ($key in (Sort-Ordinal @($byPath.Keys))) { $byPath[$key] })
}
function Get-AuthorityPath([string]$Path, [string]$DistributionRoot) {
    $absolute = [IO.Path]::GetFullPath($Path).Replace('\', '/')
    foreach ($pair in @(@($DistributionRoot, '<wrapper-distribution>'), @($repoRoot, '<shared-repo-root>'), @($worktree, '<worktree>'))) {
        if (-not $pair[0]) { continue }
        $prefix = [IO.Path]::GetFullPath($pair[0]).Replace('\', '/').TrimEnd('/')
        if ($absolute.Equals($prefix, [StringComparison]::OrdinalIgnoreCase)) { return $pair[1] }
        if ($absolute.StartsWith($prefix + '/', [StringComparison]::OrdinalIgnoreCase)) { return $pair[1] + $absolute.Substring($prefix.Length) }
    }
    throw 'Authority path outside declared roots'
}
function Observe-Path([string]$Path) {
    # Walk from the volume root; never inspect descendants of a redirected ancestor.
    $absolute = [IO.Path]::GetFullPath($Path)
    $chain = [Collections.Generic.Stack[string]]::new()
    $cursor = $absolute
    while ($cursor) { $chain.Push($cursor); $cursor = [IO.Path]::GetDirectoryName($cursor) }
    while ($chain.Count) {
        $candidate = $chain.Pop()
        try { $item = Get-Item -LiteralPath $candidate -Force -ErrorAction Stop }
        catch [Management.Automation.ItemNotFoundException] { return @{ present = $false; kind = 'absent'; item = $null } }
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            return @{ present = $true; kind = 'reparse'; item = $null }
        }
        if ($candidate -cne $absolute -and -not $item.PSIsContainer) {
            return @{ present = $true; kind = 'unsupported-ancestor'; item = $null }
        }
    }
    return @{ present = $true; kind = $(if ($item.PSIsContainer) { 'directory' } else { 'file' }); item = $item }
}
function Test-ExecutionPath([string]$Path) {
    if ($Path -in @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties',
        'gradle.lockfile', 'gradlew', 'gradlew.bat', 'scripts/run-windows-gmd.ps1', 'build.gradle.dcl', 'settings.gradle.dcl', 'local.properties')) { return $true }
    if ($Path -match '^(gradle|app|buildSrc)(/|$)') {
        return $Path -notmatch '^(app|buildSrc)/(build|\.gradle|\.kotlin|\.cxx|\.git)(/|$)' -and $Path -notmatch '^gradle/(build|\.gradle|\.kotlin|\.git)(/|$)'
    }
    return $false
}
function Get-GmdArguments {
    return @('-Dfile.encoding=UTF-8', '-Xmx64m', '-Xms64m', '-Dorg.gradle.appname=gradlew', '-classpath',
        (Join-Path $worktree 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
        ':app:pixel7Api37DebugAndroidTest', '--rerun', '--no-daemon', '--console=plain',
        '-Pandroid.builder.sdkDownload=false', '-Porg.gradle.java.installations.auto-download=false')
}
function Capture-ExecutionAuthority([string]$Mode, [string]$Selector, [int]$Count) {
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $directories = @()
    $unsupported = @()
    $presenceOnly = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $privateValues = [Collections.Generic.List[string]]::new()
    foreach ($path in @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties',
        'gradle.lockfile', 'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
        'scripts/run-windows-gmd.ps1')) { [void]$paths.Add($path) }
    foreach ($root in @('', 'app', 'buildSrc')) {
        foreach ($name in @('build.gradle.dcl', 'settings.gradle.dcl', 'local.properties')) {
            $relative = if ($root) { "$root/$name" } else { $name }
            [void]$paths.Add($relative); [void]$presenceOnly.Add($relative)
        }
        if ($root) {
            foreach ($name in @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties')) {
                [void]$paths.Add("$root/$name")
            }
        }
    }
    foreach ($root in @('gradle', 'buildSrc', 'app')) {
        $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($root)
        while ($pending.Count) {
            $relative = $pending.Pop()
            $observation = Observe-Path (Join-Path $worktree $relative)
            $directories += @{ path = "<worktree>/$relative"; present = $observation.present; kind = $observation.kind }
            if ($observation.kind -eq 'absent') { continue }
            if ($observation.kind -ne 'directory') { $unsupported += "<worktree>/$relative"; continue }
            foreach ($entry in (Get-ChildItem -LiteralPath $observation.item.FullName -Force -ErrorAction Stop)) {
                $path = [IO.Path]::GetRelativePath($worktree, $entry.FullName).Replace('\', '/')
                if (-not (Test-ExecutionPath $path)) { continue }
                if ($entry.PSIsContainer -and -not ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { $pending.Push($path) }
                else { [void]$paths.Add($path) }
            }
        }
    }
    # Filesystem discovery is authoritative; Git only observes anomaly/provenance facts.
    $orderedPaths = Sort-Ordinal @($paths)
    $collision = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $orderedPaths) { if (-not $collision.Add($path)) { $unsupported += "<worktree>/$path" } }
    $ignored = @(Get-IgnoredActiveFiles $orderedPaths)
    foreach ($path in $ignored) { [void]$presenceOnly.Add($path) }
    foreach ($path in $orderedPaths) {
        if ($path -cmatch '(^|/)(local\.properties|(build|settings)\.gradle\.dcl)$') { [void]$presenceOnly.Add($path) }
    }
    $flags = @()
    foreach ($record in ((Git @('ls-files', '-v', '-z')) -split "`0" | Where-Object { $_ })) {
        $path = $record.Substring(2)
        if ((Test-ExecutionPath $path) -and ($record[0] -ceq 'S' -or [char]::IsLower($record[0]))) {
            $flags += @{ path = "<worktree>/$path"; assumeUnchanged = [char]::IsLower($record[0]); skipWorktree = ([string]$record[0]).ToUpperInvariant() -ceq 'S' }
        }
    }
    $files = @(); $propertyGates = @(); $wrapperBytes = $null; $topology = @()
    foreach ($path in $orderedPaths) {
        $observation = Observe-Path (Join-Path $worktree $path)
        $record = [ordered]@{ path = "<worktree>/$path"; present = $observation.present; length = $null; sha256 = $null }
        if ($observation.present -and $observation.kind -ne 'file') { $unsupported += $record.path }
        if ($observation.kind -eq 'file' -and -not $presenceOnly.Contains($path)) {
            $bytes = [IO.File]::ReadAllBytes($observation.item.FullName)
            $record.length = $bytes.LongLength
            $record.sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
            if ($path -ceq 'gradle/wrapper/gradle-wrapper.properties') { $wrapperBytes = $bytes }
            if ($path -cmatch '(^|/)gradle\.properties$') { $propertyGates += Get-PropertyGate $record.path $bytes $privateValues }
            if ($path -cmatch '\.gradle(\.kts)?$') {
                $text = [Text.Encoding]::UTF8.GetString($bytes)
                # Current topology is :app plus automatic buildSrc. New external build
                # logic/source redirects need a separately reviewed finite scope.
                $includeText = if ($path -ceq 'settings.gradle.kts') { $text -creplace '\binclude\(":app"\)', '' } else { $text }
                if ($text -cmatch '\b(includeBuild|apply\s*\(|apply\s+from|mavenLocal\s*\(|projectDir\s*=|srcDirs?\s*[=(]|setSrcDirs\s*\()' -or
                    $includeText -cmatch '\binclude\b') {
                    $topology += $record.path
                }
            }
        }
        $files += $record
    }
    $distribution = Get-DistributionSelection $wrapperBytes
    $external = @(); $init = @(); $initDirectories = @()
    $externalRoots = @($childEnv['GRADLE_USER_HOME'])
    if ($distribution.root) { $externalRoots += $distribution.root }
    foreach ($root in $externalRoots) {
        $path = Join-Path $root 'gradle.properties'
        $observation = Observe-Path $path
        $record = @{ path = Get-AuthorityPath $path $distribution.root; present = $observation.present; length = $null; sha256 = $null }
        if ($observation.present -and $observation.kind -ne 'file') { $unsupported += $record.path }
        if ($observation.kind -eq 'file') {
            $bytes = [IO.File]::ReadAllBytes($observation.item.FullName)
            $record.length = $bytes.LongLength; $record.sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
            $propertyGates += Get-PropertyGate $record.path $bytes $privateValues
        }
        $external += $record
        if ($root -ceq $childEnv['GRADLE_USER_HOME']) {
            foreach ($name in @('init.gradle', 'init.gradle.kts', 'init.gradle.dcl')) {
                $path = Join-Path $root $name; $observation = Observe-Path $path
                $init += @{ path = Get-AuthorityPath $path $distribution.root; present = $observation.present }
                if ($observation.kind -in @('reparse', 'unsupported-ancestor')) { $unsupported += Get-AuthorityPath $path $distribution.root }
            }
        }
        $path = Join-Path $root 'init.d'; $observation = Observe-Path $path
        $initDirectories += @{ path = Get-AuthorityPath $path $distribution.root; present = $observation.present; kind = $observation.kind }
        if ($observation.present -and $observation.kind -ne 'directory') { $unsupported += Get-AuthorityPath $path $distribution.root }
        if ($observation.kind -eq 'directory') {
            foreach ($entry in (Get-ChildItem -LiteralPath $path -Force -ErrorAction Stop)) {
                if ($entry.Name -cmatch '\.gradle(?:\.kts|\.dcl)?$' -and (-not $entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint))) {
                    $init += @{ path = Get-AuthorityPath $entry.FullName $distribution.root; present = $true }
                }
            }
        }
    }
    $environment = @()
    $keys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($key in @('THINKCANVAS_VERSION_CODE', 'THINKCANVAS_VERSION_NAME', 'THINKCANVAS_INTERNAL_KEYSTORE_FILE',
        'THINKCANVAS_INTERNAL_STORE_PASSWORD', 'THINKCANVAS_INTERNAL_KEY_ALIAS', 'THINKCANVAS_INTERNAL_KEY_PASSWORD')) { [void]$keys.Add($key) }
    foreach ($key in $childEnv.Keys) {
        if ($key.StartsWith('ORG_GRADLE_PROJECT_', [StringComparison]::OrdinalIgnoreCase) -and $key -ine $classKey -and $key -ine $regexKey) { [void]$keys.Add($key.ToUpperInvariant()) }
    }
    foreach ($key in (Sort-Ordinal @($keys))) {
        $present = $childEnv.ContainsKey($key); $digest = $null
        if ($present) {
            $value = $childEnv[$key]; $privateValues.Add($value)
            $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($value)))
        }
        $environment += @{ key = $key; present = $present; sha256 = $digest }
    }
    $options = @()
    foreach ($key in @('JAVA_OPTS', 'GRADLE_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')) {
        $nonempty = $childEnv.ContainsKey($key) -and $childEnv[$key].Length -gt 0
        $known = $key -ceq 'JAVA_TOOL_OPTIONS' -and $nonempty -and $childEnv[$key] -ceq ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp'))
        $options += @{ key = $key; nonempty = $nonempty; knownNioOption = $known; forbidden = $nonempty -and -not $known }
    }
    $policy = @{
        hiddenIndexFlags = Sort-PathRecords $flags
        unsupportedPaths = Sort-Ordinal @($unsupported | Select-Object -Unique)
        ignoredActiveFiles = Sort-Ordinal $ignored
        unsupportedDcl = @($files | Where-Object { $_.present -and $_.path -cmatch '(build|settings)\.gradle\.dcl$' } | ForEach-Object { $_.path })
        localProperties = @($files | Where-Object { $_.present -and $_.path -cmatch '(^|/)local\.properties$' } | ForEach-Object { $_.path })
        propertyGates = Sort-PathRecords $propertyGates
        initScripts = Sort-PathRecords $init; initDirectories = Sort-PathRecords $initDirectories
        unsupportedTopology = Sort-Ordinal $topology
        jvmOptions = $options; environmentConflict = $environmentConflict
        selectorOwned = (-not $childEnv.ContainsKey($regexKey)) -and $(if ($Selector) { $childEnv.ContainsKey($classKey) -and $childEnv[$classKey] -ceq $Selector } else { -not $childEnv.ContainsKey($classKey) })
        wrapperSelection = $distribution.status
    }
    $manifest = @{
        schema = 'ExecutionAuthorityManifest/v1'; scope = 'think-canvas-app-buildSrc/v1'
        workingTreeFiles = $files; directoryMembership = Sort-PathRecords $directories
        externalConfig = Sort-PathRecords $external; externalEnvironment = $environment; policyObservations = $policy
        invocationContract = @{ mode = $Mode; selector = $Selector; expectedTestCount = $Count
            argv = @((Get-GmdArguments) | ForEach-Object { $_.Replace($worktree, '<worktree>').Replace('\', '/') })
            gradleUserHome = '<shared-repo-root>/.gradle-user'; androidUserHome = '<shared-repo-root>/.gradle-user/android-user' }
    }
    # Canonical maps + ordinal arrays; snapshot serialization excludes provenance/runtime/private values.
    $canonicalJson = ConvertTo-Json -InputObject (ConvertTo-CanonicalValue $manifest) -Depth 60 -Compress
    return @{ manifest = $manifest; canonicalJson = $canonicalJson; fingerprint = Get-AuthorityDigest $manifest
        distribution = $distribution; privateValues = $privateValues.ToArray() }
}
function Get-PropertyGate([string]$Path, [byte[]]$Bytes, $PrivateValues) {
    try {
        $pairs = Read-AuthorityProperties $Bytes
        foreach ($value in $pairs.Values) { $PrivateValues.Add($value) }
        return @{ path = $Path; unsupportedFormat = $false; redirectPresent = $pairs.ContainsKey('systemProp.gradle.user.home')
            selectorPresent = $pairs.ContainsKey('android.testInstrumentationRunnerArguments.class') -or $pairs.ContainsKey('android.testInstrumentationRunnerArguments.tests_regex') }
    } catch { return @{ path = $Path; unsupportedFormat = $true; redirectPresent = $false; selectorPresent = $false } }
}
function Get-DistributionSelection([byte[]]$Bytes) {
    $invalid = @{ status = 'UNSUPPORTED_WRAPPER_SELECTION'; root = $null; cacheDirectory = $null; name = $null; url = $null }
    if ($null -eq $Bytes) { return $invalid }
    try { $pairs = Read-AuthorityProperties $Bytes } catch { return $invalid }
    if (-not $pairs.ContainsKey('distributionUrl')) { return $invalid }
    $url = $pairs['distributionUrl']
    if ($url -cnotmatch '^https://services\.gradle\.org/distributions/(gradle-[\d.]+-bin)\.zip$') { return $invalid }
    $name = $Matches[1]
    foreach ($key in @('distributionBase', 'zipStoreBase')) { if ($pairs.ContainsKey($key) -and $pairs[$key] -cne 'GRADLE_USER_HOME') { return $invalid } }
    foreach ($key in @('distributionPath', 'zipStorePath')) { if ($pairs.ContainsKey($key) -and $pairs[$key] -cne 'wrapper/dists') { return $invalid } }
    $md5 = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($url))
    $number = [Numerics.BigInteger]::new($md5, $true, $true); $base36 = ''
    while ($number -gt 0) { $base36 = '0123456789abcdefghijklmnopqrstuvwxyz'[[int]($number % 36)] + $base36; $number = [Numerics.BigInteger]::Divide($number, 36) }
    if (-not $base36) { $base36 = '0' }
    $cache = Join-Path $childEnv['GRADLE_USER_HOME'] "wrapper/dists/$name/$base36"
    return @{ status = 'SUPPORTED'; url = $url; name = $name; cacheDirectory = $cache; root = Join-Path $cache ($name -replace '-bin$', '') }
}
function Evaluate-ExecutionAuthorityPolicy($Manifest) {
    $p = $Manifest.policyObservations
    $reasons = @()
    if ($p.hiddenIndexFlags.Count) { $reasons += 'HIDDEN_INDEX_FLAG_PRESENT' }
    if ($p.unsupportedPaths.Count) { $reasons += 'UNSUPPORTED_AUTHORITY_PATH' }
    if (@($p.initScripts | Where-Object { $_.present }).Count) { $reasons += 'EXTERNAL_INIT_PRESENT' }
    if ($p.ignoredActiveFiles.Count) { $reasons += 'IGNORED_ACTIVE_SOURCE_CONFIG' }
    if ($p.unsupportedDcl.Count) { $reasons += 'UNSUPPORTED_DCL_PRESENT' }
    if ($p.unsupportedTopology.Count) { $reasons += 'UNSUPPORTED_EXECUTION_TOPOLOGY' }
    if ($p.localProperties.Count) { $reasons += 'LOCAL_PROPERTIES_PRESENT' }
    if (@($p.propertyGates | Where-Object { $_.unsupportedFormat }).Count) { $reasons += 'UNSUPPORTED_PROPERTIES_FORMAT' }
    if (@($p.propertyGates | Where-Object { $_.redirectPresent }).Count) { $reasons += 'GRADLE_USER_HOME_REDIRECT' }
    if (@($p.propertyGates | Where-Object { $_.selectorPresent }).Count) { $reasons += 'COMPETING_PROPERTY_SELECTOR' }
    if (@($p.jvmOptions | Where-Object { $_.forbidden }).Count) { $reasons += 'FORBIDDEN_JVM_OPTION' }
    if ($p.environmentConflict) { $reasons += 'ENVIRONMENT_KEY_CONFLICT' }
    if (-not $p.selectorOwned) { $reasons += 'SELECTOR_OWNERSHIP_FAILURE' }
    if ($p.wrapperSelection -cne 'SUPPORTED') { $reasons += 'UNSUPPORTED_WRAPPER_SELECTION' }
    return @{ result = $(if ($reasons.Count) { 'BLOCKED' } else { 'ALLOWED' }); reasons = $reasons }
}
function Compare-ExecutionAuthority($Before, $After) {
    # Compare the full canonical representation as well as its digest, never Git state.
    $changed = $Before.canonicalJson -cne $After.canonicalJson -or $Before.fingerprint -cne $After.fingerprint
    $components = @()
    foreach ($key in (Sort-Ordinal @($Before.manifest.Keys))) {
        if ((Get-AuthorityDigest $Before.manifest[$key]) -cne (Get-AuthorityDigest $After.manifest[$key])) { $components += $key }
    }
    return @{ result = $(if ($changed) { 'MUTATED' } else { 'UNCHANGED' }); changedComponents = $components
        beforeFingerprint = $Before.fingerprint; afterFingerprint = $After.fingerprint }
}
function Resolve-FinalClassification($State) {
    $classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'; $reason = 'INCOMPLETE'; $validity = 'INCOMPLETE'; $phase = 'execution'
    if ($State.writeFailed) { $classification = 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE'; $reason = 'REQUIRED_EVIDENCE_WRITE_FAILED'; $validity = 'INVALID'; $phase = 'write' }
    elseif ($State.capture -ceq 'FAILURE') { $classification = 'WINDOWS_GMD_SOURCE_CONFIG_CAPTURE_FAILURE'; $reason = 'INITIAL_CAPTURE_FAILED'; $validity = 'INVALID'; $phase = 'initial' }
    elseif ($State.post -ceq 'RECAPTURE_FAILURE') { $classification = 'WINDOWS_GMD_SOURCE_CONFIG_CAPTURE_FAILURE'; $reason = 'FINAL_RECAPTURE_FAILED'; $validity = 'INVALID'; $phase = 'recapture' }
    elseif ($State.post -ceq 'MUTATED' -or ($State.policy.result -ceq 'ALLOWED' -and $State.afterPolicy.result -ceq 'BLOCKED')) {
        $classification = 'WINDOWS_GMD_SOURCE_CONFIG_MUTATION'; $reason = 'AUTHORITY_CHANGED'; $validity = 'INVALID'; $phase = 'compare'
    }
    elseif ($State.policy.result -ceq 'BLOCKED') { $classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'; $reason = $State.policy.reasons -join ','; $validity = 'VALID_BLOCK'; $phase = 'policy' }
    elseif ($State.execution.classification -and $State.execution.state -cne 'PASS_CANDIDATE') {
        $classification = $State.execution.classification; $reason = $State.execution.reason; $validity = 'VALID_FAILURE'
    }
    elseif ($State.execution.state -ceq 'PASS_CANDIDATE' -and $State.execution.xmlGatePassed -and $State.execution.runtimePreflightPassed -and
        $State.execution.gradleExitCode -eq 0 -and $State.policy.result -ceq 'ALLOWED' -and $State.afterPolicy.result -ceq 'ALLOWED' -and $State.post -ceq 'UNCHANGED') {
        $classification = 'WINDOWS_GMD_PASS'; $reason = 'ALL_GATES_PASSED'; $validity = 'VALID_COMPLETION'
    }
    return @{ classification = $classification; reason = $reason; validity = $validity; phase = $phase
        originalPolicy = $State.policy; provisionalExecution = $State.execution; postAuthority = $State.post }
}

function Protect-Text([string]$Value, [switch]$ConfigValues) {
    if ($ConfigValues) {
        # Values remain in memory only. Short values also must not escape through child logs.
        foreach ($secret in ($configRedactions | Sort-Object Length -Descending)) {
            if ($secret.Length) { $Value = $Value.Replace($secret, '<redacted>') }
        }
    }
    foreach ($secret in $redactions) { $Value = $Value.Replace($secret, '<redacted>') }
    foreach ($pair in @(@($worktree, '<worktree>'), @($repoRoot, '<shared-repo-root>'), @($env:USERPROFILE, '<user-profile>'))) {
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

function Git([string[]]$Arguments) {
    $result = Invoke-Child 'git.exe' (@('-c', 'core.excludesfile=', '-C', $worktree) + $Arguments) ''
    if ($result.exitCode -ne 0) { throw 'Git source-state capture failed' }
    return $result.stdout.TrimEnd("`r", "`n")
}

function ConvertTo-CanonicalValue($Value) {
    if ($Value -is [Collections.IDictionary]) {
        $keys = [string[]]@($Value.Keys)
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $result = [ordered]@{}
        foreach ($key in $keys) { $result[$key] = ConvertTo-CanonicalValue $Value[$key] }
        return $result
    }
    if ($Value -is [Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @($Value | ForEach-Object { ConvertTo-CanonicalValue $_ })
        return ,$items
    }
    return $Value
}

function Get-AuthorityDigest($Value) {
    $json = ConvertTo-Json -InputObject (ConvertTo-CanonicalValue $Value) -Depth 60 -Compress
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json)))
}

function ConvertFrom-PropertyEscape([string]$Text) {
    $result = [Text.StringBuilder]::new()
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $char = $Text[$i]
        if ($char -eq '\') {
            $i++
            if ($i -ge $Text.Length) { throw 'Invalid properties escape' }
            $char = $Text[$i]
            if ($char -ceq 'u') {
                if ($i + 4 -ge $Text.Length -or $Text.Substring($i + 1, 4) -cnotmatch '^[0-9a-fA-F]{4}$') {
                    throw 'Invalid properties unicode escape'
                }
                $char = [char][Convert]::ToInt32($Text.Substring($i + 1, 4), 16)
                $i += 4
            } else {
                $char = switch -CaseSensitive ($char) { 't' { "`t" }; 'n' { "`n" }; 'r' { "`r" }; 'f' { "`f" }; default { $char } }
            }
        }
        [void]$result.Append($char)
    }
    return $result.ToString()
}

function Read-AuthorityProperties([byte[]]$Bytes) {
    # java.util.Properties.load(InputStream): Latin-1, continuation, escaped keys/separators.
    $text = [Text.Encoding]::Latin1.GetString($Bytes)
    $logical = ''
    $continuing = $false
    $pairs = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in ($text -split "\r\n|\n|\r")) {
        $part = $line.TrimStart([char[]]@(' ', "`t", "`f"))
        if (-not $continuing -and ($part.Length -eq 0 -or $part[0] -in @('#', '!'))) { continue }
        $logical += $part
        $slashes = 0
        for ($i = $logical.Length - 1; $i -ge 0 -and $logical[$i] -eq '\'; $i--) { $slashes++ }
        $continuing = ($slashes % 2) -eq 1
        if ($continuing) { $logical = $logical.Substring(0, $logical.Length - 1); continue }
        $end = 0
        for (; $end -lt $logical.Length; $end++) {
            if ($logical[$end] -eq '\') { $end++; continue }
            if ($logical[$end] -in @('=', ':', ' ', "`t", "`f")) { break }
        }
        $key = ConvertFrom-PropertyEscape $logical.Substring(0, $end)
        $start = $end
        while ($start -lt $logical.Length -and $logical[$start] -in @(' ', "`t", "`f")) { $start++ }
        if ($start -lt $logical.Length -and $logical[$start] -in @('=', ':')) { $start++ }
        while ($start -lt $logical.Length -and $logical[$start] -in @(' ', "`t", "`f")) { $start++ }
        $pairs[$key] = ConvertFrom-PropertyEscape $logical.Substring($start)
        $logical = ''
    }
    if ($continuing) { throw 'Incomplete properties continuation; authority cannot be established' }
    return ,$pairs
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

function Get-IgnoredActiveFiles([string[]]$Paths) {
    if ($Paths.Count -eq 0) { return }
    $psi = New-Child 'git.exe' @('-c', 'core.excludesfile=', '-C', $worktree, 'check-ignore', '-z', '--stdin')
    $psi.RedirectStandardInput = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    try {
        [void]$process.Start()
        $out = $process.StandardOutput.ReadToEndAsync()
        $err = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Write(($Paths -join "`0") + "`0")
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $out.GetAwaiter().GetResult()
        [void]$err.GetAwaiter().GetResult()
        if ($process.ExitCode -notin @(0, 1)) { throw 'Ignored source/config census failed' }
        $output -split "`0" | Where-Object { $_ }
    } finally { $process.Dispose() }
}

function Execute-Gmd($Capture, $Provenance) {
    $outcome = @{ state = 'NOT_STARTED'; classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'; reason = ''
        phase = 'preflight'; xmlGatePassed = $false; runtimePreflightPassed = $false; gradleExitCode = $null
        appliedMitigation = @($appliedMitigation); xml = @(); gradleStartedUtc = $null; gradleFinishedUtc = $null }
    $preflight = @{ worktree = $worktree; sharedRepositoryRoot = $repoRoot; head = $Provenance.head; branch = $Provenance.branch
        gradleUserHome = $childEnv['GRADLE_USER_HOME']; androidUserHome = $childEnv['ANDROID_USER_HOME']
        SystemRoot = $systemRoot; windir = $systemRoot; environmentKeysCaseInsensitive = $true; completed = $false }
    $wrapperProperties = Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties'
    try {
        if (-not $IsWindows) { throw 'Windows required' }
        if (-not (Test-Path -LiteralPath (Join-Path $systemRoot 'System32/kernel32.dll'))) { throw 'Windows system directory unavailable' }
        $selection = $Capture.distribution
        $cache = $selection.cacheDirectory
        $cached = (Test-Path -LiteralPath (Join-Path $cache ($selection.name + '.zip.ok')) -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $selection.root 'lib') -PathType Container) -and
            (Test-Path -LiteralPath (Join-Path $selection.root 'bin/gradle.bat') -PathType Leaf)
        Save-Json 'wrapper-cache.json' @{ distributionUrl = $selection.url; cacheDirectory = $cache; extractedAndVerified = $cached }
        if (-not $cached) { $outcome.classification = 'WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING'; throw 'Canonical wrapper distribution missing; no download attempted' }
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
    if ($childEnv.ContainsKey('JAVA_TOOL_OPTIONS') -and $childEnv['JAVA_TOOL_OPTIONS']) {
        # The single proven mitigation is opt-in only, after matching the NIO signature and probe.
        $provenOption = '-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp')
        if ($childEnv['JAVA_TOOL_OPTIONS'] -cne $provenOption) { throw 'Unreviewed JAVA_TOOL_OPTIONS; stop without stacking workarounds' }
        $outcome.appliedMitigation += 'JAVA_NIO_UNIXDOMAIN_TMPDIR; operator must retain matching signature and minimal probe evidence'
        if ($outcome.appliedMitigation.Count -gt 1) { throw 'Multiple mitigations would be stacked; stop before invocation' }
    }
    foreach ($tool in @(@('adb', 'platform-tools/adb.exe', 'version'), @('emulator', 'emulator/emulator.exe', '-version'))) {
        $version = Invoke-Child (Join-Path $sdk $tool[1]) @($tool[2]) "$($tool[0])-version"
        if ($version.exitCode -ne 0) { throw "$($tool[0]) executable probe failed" }
    }
    $preflight.requiredSdkComponents = $required
    $preflight.processCensusCompleteness = $processCensus.completeness
    Save-Json 'preflight.json' $preflight

    $wrapperJar = Join-Path $worktree 'gradle/wrapper/gradle-wrapper.jar'
    if (-not (Test-Path -LiteralPath $wrapperJar)) { throw 'Current worktree wrapper JAR missing' }
    $baselineXml = @(Get-XmlCensus)
    Save-Json 'xml-before.json' $baselineXml
    $argv = Get-GmdArguments
    Save-Json 'invocation.json' @{ executable = $javaExe; argv = $argv; wrapperJarSha256 = (Get-FileHash -LiteralPath $wrapperJar).Hash
        wrapperPropertiesSha256 = (Get-FileHash -LiteralPath $wrapperProperties).Hash; appliedMitigation = $outcome.appliedMitigation }
    $outcome.phase = 'wrapper-start'
    $preflight.completed = $true
    $outcome.runtimePreflightPassed = $true
    $outcome.state = 'EXECUTING'
    Save-Json 'preflight.json' $preflight
    $outcome.gradleStartedUtc = [DateTime]::UtcNow.ToString('o')
    # Execution remains provisional until recapture and terminal write.
    Write-Host "GMD running once; diagnostics: $diagnostics"
    $gradle = Invoke-Child $javaExe $argv 'gradle'
    $outcome.gradleExitCode = $gradle.exitCode
    $outcome.gradleFinishedUtc = [DateTime]::UtcNow.ToString('o')
    $log = $gradle.stdout + "`n" + $gradle.stderr
    $outcome.phase = 'gradle'
    if ($log -match ':app:pixel7Api37Setup') { $outcome.phase = 'gmd-setup' }
    if ($log -match 'Starting \d+ tests|INSTRUMENTATION_|\d+ tests completed') { $outcome.phase = 'instrumentation' }
    $afterXml = @(Get-XmlCensus)
    Save-Json 'xml-after.json' $afterXml
    $freshXml = @($afterXml | Where-Object {
        $entry = $_
        $old = @($baselineXml | Where-Object { $_.path -ceq $entry.path })
        [DateTime]::Parse($entry.modifiedUtc).ToUniversalTime() -ge [DateTime]::Parse($outcome.gradleStartedUtc).ToUniversalTime() -and
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
    $outcome.xml = $totals
    Save-Json 'xml-results.json' $totals
    if ($freshXml.Count -gt 0) { $outcome.phase = 'xml-results' }
    $outcome.xmlGatePassed = $false
    if ($log -match 'Timed out trying to check default_boot .* is loadable') {
        $outcome.classification = 'WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT'
    } elseif ($log -match 'Could not acquire device lock|Failed to setup|pixel7Api37Setup FAILED') {
        $outcome.classification = 'WINDOWS_GMD_SETUP_FAILURE'
    } elseif ($log -match 'Permission denied: getsockopt|SEC_E_NO_CREDENTIALS|Downloading https.*gradle|Could not (GET|resolve)|UnknownHostException|SSLHandshakeException') {
        $outcome.classification = 'WINDOWS_GMD_NETWORK_OR_WRAPPER_BLOCKED'
    } elseif ($freshXml.Count -eq 0 -and $gradle.exitCode -ne 0) {
        $outcome.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } elseif ($totals.tests -eq 0) {
        $outcome.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'
    } elseif ($totals.tests -ne $ExpectedTestCount -or ($Test -and ($totals.testcaseNames.Count -ne 1 -or $totals.testcaseNames[0] -cne $Test))) {
        $outcome.classification = 'WINDOWS_GMD_RESULT_COUNT_MISMATCH'
    } elseif ($totals.failures -gt 0 -or $totals.errors -gt 0 -or $totals.skipped -gt 0) {
        $outcome.classification = 'WINDOWS_GMD_TEST_FAILURE'
    } elseif ($gradle.exitCode -ne 0) {
        $outcome.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } else { $outcome.xmlGatePassed = $true; $outcome.state = 'PASS_CANDIDATE'; $outcome.classification = '' }

    } catch {
        $outcome.reason = Protect-Text $_.Exception.Message -ConfigValues
        if ($outcome.state -eq 'EXECUTING') { $outcome.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION' }
        $outcome.state = 'FAILURE'
    }
    if ($outcome.state -eq 'EXECUTING') { $outcome.state = 'FAILURE' }
    if ($outcome.state -eq 'FAILURE' -and -not $outcome.reason) { $outcome.reason = 'OBSERVED_' + $outcome.classification }
    return $outcome
}

function Get-Provenance {
    return @{ head = Git @('rev-parse', 'HEAD'); branch = Git @('branch', '--show-current')
        status = @((Git @('status', '--porcelain=v1', '-z')) -split "`0" | Where-Object { $_ })
        staged = @((Git @('diff', '--cached', '--name-status', '-z')) -split "`0" | Where-Object { $_ })
        unstaged = @((Git @('diff', '--name-status', '-z')) -split "`0" | Where-Object { $_ }) }
}
function New-PhaseState {
    return @{ capture = 'NOT_ATTEMPTED'; policy = @{ result = 'NOT_EVALUATED'; reasons = @() }
        execution = @{ state = 'NOT_STARTED'; classification = ''; reason = ''; xmlGatePassed = $false; runtimePreflightPassed = $false; gradleExitCode = $null }
        post = 'NOT_APPLICABLE'; afterPolicy = @{ result = 'NOT_EVALUATED'; reasons = @() }; writeFailed = $false }
}
function Write-TerminalEvidence($Record, $Before, $After, $Comparison, $Provenance) {
    $records = [ordered]@{ 'policy.json' = @{ before = $Record.axes.policy; after = $Record.axes.afterPolicy }
        'comparison.json' = $Comparison; 'provenance.json' = $Provenance }
    if ($null -ne $Before) { $records['manifest-before.json'] = @{ manifest = $Before.manifest; fingerprint = $Before.fingerprint } }
    if ($null -ne $After) { $records['manifest-after.json'] = @{ manifest = $After.manifest; fingerprint = $After.fingerprint } }
    foreach ($name in $records.Keys) {
        if (-not (Write-Artifact $name $records[$name] -SafeMetadata)) { return $false }
    }
    if (-not (Write-Artifact 'summary.json' $Record -SafeMetadata)) { return $false }
    try { [IO.File]::WriteAllText((Join-Path $diagnostics 'classification.txt'), $Record.final.classification + "`n") }
    catch { return $false }
    # Completion is committed last. Partial/stale PASS files without this matching
    # runId, finished timestamp and summary digest never constitute terminal evidence.
    return Write-Artifact 'completion.json' @{ runId = $Record.runId; finishedUtc = $Record.finishedUtc
        classification = $Record.final.classification; writeComplete = $true
        summarySha256 = (Get-FileHash -LiteralPath (Join-Path $diagnostics 'summary.json')).Hash } -SafeMetadata
}
function Initialize-FixtureContext([string]$Root) {
    $script:worktree = [IO.Path]::GetFullPath($Root)
    $script:repoRoot = $script:worktree
    $script:childEnv = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
    $script:childEnv['GRADLE_USER_HOME'] = Join-Path $script:repoRoot '.gradle-user'
    $script:childEnv['ANDROID_USER_HOME'] = Join-Path $script:childEnv['GRADLE_USER_HOME'] 'android-user'
    $script:childEnv['Path'] = $env:Path
    $script:childEnv['SystemRoot'] = $systemRoot
    $script:childEnv['windir'] = $systemRoot
    $script:environmentConflict = $false
}
function Verify-StaticFixtures {
    # Isolated synthetic repository only. No Java, Gradle, SDK or GMD invocation.
    $fixture = Join-Path $worktree ('build/diagnostics/authority-fixtures/' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($fixture)
    $launcherPath = Join-Path $PSScriptRoot 'run-windows-gmd.ps1'
    Initialize-FixtureContext $fixture
    $script:diagnostics = Join-Path $fixture 'build/evidence'
    [void][IO.Directory]::CreateDirectory($diagnostics)
    foreach ($directory in @('gradle/wrapper', 'app/src/main', 'app/src/main/build', 'scripts', 'docs')) {
        [void][IO.Directory]::CreateDirectory((Join-Path $fixture $directory))
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'gradle/wrapper/gradle-wrapper.properties'), "distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`n")
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/Input.kt'), 'one')
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/build/Package.kt'), 'package source')
    [IO.File]::WriteAllText((Join-Path $fixture 'docs/note.md'), 'provenance')
    [IO.File]::Copy($launcherPath, (Join-Path $fixture 'scripts/run-windows-gmd.ps1'))
    [void](Git @('init', '--quiet')); [void](Git @('add', '.'))
    $results = [Collections.Generic.List[string]]::new()
    function Check([string]$Name, [bool]$Condition) {
        if (-not $Condition) { throw "Static fixture failed: $Name" }
        $results.Add($Name)
    }
    function Snapshot { Capture-ExecutionAuthority 'Full' '' 66 }
    function PassState {
        $s = New-PhaseState; $s.capture = 'SUCCESS'; $s.policy = @{ result = 'ALLOWED'; reasons = @() }; $s.afterPolicy = $s.policy; $s.post = 'UNCHANGED'
        $s.execution = @{ state = 'PASS_CANDIDATE'; classification = ''; reason = ''; xmlGatePassed = $true; runtimePreflightPassed = $true; gradleExitCode = 0 }
        return $s
    }
    $one = Snapshot; $two = Snapshot
    Check '01 same bytes same fingerprint' ($one.fingerprint -ceq $two.fingerprint)
    Check 'scope source package named build' (@($one.manifest.workingTreeFiles | Where-Object { $_.path -ceq '<worktree>/app/src/main/build/Package.kt' }).Count -eq 1)
    [void](Git @('update-index', '--assume-unchanged', 'app/src/main/Input.kt'))
    $hidden = Snapshot
    Check '02 assume-unchanged blocked' ('HIDDEN_INDEX_FLAG_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $hidden.manifest).reasons)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/Input.kt'), 'two')
    $hiddenEdit = Snapshot
    Check '04 hidden edit changes byte fingerprint' ($hidden.fingerprint -cne $hiddenEdit.fingerprint)
    [void](Git @('update-index', '--no-assume-unchanged', 'app/src/main/Input.kt'))
    [void](Git @('update-index', '--skip-worktree', 'app/src/main/Input.kt'))
    $skipBefore = Snapshot
    Check '03 skip-worktree blocked' ('HIDDEN_INDEX_FLAG_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $skipBefore.manifest).reasons)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/Input.kt'), 'skip edit')
    Check 'skip-worktree hidden edit changes fingerprint' ($skipBefore.fingerprint -cne (Snapshot).fingerprint)
    [IO.File]::Delete((Join-Path $fixture 'app/src/main/Input.kt'))
    Check 'missing flagged input blocked' ('HIDDEN_INDEX_FLAG_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    [void](Git @('update-index', '--no-skip-worktree', 'app/src/main/Input.kt'))
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/Input.kt'), 'one')
    [void](Git @('update-index', '--assume-unchanged', 'docs/note.md'))
    Check 'out-of-scope hidden flag allowed' ((Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).result -ceq 'ALLOWED')
    [void][IO.Directory]::CreateDirectory($childEnv['GRADLE_USER_HOME'])
    $initPath = Join-Path $childEnv['GRADLE_USER_HOME'] 'init.gradle'
    [IO.File]::WriteAllText($initPath, 'synthetic init')
    $initBefore = Snapshot; $initAfter = Snapshot; $initPolicy = Evaluate-ExecutionAuthorityPolicy $initBefore.manifest
    Check '05 / checkpoint case 2 init capture succeeds policy blocks' ($initPolicy.result -ceq 'BLOCKED' -and 'EXTERNAL_INIT_PRESENT' -cin $initPolicy.reasons)
    $state = PassState; $state.policy = $initPolicy; $state.afterPolicy = $initPolicy; $state.execution = (New-PhaseState).execution
    $state.post = (Compare-ExecutionAuthority $initBefore $initAfter).result
    $decision = Resolve-FinalClassification $state
    Check '06 / checkpoint case 3 same block preserved' ($decision.classification -ceq 'WINDOWS_GMD_PREFLIGHT_BLOCKED' -and $decision.reason -ceq 'EXTERNAL_INIT_PRESENT')
    [IO.File]::Delete($initPath)
    $state = New-PhaseState; $state.capture = 'FAILURE'
    Check '07 / checkpoint case 1 initial capture failure' ((Resolve-FinalClassification $state).reason -ceq 'INITIAL_CAPTURE_FAILED')
    # Actually unreadable-as-file capture, rather than only injected resolver state.
    $bad = Join-Path $fixture 'gradle/wrapper/gradle-wrapper.properties'
    $lock = [IO.File]::Open($bad, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try { $failed = $false; try { [void](Snapshot) } catch { $failed = $true }; Check 'unreadable bytes capture failure' $failed } finally { $lock.Dispose() }
    $state = PassState; $state.post = 'MUTATED'
    Check '08 / checkpoint case 4 PASS candidate mutation' ((Resolve-FinalClassification $state).classification -ceq 'WINDOWS_GMD_SOURCE_CONFIG_MUTATION')
    $state = PassState; $state.post = 'RECAPTURE_FAILURE'
    Check '09 / checkpoint case 6 recapture failure' ((Resolve-FinalClassification $state).reason -ceq 'FINAL_RECAPTURE_FAILED')
    $state = PassState; $state.post = 'MUTATED'; $state.execution.state = 'FAILURE'; $state.execution.classification = 'WINDOWS_GMD_TEST_FAILURE'; $state.execution.reason = 'OBSERVED_TEST_FAILURE'
    $decision = Resolve-FinalClassification $state
    Check '10 / checkpoint case 5 failure mutation precedence' ($decision.classification -ceq 'WINDOWS_GMD_SOURCE_CONFIG_MUTATION' -and $decision.provisionalExecution.classification -ceq 'WINDOWS_GMD_TEST_FAILURE')
    $state.writeFailed = $true
    Check '11 / checkpoint case 7 write failure precedence' ((Resolve-FinalClassification $state).classification -ceq 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE')
    $state = PassState
    Check 'checkpoint case 8 fully validated XML PASS unchanged' ((Resolve-FinalClassification $state).classification -ceq 'WINDOWS_GMD_PASS')
    $state.execution.xmlGatePassed = $false
    Check 'XML green alone cannot PASS' ((Resolve-FinalClassification $state).classification -cne 'WINDOWS_GMD_PASS')
    $baseline = Snapshot
    $propsPath = Join-Path $childEnv['GRADLE_USER_HOME'] 'gradle.properties'
    $fixtureSecret = 'fixture-' + [Guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($propsPath, "sample=$fixtureSecret`n")
    $propertyChange = Snapshot
    Check '12 external properties creation mutation' ((Compare-ExecutionAuthority $baseline $propertyChange).result -ceq 'MUTATED')
    [IO.File]::WriteAllText($propsPath, "sample=${fixtureSecret}-changed`n")
    Check '12 external properties hash mutation' ((Compare-ExecutionAuthority $propertyChange (Snapshot)).result -ceq 'MUTATED')
    $childEnv['THINKCANVAS_INTERNAL_KEY_PASSWORD'] = $fixtureSecret
    $secretCapture = Snapshot
    Check '12 environment digest mutation' ((Compare-ExecutionAuthority $propertyChange $secretCapture).result -ceq 'MUTATED')
    Check '13 raw secret absent from manifest' (-not $secretCapture.canonicalJson.Contains($fixtureSecret))
    foreach ($value in $secretCapture.privateValues) { if ($value.Length) { [void]$configRedactions.Add($value) } }
    Check '13 child secret redaction' (-not (Protect-Text $fixtureSecret -ConfigValues).Contains($fixtureSecret))
    $childEnv.Remove('THINKCANVAS_INTERNAL_KEY_PASSWORD') | Out-Null
    [IO.File]::Delete($propsPath)
    $stable = Snapshot
    $pwsh = Join-Path $PSHOME 'pwsh.exe'
    $child = Invoke-Child $pwsh @('-NoProfile', '-File', $launcherPath, '-VerifyStatic', '-FixtureRoot', $fixture) ''
    if ($child.exitCode -ne 0) { throw ('Separate fixture process failed: ' + (Protect-Text $child.stderr -ConfigValues)) }
    Check '14 separate PowerShell same-state fingerprint' ($child.exitCode -eq 0 -and $child.stdout.Trim() -ceq $stable.fingerprint)
    $docsBefore = Snapshot
    [IO.File]::WriteAllText((Join-Path $fixture 'docs/note.md'), 'changed documentation')
    Check 'docs provenance excluded from authority' ((Snapshot).fingerprint -ceq $docsBefore.fingerprint)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/New.kt'), 'new source')
    $created = Snapshot
    Check 'file creation authority set mutation' ((Compare-ExecutionAuthority $docsBefore $created).result -ceq 'MUTATED')
    [IO.File]::Delete((Join-Path $fixture 'app/src/main/New.kt'))
    Check 'file deletion authority set mutation' ((Compare-ExecutionAuthority $created (Snapshot)).result -ceq 'MUTATED')
    [IO.File]::WriteAllText((Join-Path $fixture '.gitignore'), "app/src/main/Ignored.kt`n")
    [IO.File]::WriteAllText((Join-Path $fixture 'app/src/main/Ignored.kt'), $fixtureSecret)
    $ignoredCapture = Snapshot
    Check 'ignored active config presence block without reading secret' ('IGNORED_ACTIVE_SOURCE_CONFIG' -cin (Evaluate-ExecutionAuthorityPolicy $ignoredCapture.manifest).reasons -and -not $ignoredCapture.canonicalJson.Contains($fixtureSecret))
    [IO.File]::WriteAllText($propsPath, 'systemProp.gradle.user.home=elsewhere')
    Check 'home redirect blocked' ('GRADLE_USER_HOME_REDIRECT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    $script:childEnv['JAVA_OPTS'] = '-Dunreviewed=true'
    Check 'forbidden JVM options policy blocked' ('FORBIDDEN_JVM_OPTION' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    $state = PassState; $state.policy = $initPolicy; $state.afterPolicy = $initPolicy; $state.post = 'RECAPTURE_FAILURE'
    Check 'real recapture failure invalidates block preserves reason' ((Resolve-FinalClassification $state).originalPolicy.reasons -contains 'EXTERNAL_INIT_PRESENT')
    # Required terminal writer failure injection via a nonexistent destination.
    $script:diagnostics = Join-Path $fixture 'missing/evidence'
    $record = @{ runId = 'fixture'; finishedUtc = [DateTime]::UtcNow.ToString('o'); axes = (PassState); final = Resolve-FinalClassification (PassState) }
    Check 'actual terminal write failure reported' (-not (Write-TerminalEvidence $record $stable $stable @{ result = 'UNCHANGED' } @{}))
    $script:diagnostics = Join-Path $fixture 'build/evidence'
    Check 'complete terminal writer success' (Write-TerminalEvidence $record $secretCapture $secretCapture @{ result = 'UNCHANGED' } @{})
    $diagnosticText = (Get-ChildItem -LiteralPath $diagnostics -File | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
    Check '13 raw secret absent from saved diagnostics' (-not $diagnosticText.Contains($fixtureSecret))
    [void][IO.Directory]::CreateDirectory((Join-Path $fixture 'build/link-target'))
    [void](New-Item -ItemType Junction -Path (Join-Path $fixture 'app/schemas') -Value (Join-Path $fixture 'build/link-target'))
    Check 'reparse path captured then policy blocked' ('UNSUPPORTED_AUTHORITY_PATH' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle'), "include ':other'")
    Check 'unsupported Groovy topology blocked' ('UNSUPPORTED_EXECUTION_TOPOLOGY' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    [IO.File]::WriteAllText((Join-Path $fixture 'buildSrc.gradle.dcl'), 'not an automatic DCL candidate')
    [IO.File]::WriteAllText((Join-Path $fixture 'build.gradle.dcl'), $fixtureSecret)
    $dclCapture = Snapshot
    Check 'DCL presence-only block' ('UNSUPPORTED_DCL_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $dclCapture.manifest).reasons -and
        @($dclCapture.manifest.workingTreeFiles | Where-Object { $_.path -ceq '<worktree>/build.gradle.dcl' -and $null -eq $_.sha256 }).Count -eq 1)
    [IO.File]::WriteAllText((Join-Path $fixture 'app/local.properties'), $fixtureSecret)
    Check 'module local.properties presence blocked' ('LOCAL_PROPERTIES_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    $selection = $stable.distribution
    [void][IO.Directory]::CreateDirectory((Join-Path $selection.root 'init.d/nested'))
    [IO.File]::WriteAllText((Join-Path $selection.root 'init.d/nested/not-automatic.gradle'), $fixtureSecret)
    [IO.File]::WriteAllText((Join-Path $selection.root 'init.d/not-automatic.GRADLE'), $fixtureSecret)
    Check 'nonrecognized distribution init paths excluded' (-not ('EXTERNAL_INIT_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons))
    [IO.File]::WriteAllText((Join-Path $selection.root 'init.d/automatic.gradle.kts'), $fixtureSecret)
    Check 'recognized distribution init blocked' ('EXTERNAL_INIT_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    Write-Output "Static fixtures PASS ($($results.Count)); no GMD executed"
    $results | ForEach-Object { Write-Output "PASS $_" }
}

if ($VerifyStatic) {
    if ($FixtureRoot) {
        Initialize-FixtureContext $FixtureRoot
        Write-Output (Capture-ExecutionAuthority 'Full' '' 66).fingerprint
    } else { Verify-StaticFixtures }
    exit 0
}

$started = [DateTime]::UtcNow
$runId = $started.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$diagnostics = Join-Path $worktree "build/diagnostics/windows-gmd/$runId"
$state = New-PhaseState
$before = $null; $after = $null; $comparison = @{ result = 'NOT_APPLICABLE' }; $provenance = @{}
try { [void][IO.Directory]::CreateDirectory($diagnostics) } catch { $writeState.failed = $true }
try {
    # Bind invocation before capture; no Java/Gradle/runtime probes precede policy.
    if ($PSCmdlet.ParameterSetName -eq 'Focused') {
        if ($Test -cnotmatch '^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*#[A-Za-z_$][A-Za-z0-9_$]*$') { throw 'Focused mode requires one nonempty Class#method' }
        $ExpectedTestCount = 1
    }
    if ((Git @('rev-parse', '--show-toplevel')).Replace('/', '\') -ine $worktree.Replace('/', '\')) { throw 'Launcher must belong to the current worktree' }
    $commonDir = Git @('rev-parse', '--path-format=absolute', '--git-common-dir')
    if ((Split-Path -Leaf $commonDir) -ne '.git') { throw 'Unsupported shared repository layout' }
    $repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $commonDir))
    $childEnv['GRADLE_USER_HOME'] = Join-Path $repoRoot '.gradle-user'
    $childEnv['ANDROID_USER_HOME'] = Join-Path $childEnv['GRADLE_USER_HOME'] 'android-user'
    $childEnv['SystemRoot'] = $systemRoot; $childEnv['windir'] = $systemRoot
    [void]$childEnv.Remove($classKey); [void]$childEnv.Remove($regexKey)
    if ($Test) { $childEnv[$classKey] = $Test }
    try { $before = Capture-ExecutionAuthority $PSCmdlet.ParameterSetName $Test $ExpectedTestCount; $state.capture = 'SUCCESS' }
    catch { $state.capture = 'FAILURE' }
    if ($null -ne $before) {
        foreach ($value in $before.privateValues) { if ($value.Length) { [void]$configRedactions.Add($value) } }
        $state.policy = Evaluate-ExecutionAuthorityPolicy $before.manifest
        try { $provenance = @{ before = Get-Provenance } } catch { $provenance = @{ before = @{ head = $null; branch = $null }; limitation = 'Git provenance census unavailable' } }
        if ($state.policy.result -ceq 'ALLOWED' -and -not $writeState.failed) {
            $state.execution = Execute-Gmd $before $provenance.before
        }
        # Every baseline exit, including a policy rejection, has the same read-only
        # recapture. Repeating a forbidden presence never throws a policy exception.
        try {
            $after = Capture-ExecutionAuthority $PSCmdlet.ParameterSetName $Test $ExpectedTestCount
            $state.afterPolicy = Evaluate-ExecutionAuthorityPolicy $after.manifest
            $comparison = Compare-ExecutionAuthority $before $after
            $state.post = $comparison.result
        } catch { $state.post = 'RECAPTURE_FAILURE' }
        try { $provenance.after = Get-Provenance } catch { $provenance.afterLimitation = 'Git provenance census unavailable' }
    }
} catch {
    if ($state.capture -ceq 'NOT_ATTEMPTED') {
        $state.execution.classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'; $state.execution.reason = 'INVOCATION_BINDING_FAILED'; $state.execution.state = 'FAILURE'
    } else {
        # Unexpected lifecycle failure cannot become a PASS candidate.
        $state.post = 'RECAPTURE_FAILURE'
    }
}
$state.writeFailed = $writeState.failed
$record = @{ runId = $runId; startedUtc = $started.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o')
    axes = $state; final = Resolve-FinalClassification $state }
$terminalWritten = $false
try { $terminalWritten = Write-TerminalEvidence $record $before $after $comparison $provenance } catch { $terminalWritten = $false }
if (-not $terminalWritten) {
    $state.writeFailed = $true; $record.final = Resolve-FinalClassification $state
    # Best effort invalidation cannot restart execution. Only a matching completion
    # marker plus summary can be consumed; exit remains nonzero even if this saves.
    try { [void](Write-TerminalEvidence $record $before $after $comparison $provenance) } catch { }
}
Write-Output $record.final.classification
Write-Output "Diagnostics: $diagnostics"
if ($terminalWritten -and $record.final.classification -ceq 'WINDOWS_GMD_PASS') { exit 0 }
exit 1
