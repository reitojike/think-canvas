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
$environmentConflict = $false
$appliedMitigation = @()
$runtimeEnvironmentKeys = @('Path', 'TEMP', 'TMP', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA',
    'LOCALAPPDATA', 'ProgramData', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')
function New-FiniteChildEnvironment($Parent) {
    $map = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($key in $runtimeEnvironmentKeys) {
        # Canonical spelling wins (in particular Path over PATH). No generic forward.
        $matches = @($Parent.Keys | Where-Object { $_ -ieq $key } | Sort-Object -CaseSensitive)
        $selected = @($matches | Where-Object { $_ -ceq $key })
        if (-not $selected.Count) { $selected = $matches }
        if ($matches.Count -gt 1 -and @($matches | ForEach-Object { [string]$Parent[$_] } | Select-Object -Unique).Count -gt 1) {
            if ($key -ceq 'Path') { $script:appliedMitigation = @('PATH_CASE_NORMALIZATION') }
            else { $script:environmentConflict = $true }
        }
        if ($selected.Count) { $map[$key] = [string]$Parent[$selected[0]] }
    }
    $map['SystemRoot'] = $systemRoot; $map['windir'] = $systemRoot
    $map['ComSpec'] = Join-Path $systemRoot 'System32/cmd.exe'
    $map['PATHEXT'] = '.COM;.EXE;.BAT;.CMD'; $map['OS'] = 'Windows_NT'
    $map['GRADLE_USER_HOME'] = Join-Path $repoRoot '.gradle-user'
    $map['ANDROID_USER_HOME'] = Join-Path $map['GRADLE_USER_HOME'] 'android-user'
    $map['THINKCANVAS_VERSION_CODE'] = '1'; $map['THINKCANVAS_VERSION_NAME'] = '0.1.0'
    $known = '-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp')
    if ($Parent.Contains('JAVA_TOOL_OPTIONS') -and $Parent['JAVA_TOOL_OPTIONS'] -ceq $known) {
        $map['JAVA_TOOL_OPTIONS'] = $known
    }
    return ,$map
}
function Get-JvmOptionObservations($Parent) {
    return @(foreach ($key in @('JAVA_OPTS', 'GRADLE_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')) {
        $nonempty = $Parent.Contains($key) -and ([string]$Parent[$key]).Length -gt 0
        $known = $key -ceq 'JAVA_TOOL_OPTIONS' -and $nonempty -and $Parent[$key] -ceq ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp'))
        @{ key = $key; nonempty = $nonempty; knownNioOption = $known; forbidden = $nonempty -and -not $known }
    })
}
$parentEnvironment = [Environment]::GetEnvironmentVariables()
$childEnv = New-FiniteChildEnvironment $parentEnvironment
# Only typed option facts survive. Dropped parent values are never redaction inputs or verifiers.
$jvmOptionObservations = Get-JvmOptionObservations $parentEnvironment
$parentEnvironment = $null
$configRedactions = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$writeState = @{ failed = $false }
$requiredArtifacts = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$publishedArtifacts = [Collections.Generic.List[string]]::new()
$owned = $null
$diagnostics = $null

# Runtime components below are selectively salvaged from frozen #57. The authority
# capture, policy, comparison, resolver and terminal protocol are new for Option C.

function Assert-StructuredArtifact([string]$Name, $Envelope) {
    if ($Envelope -isnot [Collections.IDictionary] -or $Envelope.schemaVersion -ne 1 -or
        $Envelope.artifact -cne $Name -or -not $Envelope.Contains('data')) { throw 'DIAGNOSTIC_SCHEMA_INVALID' }
    $v = $Envelope.data
    function Reject-UnsafeFields($Object, [string]$ParentKey) {
        if ($Object -is [Collections.IDictionary]) {
            foreach ($key in $Object.Keys) {
                if ($key -cin @('rawEnvironment', 'rawConfig', 'privateValues', 'secretValue', 'secretSha256', 'secretDigest',
                    'commandLine', 'exception', 'requiredLogs') -or
                    ($key -cin @('stdout', 'stderr') -and $ParentKey -cne 'diagnosticLogs')) { throw 'SECRET_UNSAFE_PERSISTENCE' }
                Reject-UnsafeFields $Object[$key] $key
            }
        } elseif ($Object -is [array]) { foreach ($item in $Object) { Reject-UnsafeFields $item $ParentKey } }
    }
    Reject-UnsafeFields $v ''
    function Keys($Object, [hashtable]$Types) {
        if ($Object -isnot [Collections.IDictionary]) { throw 'DIAGNOSTIC_OBJECT_INVALID' }
        foreach ($k in $Types.Keys) {
            if (-not $Object.Contains($k) -or $Object[$k] -isnot $Types[$k]) { throw 'DIAGNOSTIC_KEY_TYPE_INVALID' }
        }
    }
    switch -Regex ($Name) {
        '^manifest-(before|after)\.json$' {
            Keys $v @{ manifest = [Collections.IDictionary]; fingerprint = [string] }
            Keys $v.manifest @{ schema = [string]; workingTreeFiles = [array]; externalConfig = [array]; externalEnvironment = [Collections.IDictionary]; invocationContract = [Collections.IDictionary] }
            if ($v.manifest.schema -cne 'ExecutionAuthorityManifest/v2' -or
                (Get-AuthorityDigest $v.manifest.externalEnvironment) -cne (Get-AuthorityDigest @{ strategy = 'FINITE_ALLOWLIST/v1'; droppedInputs = 'NOT_EXECUTION_AUTHORITY'; secretValuesRequired = 0 })) { throw 'SECRET_UNSAFE_PERSISTENCE' }
            foreach ($a in $v.manifest.externalConfig) {
                if ($null -ne $a.length -or $null -ne $a.sha256) { throw 'SECRET_UNSAFE_PERSISTENCE' }
            }
            if ((Get-AuthorityDigest $v.manifest.invocationContract.fixedEnvironment) -cne (Get-AuthorityDigest @{ THINKCANVAS_VERSION_CODE = '1'; THINKCANVAS_VERSION_NAME = '0.1.0' })) { throw 'SECRET_UNSAFE_PERSISTENCE' }
        }
        '^policy\.json$' { Keys $v @{ before = [Collections.IDictionary]; after = [Collections.IDictionary] }; foreach ($p in @($v.before, $v.after)) { Keys $p @{ result = [string]; reasons = [array] } } }
        '^comparison\.json$' { Keys $v @{ result = [string] } }
        '^provenance\.json$' { if ($v -isnot [Collections.IDictionary]) { throw 'DIAGNOSTIC_PROVENANCE_INVALID' } }
        '^summary\.json$' {
            Keys $v @{ runId = [string]; startedUtc = [string]; finishedUtc = [string]; axes = [Collections.IDictionary]; final = [Collections.IDictionary]; requiredArtifacts = [array] }
            Keys $v.final @{ classification = [string]; validity = [string]; originalPolicy = [Collections.IDictionary]; provisionalExecution = [Collections.IDictionary]; postAuthority = [string] }
            Keys $v.axes @{ capture = [string]; policy = [Collections.IDictionary]; execution = [Collections.IDictionary]; post = [string]; writeFailed = [bool] }
            Keys $v.axes.execution @{ state = [string]; classification = [string]; reason = [string]; xmlGatePassed = [bool]; ownershipPassed = [bool]; runtimePreflightPassed = [bool] }
        }
        '^completion\.json$' {
            Keys $v @{ runId = [string]; finishedUtc = [string]; classification = [string]; summarySha256 = [string]; artifacts = [array]; terminalArtifact = [Collections.IDictionary]; writeComplete = [bool] }
            if ($v.Contains('requiredLogs')) { throw 'SECRET_UNSAFE_PERSISTENCE' }
            Keys $v.terminalArtifact @{ path = [string]; length = [long]; sha256 = [string] }
            if (-not $v.writeComplete -or $v.summarySha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'DIAGNOSTIC_COMPLETION_INVALID' }
            foreach ($a in $v.artifacts) { Keys $a @{ path = [string]; length = [long]; sha256 = [string] } }
        }
        '^.*\.process\.json$' {
            Keys $v @{ startedUtc = [string]; finishedUtc = [string]; exitCode = [long]; stdoutTruncated = [bool]; stderrTruncated = [bool]; diagnosticLogs = [Collections.IDictionary] }
            foreach ($stream in @('stdout', 'stderr')) {
                if ($v.diagnosticLogs[$stream] -cnotin @('SAVED_DIAGNOSTIC_ONLY', 'SUPPRESSED_SECRET_SAFETY', 'UNAVAILABLE_IO')) { throw 'DIAGNOSTIC_AVAILABILITY_INVALID' }
            }
        }
        '^.*process-census\.json$' { Keys $v @{ completeness = [string]; conflict = [bool]; processes = [array] } }
        '^preflight\.json$' { Keys $v @{ worktree = [string]; sharedRepositoryRoot = [string]; completed = [bool] } }
        '^wrapper-cache\.json$' { Keys $v @{ cacheDirectory = [string]; extractedAndVerified = [bool] } }
        '^gmd-state\.json$' { Keys $v @{ trackingPresent = [bool]; MDLockCount = [long]; automaticCleanup = [bool] } }
        '^invocation\.json$' { Keys $v @{ executable = [string]; argv = [array]; ownedInit = [Collections.IDictionary] } }
        '^ownership\.json$' { Keys $v @{ namespace = [string]; emptyBeforeChild = [bool]; bindingConfirmed = [bool]; result = [string]; reason = [string] } }
        '^xml-(before|after)\.json$' { if ($v -isnot [array]) { throw 'DIAGNOSTIC_XML_CENSUS_INVALID' }; foreach ($a in $v) { Keys $a @{ path = [string]; length = [long]; sha256 = [string]; modifiedUtc = [string] } } }
        '^xml-results\.json$' { Keys $v @{ tests = [long]; failures = [long]; errors = [long]; skipped = [long]; testcaseNames = [array]; files = [array]; classification = [string]; reason = [string] } }
        default { throw 'DIAGNOSTIC_ARTIFACT_NOT_ALLOWLISTED' }
    }
}
function ConvertFrom-StructuredJson([string]$Json) {
    # JsonDocument keeps ISO timestamps as strings on every supported PowerShell.
    function Element($e) {
        switch ([string]$e.ValueKind) {
            'Object' {
                $o = @{}
                foreach ($p in $e.EnumerateObject()) {
                    if ($o.ContainsKey($p.Name)) { throw 'DUPLICATE_JSON_KEY' }
                    $o[$p.Name] = Element $p.Value
                }
                return $o
            }
            'Array' { return ,@(foreach ($i in $e.EnumerateArray()) { Element $i }) }
            'String' { return $e.GetString() }
            'Number' { return $e.GetInt64() }
            'True' { return $true }
            'False' { return $false }
            'Null' { return $null }
            default { throw 'JSON_KIND_INVALID' }
        }
    }
    $document = [Text.Json.JsonDocument]::Parse($Json)
    try { return Element $document.RootElement } finally { $document.Dispose() }
}
function Read-StructuredArtifact([string]$Name) {
    $path = Join-Path $diagnostics $Name
    if ((Observe-Path $path).kind -cne 'file') { throw 'DIAGNOSTIC_FILE_MISSING_OR_REDIRECTED' }
    $bytes = [IO.File]::ReadAllBytes($path)
    $json = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    $v = ConvertFrom-StructuredJson $json
    Assert-StructuredArtifact $Name $v
    return @{ value = $v.data; path = $Name; length = $bytes.LongLength; sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)) }
}
function Write-Artifact([string]$Name, $Value) {
    $temp = $null
    try {
        # Only typed, allowlisted objects reach this writer. Never redact serialized JSON.
        $envelope = @{ schemaVersion = 1; artifact = $Name; data = $Value }
        $json = ConvertTo-Json -InputObject $envelope -Depth 60 -WarningAction Stop
        # Reject unsafe fields before even a temporary file can persist them.
        Assert-StructuredArtifact $Name (ConvertFrom-StructuredJson $json)
        $temp = $Name + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
        [IO.File]::WriteAllText((Join-Path $diagnostics $temp), $json, [Text.UTF8Encoding]::new($false))
        $parsed = ConvertFrom-StructuredJson ([IO.File]::ReadAllText((Join-Path $diagnostics $temp)))
        Assert-StructuredArtifact $Name $parsed
        if (Test-Path -LiteralPath (Join-Path $diagnostics 'completion.json')) { throw 'TERMINAL_ALREADY_COMMITTED' }
        [IO.File]::Move((Join-Path $diagnostics $temp), (Join-Path $diagnostics $Name), $true)
        $final = Read-StructuredArtifact $Name
        if ($final.sha256 -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($json)))) { throw 'DIAGNOSTIC_FINAL_BYTES_CHANGED' }
        $publishedArtifacts.Add($Name)
        return $true
    } catch { return $false }
}
function Save-Json([string]$Name, $Value) {
    [void]$requiredArtifacts.Add($Name)
    if (-not (Write-Artifact $Name $Value)) {
        $writeState.failed = $true
    }
}
function Invoke-Child([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    if ($LogName) {
        [void]$requiredArtifacts.Add("$LogName.process.json")
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-Child $Executable $Arguments
    $begin = [DateTime]::UtcNow
    try {
        [void]$process.Start()
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $result = @{ exitCode = $process.ExitCode; stdout = $outTask.GetAwaiter().GetResult(); stderr = $errTask.GetAwaiter().GetResult()
            startedUtc = $begin.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o') }
        if ($LogName) {
            $availability = @{}
            foreach ($stream in @('stdout', 'stderr')) {
                $saved = Save-DiagnosticText "$LogName.$stream.log" $result[$stream]
                $availability[$stream] = $saved.status
                $result["${stream}Truncated"] = $saved.truncated
            }
            Save-Json "$LogName.process.json" @{
                startedUtc = $result.startedUtc; finishedUtc = $result.finishedUtc
                exitCode = $result.exitCode; stdoutTruncated = $result.stdoutTruncated; stderrTruncated = $result.stderrTruncated
                diagnosticLogs = $availability
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
        '-Pandroid.builder.sdkDownload=false', '-Porg.gradle.java.installations.auto-download=false',
        '--init-script', $owned.initPath)
}
function Get-OwnedInitText([string]$Id) {
    if ($Id -cnotmatch '^[a-zA-Z0-9-]{1,80}$') { throw 'RUN_ID_INVALID' }
    # Fixed Groovy template; the sole variable is a validated launcher-generated ID.
    # No caller logic, environment values, credentials or absolute user paths.
    return @'
def runId = '__RUN_ID__'
def base = new File(gradle.startParameter.currentDir, "build/diagnostics/windows-gmd/${runId}/gradle-build")
gradle.beforeProject { p ->
    if (p.rootDir.canonicalFile != gradle.startParameter.currentDir.canonicalFile || !(p.path in [':', ':app'])) {
        throw new GradleException('WINDOWS_GMD_OWNERSHIP_TOPOLOGY')
    }
    p.layout.buildDirectory.set(new File(base, p.path == ':' ? 'root' : 'app'))
    p.layout.buildDirectory.finalizeValue()
}
gradle.taskGraph.whenReady { graph ->
    def projects = gradle.rootProject.allprojects
    if (projects.collect { it.path }.sort() != [':', ':app'] || !gradle.includedBuilds.empty) {
        throw new GradleException('WINDOWS_GMD_OWNERSHIP_TOPOLOGY')
    }
    projects.each { p ->
        def expected = new File(base, p.path == ':' ? 'root' : 'app').canonicalFile
        if (p.layout.buildDirectory.get().asFile.canonicalFile != expected) {
            throw new GradleException('WINDOWS_GMD_OUTPUT_BINDING_FAILURE')
        }
    }
    def task = gradle.rootProject.project(':app').tasks.getByName('pixel7Api37DebugAndroidTest')
    def resultRoot = new File(base, 'app/outputs/androidTest-results/managedDevice').canonicalFile.toPath()
    def resultDirs = [task.resultsDir.get().asFile]
    if (task.xmlResultsDirectory.present) { resultDirs.add(task.xmlResultsDirectory.get().asFile) }
    if (!graph.hasTask(task) || task.testedVariantName.get() != 'debug' || task.device.get().name != 'pixel7Api37' ||
        resultDirs.any { !it.canonicalFile.toPath().startsWith(resultRoot) } ||
        task.outputs.files.files.empty || task.outputs.files.files.any { !it.canonicalFile.toPath().startsWith(base.canonicalFile.toPath()) }) {
        throw new GradleException('WINDOWS_GMD_OUTPUT_BINDING_FAILURE')
    }
    // Observe configured outputs without changing task actions, inputs or test semantics.
    println "WINDOWS_GMD_OUTPUT_BOUND|${runId}|:app:pixel7Api37DebugAndroidTest"
}
'@.Replace('__RUN_ID__', $Id).Replace("`r`n", "`n") + "`n"
}
function Initialize-OwnedRun([string]$Id) {
    $path = Join-Path $worktree "build/diagnostics/windows-gmd/$Id"
    if ((Observe-Path $path).present) { throw 'RUN_NAMESPACE_EXISTS' }
    [void][IO.Directory]::CreateDirectory($path)
    if ((Observe-Path $path).kind -cne 'directory' -or @(Get-ChildItem -LiteralPath $path -Force).Count) { throw 'RUN_NAMESPACE_NOT_NEW' }
    $script:diagnostics = $path
    $script:owned = @{ runId = $Id; root = Join-Path $path 'gradle-build'; initPath = Join-Path $path 'owned-output.init.gradle' }
    [IO.File]::WriteAllText($owned.initPath, (Get-OwnedInitText $Id), [Text.UTF8Encoding]::new($false))
    [void][IO.Directory]::CreateDirectory($owned.root)
    Assert-OwnedNamespaceEmpty
}
function Assert-OwnedNamespaceEmpty {
    if ((Observe-Path $owned.root).kind -cne 'directory' -or @(Get-ChildItem -LiteralPath $owned.root -Force).Count) { throw 'OWNED_NAMESPACE_NOT_EMPTY' }
}
function Get-OwnedInvocation {
    if ((Observe-Path $owned.initPath).kind -cne 'file') { throw 'OWNED_INIT_UNREADABLE' }
    $bytes = [IO.File]::ReadAllBytes($owned.initPath)
    return @{ template = 'BuildDirectoryRelocation/v1'; runId = $owned.runId
        script = @{ path = "<run>/owned-output.init.gradle"; length = $bytes.LongLength; sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
            utf8 = [Text.UTF8Encoding]::new($false, $true).GetString($bytes) }
        projects = @(@{ project = ':'; output = '<run>/gradle-build/root' }, @{ project = ':app'; output = '<run>/gradle-build/app' }) }
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
            if ($path -cmatch '(^|/)gradle\.properties$') {
                $gate = Get-PropertyGate $record.path $bytes $privateValues
                $propertyGates += $gate
                if ($gate.secretPresent -or $gate.unsupportedFormat) { $files += $record; continue }
            }
            $record.length = $bytes.LongLength
            $record.sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
            if ($path -ceq 'gradle/wrapper/gradle-wrapper.properties') { $wrapperBytes = $bytes }
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
        # Private automatic properties are presence-only policy blocks. Never read/hash them.
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
                    $parent = Get-AuthorityPath $entry.DirectoryName $distribution.root
                    $init += @{ path = $parent + '/<init-' + (Get-AuthorityDigest $entry.Name) + '>'; present = $true }
                }
            }
        }
    }
    $environment = @{ strategy = 'FINITE_ALLOWLIST/v1'; droppedInputs = 'NOT_EXECUTION_AUTHORITY'; secretValuesRequired = 0 }
    $options = $jvmOptionObservations
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
        finiteEnvironmentValid = Test-FiniteChildEnvironment
        selectorOwned = (-not $childEnv.ContainsKey($regexKey)) -and $(if ($Selector) { $childEnv.ContainsKey($classKey) -and $childEnv[$classKey] -ceq $Selector } else { -not $childEnv.ContainsKey($classKey) })
        wrapperSelection = $distribution.status
    }
    $manifest = @{
        schema = 'ExecutionAuthorityManifest/v2'; scope = 'think-canvas-app-buildSrc/v1'
        workingTreeFiles = $files; directoryMembership = Sort-PathRecords $directories
        externalConfig = Sort-PathRecords $external; externalEnvironment = $environment; policyObservations = $policy
        invocationContract = @{ mode = $Mode; selector = $Selector; expectedTestCount = $Count
            fixedEnvironment = @{ THINKCANVAS_VERSION_CODE = '1'; THINKCANVAS_VERSION_NAME = '0.1.0' }
            ownedOutput = Get-OwnedInvocation
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
            secretPresent = @($pairs.Keys | Where-Object { $_ -match '(?i)token|secret|password|credential|api.?key' }).Count -gt 0
            selectorPresent = $pairs.ContainsKey('android.testInstrumentationRunnerArguments.class') -or $pairs.ContainsKey('android.testInstrumentationRunnerArguments.tests_regex') }
    } catch { return @{ path = $Path; unsupportedFormat = $true; redirectPresent = $false; selectorPresent = $false; secretPresent = $false } }
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
    if (@($Manifest.externalConfig | Where-Object { $_.present }).Count) { $reasons += 'EXTERNAL_PRIVATE_PROPERTIES_PRESENT'; $reasons += 'SECRET_SAFETY_POLICY_BLOCK' }
    if (-not $p.finiteEnvironmentValid -or @($p.propertyGates | Where-Object { $_.secretPresent }).Count) { $reasons += 'SECRET_SAFETY_POLICY_BLOCK' }
    if (@($p.propertyGates | Where-Object { $_.unsupportedFormat }).Count) { $reasons += 'UNSUPPORTED_PROPERTIES_FORMAT' }
    if (@($p.propertyGates | Where-Object { $_.redirectPresent }).Count) { $reasons += 'GRADLE_USER_HOME_REDIRECT' }
    if (@($p.propertyGates | Where-Object { $_.selectorPresent }).Count) { $reasons += 'COMPETING_PROPERTY_SELECTOR' }
    if (@($p.jvmOptions | Where-Object { $_.forbidden }).Count) { $reasons += 'FORBIDDEN_JVM_OPTION' }
    if ($p.environmentConflict) { $reasons += 'ENVIRONMENT_KEY_CONFLICT' }
    if (-not $p.selectorOwned) { $reasons += 'SELECTOR_OWNERSHIP_FAILURE' }
    if ($p.wrapperSelection -cne 'SUPPORTED') { $reasons += 'UNSUPPORTED_WRAPPER_SELECTION' }
    if (@($Manifest.directoryMembership | Where-Object { $_.path -ceq '<worktree>/buildSrc' -and $_.present }).Count) { $reasons += 'UNMAPPED_BUILD_IDENTITY' }
    if ($Manifest.invocationContract.ownedOutput.script.utf8 -cne (Get-OwnedInitText $Manifest.invocationContract.ownedOutput.runId)) { $reasons += 'OWNED_INIT_TEMPLATE_MISMATCH' }
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
        $classification = $State.execution.classification; $reason = $State.execution.reason
        $validity = if ($classification -ceq 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE') { 'INVALID' } else { 'VALID_FAILURE' }
    }
    elseif ($State.execution.state -ceq 'PASS_CANDIDATE' -and $State.execution.xmlGatePassed -and $State.execution.ownershipPassed -and $State.execution.runtimePreflightPassed -and
        $State.execution.gradleExitCode -eq 0 -and $State.policy.result -ceq 'ALLOWED' -and $State.afterPolicy.result -ceq 'ALLOWED' -and $State.post -ceq 'UNCHANGED') {
        $classification = 'WINDOWS_GMD_PASS'; $reason = 'ALL_GATES_PASSED'; $validity = 'VALID_COMPLETION'
    }
    return @{ classification = $classification; reason = $reason; validity = $validity; phase = $phase
        originalPolicy = $State.policy; provisionalExecution = $State.execution; postAuthority = $State.post }
}

function Save-DiagnosticText([string]$Name, [string]$Value) {
    # Never persist/stream a text source for which safe redaction is ambiguous.
    # All nonempty known config values participate; the short-value rule suppresses,
    # rather than exempting them from protection.
    foreach ($valueToProtect in $configRedactions) {
        if ($valueToProtect.Length -and $Value.Contains($valueToProtect) -and
            ($valueToProtect.Length -lt 4 -or $valueToProtect -match '^(?i:true|false|\d+)$')) {
            return @{ status = 'SUPPRESSED_SECRET_SAFETY'; truncated = $false }
        }
    }
    $safe = Protect-Text $Value -ConfigValues
    $truncated = $safe.Length -gt 2MB
    if ($truncated) { $safe = '<tail only>' + "`n" + $safe.Substring($safe.Length - 2MB) }
    try {
        [IO.File]::WriteAllText((Join-Path $diagnostics $Name), $safe, [Text.UTF8Encoding]::new($false))
        return @{ status = 'SAVED_DIAGNOSTIC_ONLY'; truncated = $truncated }
    } catch { return @{ status = 'UNAVAILABLE_IO'; truncated = $truncated } }
}
function Protect-Text([string]$Value, [switch]$ConfigValues) {
    if ($ConfigValues) {
        # Merge original-text intervals, so overlapping values and placeholders are safe.
        $intervals = @(foreach ($secret in $configRedactions) {
            if (-not $secret.Length) { continue }
            $offset = 0
            while ($offset -lt $Value.Length) {
                $found = $Value.IndexOf($secret, $offset, [StringComparison]::Ordinal)
                if ($found -lt 0) { break }
                @{ start = $found; end = $found + $secret.Length }
                $offset = $found + 1
            }
        }) | Sort-Object { $_.start }, { $_.end }
        $builder = [Text.StringBuilder]::new(); $cursor = 0; $end = -1; $start = -1
        foreach ($interval in $intervals) {
            if ($start -lt 0) { $start = $interval.start; $end = $interval.end }
            elseif ($interval.start -le $end) { $end = [Math]::Max($end, $interval.end) }
            else {
                [void]$builder.Append($Value.Substring($cursor, $start - $cursor)); [void]$builder.Append('<redacted>')
                $cursor = $end; $start = $interval.start; $end = $interval.end
            }
        }
        if ($start -ge 0) {
            [void]$builder.Append($Value.Substring($cursor, $start - $cursor)); [void]$builder.Append('<redacted>')
            [void]$builder.Append($Value.Substring($end)); $Value = $builder.ToString()
        }
    }
    foreach ($pair in @(@($worktree, '<worktree>'), @($repoRoot, '<shared-repo-root>'), @($env:USERPROFILE, '<user-profile>'))) {
        if ($pair[0]) {
            $Value = $Value.Replace($pair[0], $pair[1]).Replace($pair[0].Replace('\', '/'), $pair[1])
            $Value = $Value.Replace($pair[0].Replace('\', '\\'), $pair[1])
        }
    }
    $runtimePaths = @(foreach ($key in $runtimeEnvironmentKeys) {
        if ($childEnv.ContainsKey($key) -and $childEnv[$key]) {
            if ($key -ceq 'Path') { $childEnv[$key] -split ';' | Where-Object { $_ } }
            else { $childEnv[$key] }
        }
    }) | Select-Object -Unique | Sort-Object Length -Descending
    foreach ($path in $runtimePaths) {
        $Value = $Value.Replace($path, '<runtime-path>').Replace($path.Replace('\', '/'), '<runtime-path>').Replace($path.Replace('\', '\\'), '<runtime-path>')
    }
    $Value = $Value -replace '(?i)(?:ghp_|gho_|github_pat_)[a-z0-9_]+', '<redacted>'
    $Value = $Value -replace '(?i)((?:password|token|secret|api.?key)\s*[=:]\s*)\S+', '$1<redacted>'
    $Value = $Value -replace '(?i)(https?://)[^\s/@]+:[^\s/@]+@', '$1<redacted>@'
    $Value = $Value -replace '(?i)[A-Z]:\\(?:\\)?Users\\(?:\\)?[^\\\r\n]+', '<user-profile>'
    $Value = $Value -replace '(?m)^\s*user\.name = .+$', '    user.name = <redacted>'
    return $Value
}

function Test-FiniteChildEnvironment {
    $allowed = $runtimeEnvironmentKeys + @('SystemRoot', 'windir', 'ComSpec', 'PATHEXT', 'OS', 'GRADLE_USER_HOME',
        'ANDROID_USER_HOME', 'THINKCANVAS_VERSION_CODE', 'THINKCANVAS_VERSION_NAME', 'JAVA_TOOL_OPTIONS', $classKey)
    if (@($childEnv.Keys | Where-Object { $_ -cnotin $allowed }).Count) { return $false }
    if ($childEnv['THINKCANVAS_VERSION_CODE'] -cne '1' -or $childEnv['THINKCANVAS_VERSION_NAME'] -cne '0.1.0') { return $false }
    if ($childEnv.ContainsKey('JAVA_TOOL_OPTIONS') -and $childEnv['JAVA_TOOL_OPTIONS'] -cne ('-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp'))) { return $false }
    foreach ($key in $runtimeEnvironmentKeys) {
        if (-not $childEnv.ContainsKey($key) -or -not $childEnv[$key]) { continue }
        $paths = if ($key -ceq 'Path') { @($childEnv[$key] -split ';' | Where-Object { $_ }) } else { @($childEnv[$key]) }
        foreach ($path in $paths) {
            if ($path -match '[\r\n"<>|?*]' -or $path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') {
                if ($key -ceq 'HOMEDRIVE' -and $path -match '^[A-Za-z]:$') { continue }
                if ($key -ceq 'HOMEPATH' -and $path -match '^\\[^:]') { continue }
                return $false
            }
        }
    }
    return $true
}
function New-Child([string]$Executable, [string[]]$Arguments) {
    if (-not (Test-FiniteChildEnvironment)) { throw 'SECRET_SAFETY_POLICY_BLOCK' }
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

function Get-ExpectedTestNames {
    return @'
com.thinkcanvas.board.BoardImageDeliveryTest#allElementKindsArePresentInSharedBitmap
com.thinkcanvas.board.BoardImageDeliveryTest#attachedAndFreeArrowEndpointsArePresentInRenderedBitmap
com.thinkcanvas.board.BoardImageDeliveryTest#detailedRegionAndArrowStylingRemainsInExport
com.thinkcanvas.board.BoardImageDeliveryTest#exportUsesDeviceTextMetricsForPlanAndBitmap
com.thinkcanvas.board.BoardImageDeliveryTest#longRegionNameRemainsVisibleBeyondNarrowRegion
com.thinkcanvas.board.BoardImageDeliveryTest#markerStaysBehindShapesAndPenInExport
com.thinkcanvas.board.BoardImageDeliveryTest#mediaStoreSaveCanBeReadAndCleanedUp
com.thinkcanvas.board.BoardImageDeliveryTest#regionLabelMasksArrowAndOutlineInExport
com.thinkcanvas.board.BoardImageDeliveryTest#renderedPngMatchesPreviewAndCanBeCopiedWithoutChangingBoard
com.thinkcanvas.board.BoardImageDeliveryTest#selectedTextArrowUsesThePlanGeometryForDrawing
com.thinkcanvas.board.BoardListScreenTest#actionFailureIsShownAfterActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#boardUndoHistorySurvivesReopenAndActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#completedOpenWaitsForStartedSurvivingActivityBeforeConsume
com.thinkcanvas.board.BoardListScreenTest#createCompletesOnceAfterActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#deleteDoesNotRestoreTheDeletedBoardAfterActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#deletionRequiresConfirmationAndKeepsOtherBoard
com.thinkcanvas.board.BoardListScreenTest#duplicateReloadsExactlyOneCopyAfterActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#emptyBoardShowsExactlyOneCanonicalAccessibleHint
com.thinkcanvas.board.BoardListScreenTest#existingTextImmediateSaveClosesEditor
com.thinkcanvas.board.BoardListScreenTest#failedActionExplicitCloseRestoresListBeforeAcknowledgement
com.thinkcanvas.board.BoardListScreenTest#failedActionRecoveryReadFailureKeepsFailureRetryable
com.thinkcanvas.board.BoardListScreenTest#failedActionSystemBackRecoversListAndAllowsAnotherAction
com.thinkcanvas.board.BoardListScreenTest#failedDraftSaveKeepsContinuationUntilRetrySucceeds
com.thinkcanvas.board.BoardListScreenTest#failedSaveAndRetryRemainObservableAcrossRecreation
com.thinkcanvas.board.BoardListScreenTest#failureOutsideDismissalAndRecreationKeepFailureUntilRecovery
com.thinkcanvas.board.BoardListScreenTest#guideAppearsOnlyOnInitialEmptyBoardAndCanBeReplayed
com.thinkcanvas.board.BoardListScreenTest#guideCtaCanBeScrolledToAndActivatedAtLargeFontInCompactHeight
com.thinkcanvas.board.BoardListScreenTest#lastOpenedBoardReturnsAfterActivityRestart
com.thinkcanvas.board.BoardListScreenTest#listPageSurvivesActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#listShareLoadingRejectsOpenCreateMutationAndSecondShare
com.thinkcanvas.board.BoardListScreenTest#listShareOffersPreviewAndCopyWithoutChangingSavedContent
com.thinkcanvas.board.BoardListScreenTest#newTextImmediateSaveClosesOnceAndPersistsOneElementAfterRepeatedDone
com.thinkcanvas.board.BoardListScreenTest#openingBoardCompletesAfterActivityRecreationWithoutStartingTwice
com.thinkcanvas.board.BoardListScreenTest#pendingBoardSaveSurvivesRecreationAndBlocksLeaving
com.thinkcanvas.board.BoardListScreenTest#regionNameEditBlocksSystemBackAndBoardListNavigation
com.thinkcanvas.board.BoardListScreenTest#renameAndDuplicateKeepIndependentContent
com.thinkcanvas.board.BoardListScreenTest#renameReloadsTheListAfterActivityRecreation
com.thinkcanvas.board.BoardListScreenTest#runningCreateRemainsTheOnlyAllowedListActionAfterRecreation
com.thinkcanvas.board.BoardListScreenTest#selectedTextOpensOnlyItsOwnImagePreview
com.thinkcanvas.board.BoardListScreenTest#systemBackDoesNotLeaveAnUncommittedDraft
com.thinkcanvas.board.BoardListScreenTest#systemBackReturnsFromBoardToList
com.thinkcanvas.board.BoardListScreenTest#twentyCardsCanScrollAndOpenFirstAndLast
com.thinkcanvas.board.BoardThumbnailTest#arrowOnlyBoardHasVisiblePreview
com.thinkcanvas.board.BoardThumbnailTest#fittedTitleGeometryMatchesItsFixedPixelExtent
com.thinkcanvas.board.BoardThumbnailTest#fixedScreenThumbnailGeometryPreservesDpAcrossDensity
com.thinkcanvas.board.BoardThumbnailTest#inkOnlyBoardHasVisiblePreview
com.thinkcanvas.board.BoardThumbnailTest#lowScaleRegionLabelFitsInsideLeftAndRightBitmapEdges
com.thinkcanvas.board.BoardThumbnailTest#multipleRegionLabelsContributeToFitBounds
com.thinkcanvas.board.BoardThumbnailTest#regionLabelFitEnvelopeDoesNotChangeShapeOrArrowAttachmentGeometry
com.thinkcanvas.board.BoardThumbnailTest#thumbnailVisibilityUsesItsActualFitScaleForBorderlineInk
com.thinkcanvas.board.BoardThumbnailTest#titleAttachedArrowUsesTheCorrectedTitleBounds
com.thinkcanvas.board.BoardThumbnailTest#titleNearFittedEdgeIsNotClippedAtScaleBelowHalf
com.thinkcanvas.canvas.ConditionalChromeLifecycleTest#collapsedAndExpandedToolFormerCentersAdmitPersistedInkAndAllowReentry
com.thinkcanvas.canvas.ConditionalChromeLifecycleTest#regionNameTransitionRemovesFormerSelectionSharePointUntilReselected
com.thinkcanvas.canvas.InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws
com.thinkcanvas.canvas.LongPressGestureTest#blankLongPressDragInsertsGapAsOneUndoableOperation
com.thinkcanvas.canvas.LongPressGestureTest#blankLongPressReleaseDoesNothing
com.thinkcanvas.canvas.LongPressGestureTest#elementLongPressDragMovesAndUndoRedoApply
com.thinkcanvas.canvas.LongPressGestureTest#elementLongPressReleaseOpensMenuAndDeleteIsReachable
com.thinkcanvas.canvas.SemanticNavigationTest#farRegionFitsAndSearchFindsSavedText
com.thinkcanvas.canvas.SemanticNavigationTest#openingFitIncludesHiddenBodiesAlongsideShapes
com.thinkcanvas.canvas.SemanticNavigationTest#openingFitKeepsFarBodyOnlyExtentAfterBodyBecomesHidden
com.thinkcanvas.canvas.SemanticNavigationTest#openingFitViewportSettlesAfterSemanticTierChanges
com.thinkcanvas.canvas.SemanticNavigationTest#zoomCycleAndSearchControlsAreAccessibleWithoutSaving
com.thinkcanvas.data.BoardPersistenceTest#boardsRemainSeparateAcrossReopenDeleteAndLateSave
com.thinkcanvas.data.InkPersistenceTest#inkInputEncodingSurvivesDatabaseReopenAtThreeViewports
'@ -split "`n" | ForEach-Object { $_.Trim() }
}
function Get-OwnedXmlFiles {
    $root = Join-Path $owned.root 'app/outputs/androidTest-results/managedDevice'
    $observation = Observe-Path $root
    if (-not $observation.present) { return }
    if ($observation.kind -cne 'directory') { throw 'OWNED_RESULT_PATH_REDIRECTED' }
    $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($root)
    while ($pending.Count) {
        foreach ($item in (Get-ChildItem -LiteralPath $pending.Pop() -Force)) {
            if ((Observe-Path $item.FullName).kind -notin @('directory', 'file')) { throw 'OWNED_RESULT_PATH_REDIRECTED' }
            if ($item.PSIsContainer) { $pending.Push($item.FullName) }
            elseif ($item.Extension -ieq '.xml') { $item.FullName }
        }
    }
}
function Read-OwnedXmlResult([DateTime]$ChildStarted, [bool]$BindingConfirmed, [int]$Count, [string[]]$Names) {
    $r = @{ tests = 0; failures = 0; errors = 0; skipped = 0; testcaseNames = @(); files = @()
        ownershipPassed = $false; eligible = $false; classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; reason = 'OWNED_OUTPUT_BINDING_UNPROVEN' }
    if (-not $BindingConfirmed) { return $r }
    try {
        $paths = @(Get-OwnedXmlFiles)
        if ($paths.Count -ne 1) { $r.reason = 'EXPECTED_OWNED_XML_NOT_GENERATED'; return $r }
        $expected = Join-Path $owned.root 'app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
        if (-not $paths[0].Equals($expected, [StringComparison]::OrdinalIgnoreCase)) { $r.reason = 'UNEXPECTED_OWNED_XML_LOCATION'; return $r }
        $info = Get-Item -LiteralPath $expected
        if ($info.CreationTimeUtc -lt $ChildStarted -or $info.LastWriteTimeUtc -lt $ChildStarted) { $r.reason = 'XML_PREDATES_CHILD'; return $r }
        $bytes = [IO.File]::ReadAllBytes($expected)
        $r.files = @(@{ path = '<run>/gradle-build/app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
            length = $bytes.LongLength; sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)); modifiedUtc = $info.LastWriteTimeUtc.ToString('o') })
        $r.ownershipPassed = $true
        $r.classification = 'WINDOWS_GMD_RESULT_COUNT_MISMATCH'; $r.reason = 'XML_SCHEMA_OR_SUITE_INVALID'
        $settings = [Xml.XmlReaderSettings]::new(); $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit; $settings.XmlResolver = $null
        $stream = [IO.MemoryStream]::new($bytes, $false); $reader = [Xml.XmlReader]::Create($stream, $settings)
        try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) } finally { $reader.Dispose(); $stream.Dispose() }
        if ($xml.DocumentElement.Name -cne 'testsuites') { return $r }
        $suites = @($xml.SelectNodes('/testsuites/testsuite'))
        if (-not $suites.Count -or $xml.SelectNodes('//testcase').Count -ne $xml.SelectNodes('/testsuites/testsuite/testcase').Count) { return $r }
        foreach ($suite in $suites) {
            $cases = @($suite.SelectNodes('testcase'))
            foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
                $a = $suite.GetAttribute($key)
                if ($a -cnotmatch '^\d+$') { return $r }
                $value = [int]$a; $r[$key] += $value
                $observed = if ($key -ceq 'tests') { $cases.Count } else {
                    $element = @{ failures = 'failure'; errors = 'error'; skipped = 'skipped' }[$key]
                    $suite.SelectNodes("testcase/$element").Count
                }
                if ($value -ne $observed) { return $r }
            }
            foreach ($case in $cases) {
                $name = $case.GetAttribute('classname') + '#' + $case.GetAttribute('name')
                # Unknown names/strings never enter diagnostic objects.
                if ($name -cnotin $Names -or $suite.GetAttribute('name') -cne $case.GetAttribute('classname')) { $r.reason = 'UNEXPECTED_TESTCASE'; return $r }
                $r.testcaseNames += $name
            }
            $devices = @($suite.SelectNodes('properties/property[@name="device"]'))
            if ($devices.Count -ne 1 -or $devices[0].GetAttribute('value') -cne '_app_pixel7Api37DebugAndroidTest') { $r.reason = 'UNEXPECTED_DEVICE'; return $r }
        }
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
            if ($xml.DocumentElement.GetAttribute($key) -cnotmatch '^\d+$' -or [int]$xml.DocumentElement.GetAttribute($key) -ne $r[$key]) { return $r }
        }
        if ($r.tests -eq 0) { $r.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'; $r.reason = 'ZERO_TESTCASES'; return $r }
        if ($r.tests -ne $Count -or $r.testcaseNames.Count -ne $Names.Count -or
            @($r.testcaseNames | Select-Object -Unique).Count -ne $Names.Count -or
            (Compare-Object (Sort-Ordinal $Names) (Sort-Ordinal $r.testcaseNames) -CaseSensitive)) { $r.reason = 'COUNT_OR_NAME_MISMATCH'; return $r }
        if ($r.failures -or $r.errors -or $r.skipped) { $r.classification = 'WINDOWS_GMD_TEST_FAILURE'; $r.reason = 'OBSERVED_TEST_FAILURE'; return $r }
        $r.classification = ''; $r.reason = 'OWNED_XML_ELIGIBLE'; $r.eligible = $true
    } catch { $r.reason = if ($r.ownershipPassed) { 'XML_PARSE_OR_READ_FAILURE' } else { 'OWNERSHIP_CONTRACT_UNPROVEN' } }
    return $r
}
function ConvertTo-ProcessCensus([object[]]$Processes) {
    $c = @{ completeness = 'COMMAND_LINE'; conflict = $false; processes = @() }
    foreach ($p in $Processes) {
        if ($p.Name -notmatch '^(javaw?|emulator|qemu.*)\.exe$') { continue }
        $cmd = [string]$p.CommandLine
        if (-not $cmd) { $c.completeness = 'PARTIAL_COMMAND_LINE' }
        $same = $cmd.Replace('/', '\').IndexOf($worktree.Replace('/', '\'), [StringComparison]::OrdinalIgnoreCase) -ge 0
        $active = $cmd -match 'GradleWrapperMain|GradleWorkerMain|gradle-managed|dev37_google_apis|pixel7Api37.*AndroidTest'
        # An idle GradleDaemon or manual ThinkCanvas_API37 is not an active invocation.
        $conflict = $same -and $active -and $cmd -notmatch 'ThinkCanvas_API37'
        $c.conflict = $c.conflict -or $conflict
        $c.processes += @{ pid = [long]$p.ProcessId; parentPid = [long]$p.ParentProcessId
            kind = $(if ($cmd -match 'gradle-managed|dev37_google_apis') { 'GMD' } elseif ($cmd -match 'Gradle') { 'Gradle' } else { 'OTHER' })
            sameWorktree = $same; activeInvocation = $active; conflict = $conflict }
    }
    return $c
}
function Get-ProcessCensus {
    try { return ConvertTo-ProcessCensus @(Get-CimInstance Win32_Process -ErrorAction Stop) }
    catch { return @{ completeness = 'UNAVAILABLE'; processes = @(); conflict = $false } }
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
        phase = 'preflight'; xmlGatePassed = $false; ownershipPassed = $false; runtimePreflightPassed = $false; gradleExitCode = $null
        appliedMitigation = @($appliedMitigation); xml = @(); gradleStartedUtc = $null; gradleFinishedUtc = $null }
    $preflight = @{ worktree = '<worktree>'; sharedRepositoryRoot = '<shared-repo-root>'; head = $Provenance.head; branchDigest = $Provenance.branchDigest
        gradleUserHome = '<shared-repo-root>/.gradle-user'; androidUserHome = '<shared-repo-root>/.gradle-user/android-user'
        SystemRoot = '<windows>'; windir = '<windows>'; environmentKeysCaseInsensitive = $true; completed = $false }
    $wrapperProperties = Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties'
    try {
        if (-not $IsWindows) { throw 'Windows required' }
        if (-not (Test-Path -LiteralPath (Join-Path $systemRoot 'System32/kernel32.dll'))) { throw 'Windows system directory unavailable' }
        $selection = $Capture.distribution
        $cache = $selection.cacheDirectory
        $cached = (Test-Path -LiteralPath (Join-Path $cache ($selection.name + '.zip.ok')) -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $selection.root 'lib') -PathType Container) -and
            (Test-Path -LiteralPath (Join-Path $selection.root 'bin/gradle.bat') -PathType Leaf)
        Save-Json 'wrapper-cache.json' @{ distributionUrl = $selection.url; cacheDirectory = Get-AuthorityPath $cache $selection.root; extractedAndVerified = $cached }
        if (-not $cached) { $outcome.classification = 'WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING'; throw 'Canonical wrapper distribution missing; no download attempted' }
    $processCensus = Get-ProcessCensus
    Save-Json 'pre-run-process-census.json' $processCensus
    if ($processCensus.conflict) { $outcome.reason = 'SAME_WORKTREE_ACTIVE_EXECUTION'; throw 'SAME_WORKTREE_ACTIVE_EXECUTION' }
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
    $preflight.androidSdk = '<android-sdk>'
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
    $preflight.javaExecutable = '<java-home>/bin/java.exe'
    $preflight.javaHome = '<java-home>'
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
    $outcome.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'
    Assert-OwnedNamespaceEmpty
    $baselineXml = @()
    Save-Json 'xml-before.json' $baselineXml
    $argv = Get-GmdArguments
    Save-Json 'invocation.json' @{ executable = '<java-home>/bin/java.exe'; argv = $Capture.manifest.invocationContract.argv; ownedInit = $Capture.manifest.invocationContract.ownedOutput; wrapperJarSha256 = (Get-FileHash -LiteralPath $wrapperJar).Hash
        wrapperPropertiesSha256 = (Get-FileHash -LiteralPath $wrapperProperties).Hash; appliedMitigation = $outcome.appliedMitigation }
    $outcome.classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'
    $outcome.phase = 'wrapper-start'
    $preflight.completed = $true
    $outcome.runtimePreflightPassed = $true
    $outcome.state = 'EXECUTING'
    Save-Json 'preflight.json' $preflight
    if ($writeState.failed) { $outcome.reason = 'PRE_EXECUTION_DIAGNOSTIC_FAILURE'; throw 'PRE_EXECUTION_DIAGNOSTIC_FAILURE' }
    $outcome.gradleStartedUtc = [DateTime]::UtcNow.ToString('o')
    # Execution remains provisional until recapture and terminal write.
    Write-Host "GMD running once; diagnostics: $diagnostics"
    $gradle = Invoke-Child $javaExe $argv 'gradle'
    $outcome.gradleExitCode = $gradle.exitCode
    $outcome.gradleStartedUtc = $gradle.startedUtc
    $outcome.gradleFinishedUtc = $gradle.finishedUtc
    $log = $gradle.stdout + "`n" + $gradle.stderr
    $outcome.phase = 'gradle'
    if ($log -match ':app:pixel7Api37Setup') { $outcome.phase = 'gmd-setup' }
    if ($log -match 'Starting \d+ tests|INSTRUMENTATION_|\d+ tests completed') { $outcome.phase = 'instrumentation' }
    $postCensus = Get-ProcessCensus
    Save-Json 'post-run-process-census.json' $postCensus
    $binding = $log.Contains("WINDOWS_GMD_OUTPUT_BOUND|$($owned.runId)|:app:pixel7Api37DebugAndroidTest")
    $expectedNames = if ($Test) { @($Test) } else { @(Get-ExpectedTestNames) }
    $totals = Read-OwnedXmlResult ([DateTime]::Parse($outcome.gradleStartedUtc).ToUniversalTime()) $binding $ExpectedTestCount $expectedNames
    $outcome.xml = $totals
    $outcome.ownershipPassed = $totals.ownershipPassed
    Save-Json 'xml-after.json' @($totals.files)
    Save-Json 'xml-results.json' $totals
    Save-Json 'ownership.json' @{ namespace = '<run>/gradle-build'; emptyBeforeChild = $true; bindingConfirmed = $binding
        result = $(if ($totals.ownershipPassed) { 'OWNED' } else { 'INVALID' }); reason = $totals.reason }
    if ($totals.files.Count) { $outcome.phase = 'xml-results' }
    $outcome.xmlGatePassed = $false
    if ($log -match 'Timed out trying to check default_boot .* is loadable') {
        $outcome.classification = 'WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT'
    } elseif ($log -match 'Could not acquire device lock|Failed to setup|pixel7Api37Setup FAILED') {
        $outcome.classification = 'WINDOWS_GMD_SETUP_FAILURE'
    } elseif ($log -match 'Permission denied: getsockopt|SEC_E_NO_CREDENTIALS|Downloading https.*gradle|Could not (GET|resolve)|UnknownHostException|SSLHandshakeException') {
        $outcome.classification = 'WINDOWS_GMD_NETWORK_OR_WRAPPER_BLOCKED'
    } elseif ($gradle.exitCode -ne 0 -and -not $totals.files.Count) {
        $outcome.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } elseif ($postCensus.conflict) {
        $outcome.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; $outcome.reason = 'OVERLAPPING_ACTIVE_EXECUTION'
    } elseif (-not $totals.eligible) {
        $outcome.classification = $totals.classification; $outcome.reason = $totals.reason
    } elseif ($gradle.exitCode -ne 0) {
        $outcome.classification = 'WINDOWS_GMD_GRADLE_FAILURE'
    } else { $outcome.xmlGatePassed = $true; $outcome.state = 'PASS_CANDIDATE'; $outcome.classification = '' }
    if ($postCensus.conflict) {
        $outcome.observedExecutionClassification = $outcome.classification
        $outcome.state = 'FAILURE'; $outcome.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'
        $outcome.reason = 'OVERLAPPING_ACTIVE_EXECUTION'; $outcome.ownershipPassed = $false
    }

    } catch {
        if (-not $outcome.reason) { $outcome.reason = 'PHASE_' + $outcome.phase.ToUpperInvariant().Replace('-', '_') + '_FAILED' }
        if ($outcome.state -eq 'EXECUTING') { $outcome.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; $outcome.reason = 'POST_EXECUTION_OWNERSHIP_UNPROVEN' }
        $outcome.state = 'FAILURE'
    }
    if ($outcome.state -eq 'EXECUTING') { $outcome.state = 'FAILURE' }
    if ($outcome.state -eq 'FAILURE' -and -not $outcome.reason) { $outcome.reason = 'OBSERVED_' + $outcome.classification }
    return $outcome
}

function Get-Provenance {
    $branch = Git @('branch', '--show-current')
    $status = Git @('status', '--porcelain=v1', '-z')
    $staged = Git @('diff', '--cached', '--name-status', '-z')
    $unstaged = Git @('diff', '--name-status', '-z')
    return @{ head = Git @('rev-parse', 'HEAD'); branchDigest = Get-AuthorityDigest $branch
        statusDigest = Get-AuthorityDigest $status; stagedDigest = Get-AuthorityDigest $staged; unstagedDigest = Get-AuthorityDigest $unstaged }
}
function New-PhaseState {
    return @{ capture = 'NOT_ATTEMPTED'; policy = @{ result = 'NOT_EVALUATED'; reasons = @() }
        execution = @{ state = 'NOT_STARTED'; classification = ''; reason = ''; xmlGatePassed = $false; ownershipPassed = $false; runtimePreflightPassed = $false; gradleExitCode = $null }
        post = 'NOT_APPLICABLE'; afterPolicy = @{ result = 'NOT_EVALUATED'; reasons = @() }; writeFailed = $false }
}
function Write-TerminalEvidence($Record, $Before, $After, $Comparison, $Provenance) {
    if (Test-Path -LiteralPath (Join-Path $diagnostics 'completion.json')) { return $false }
    $records = [ordered]@{ 'policy.json' = @{ before = $Record.axes.policy; after = $Record.axes.afterPolicy }
        'comparison.json' = $Comparison; 'provenance.json' = $Provenance }
    if ($null -ne $Before) { $records['manifest-before.json'] = @{ manifest = $Before.manifest; fingerprint = $Before.fingerprint } }
    if ($null -ne $After) { $records['manifest-after.json'] = @{ manifest = $After.manifest; fingerprint = $After.fingerprint } }
    $Record.applicability = @{ beforeManifest = $(if ($Before) { 'REQUIRED' } else { 'NOT_APPLICABLE' })
        afterManifest = $(if ($After) { 'REQUIRED' } else { 'NOT_APPLICABLE' }); execution = $Record.axes.execution.state }
    # Required obligations are registered before attempting writes/process starts.
    # A failed writer cannot remove an artifact from this phase-derived set.
    foreach ($name in $records.Keys) { [void]$requiredArtifacts.Add($name) }
    [void]$requiredArtifacts.Add('summary.json')
    $Record.requiredArtifacts = Sort-Ordinal @($requiredArtifacts)
    foreach ($name in $records.Keys) {
        if (-not (Write-Artifact $name $records[$name])) { return $false }
    }
    if (-not (Write-Artifact 'summary.json' $Record)) { return $false }
    $terminalBytes = [Text.Encoding]::UTF8.GetBytes($Record.final.classification + "`n")
    try { [IO.File]::WriteAllBytes((Join-Path $diagnostics 'classification.txt'), $terminalBytes) }
    catch { return $false }
    try {
        $artifacts = @(foreach ($name in $Record.requiredArtifacts) {
            $a = Read-StructuredArtifact $name
            @{ path = $name; length = $a.length; sha256 = $a.sha256 }
        })
        if ($Record.final.classification -ceq 'WINDOWS_GMD_PASS') { Assert-PassRelations $Record }
        $summary = Read-StructuredArtifact 'summary.json'
        # Commit marker is the last publication; no writes follow a valid marker.
        if (-not (Write-Artifact 'completion.json' @{ runId = $Record.runId; finishedUtc = $Record.finishedUtc
            classification = $Record.final.classification; writeComplete = $true
            terminalArtifact = @{ path = 'classification.txt'; length = $terminalBytes.LongLength; sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($terminalBytes)) }
            summarySha256 = $summary.sha256; artifacts = $artifacts })) { return $false }
        if (-not (Test-TerminalEvidence $Record.runId)) {
            [IO.File]::Delete((Join-Path $diagnostics 'completion.json'))
            return $false
        }
        return $true
    } catch { return $false }
}
function Assert-PassRelations($Record) {
    if ((Resolve-FinalClassification $Record.axes).classification -cne 'WINDOWS_GMD_PASS') { throw 'PASS_AXES_INCONSISTENT' }
    $before = (Read-StructuredArtifact 'manifest-before.json').value
    $after = (Read-StructuredArtifact 'manifest-after.json').value
    if ($before.fingerprint -cne (Get-AuthorityDigest $before.manifest) -or $after.fingerprint -cne (Get-AuthorityDigest $after.manifest) -or
        $before.fingerprint -cne $after.fingerprint) { throw 'PASS_AUTHORITY_INCONSISTENT' }
    $comparison = (Read-StructuredArtifact 'comparison.json').value
    if ($comparison.result -cne 'UNCHANGED' -or $comparison.beforeFingerprint -cne $before.fingerprint -or
        $comparison.afterFingerprint -cne $after.fingerprint) { throw 'PASS_COMPARISON_INCONSISTENT' }
    $policies = (Read-StructuredArtifact 'policy.json').value
    if ((Evaluate-ExecutionAuthorityPolicy $before.manifest).result -cne 'ALLOWED' -or
        (Evaluate-ExecutionAuthorityPolicy $after.manifest).result -cne 'ALLOWED' -or
        $policies.before.result -cne 'ALLOWED' -or $policies.after.result -cne 'ALLOWED') { throw 'PASS_POLICY_INCONSISTENT' }
    $process = (Read-StructuredArtifact 'gradle.process.json').value
    if ($process.exitCode -ne 0 -or $process.startedUtc -cne $Record.axes.execution.gradleStartedUtc -or
        -not (Read-StructuredArtifact 'preflight.json').value.completed -or
        @((Read-StructuredArtifact 'xml-before.json').value).Count) { throw 'PASS_EXECUTION_INCONSISTENT' }
    foreach ($name in @('pre-run-process-census.json', 'post-run-process-census.json')) {
        if ((Read-StructuredArtifact $name).value.conflict) { throw 'PASS_RUNTIME_OVERLAP' }
    }
    $ownership = (Read-StructuredArtifact 'ownership.json').value
    if (-not $ownership.emptyBeforeChild -or -not $ownership.bindingConfirmed -or $ownership.result -cne 'OWNED') { throw 'PASS_OWNERSHIP_INCONSISTENT' }
    $results = (Read-StructuredArtifact 'xml-results.json').value
    $contract = $before.manifest.invocationContract
    $invocation = (Read-StructuredArtifact 'invocation.json').value
    if ((Get-AuthorityDigest $invocation.ownedInit) -cne (Get-AuthorityDigest $contract.ownedOutput) -or
        (Get-AuthorityDigest $invocation.argv) -cne (Get-AuthorityDigest $contract.argv)) { throw 'PASS_INVOCATION_INCONSISTENT' }
    $names = if ($contract.selector) { @($contract.selector) } else { @(Get-ExpectedTestNames) }
    $read = Read-OwnedXmlResult ([DateTime]::Parse($Record.axes.execution.gradleStartedUtc).ToUniversalTime()) $true $contract.expectedTestCount $names
    if (-not $read.eligible -or (Get-AuthorityDigest $read) -cne (Get-AuthorityDigest $results) -or
        (Get-AuthorityDigest $read.files) -cne (Get-AuthorityDigest (Read-StructuredArtifact 'xml-after.json').value)) { throw 'PASS_XML_BYTES_INCONSISTENT' }
    $init = Get-OwnedInvocation
    if ((Get-AuthorityDigest $init) -cne (Get-AuthorityDigest $contract.ownedOutput)) { throw 'PASS_INIT_BYTES_INCONSISTENT' }
}
function Test-TerminalEvidence([string]$ExpectedRunId) {
    try {
        $c = (Read-StructuredArtifact 'completion.json').value
        $s = Read-StructuredArtifact 'summary.json'; $r = $s.value
        if ($r.Contains('requiredLogs')) { return $false }
        if ((Observe-Path (Join-Path $diagnostics 'classification.txt')).kind -cne 'file') { return $false }
        $terminalBytes = [IO.File]::ReadAllBytes((Join-Path $diagnostics 'classification.txt'))
        $expectedBytes = [Text.Encoding]::UTF8.GetBytes($c.classification + "`n")
        if ($c.terminalArtifact.path -cne 'classification.txt' -or $c.terminalArtifact.length -ne $terminalBytes.LongLength -or
            $c.terminalArtifact.sha256 -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($terminalBytes)) -or
            -not [Linq.Enumerable]::SequenceEqual([byte[]]$terminalBytes, [byte[]]$expectedBytes)) { return $false }
        if ($c.runId -cne $ExpectedRunId -or $r.runId -cne $c.runId -or $c.finishedUtc -cne $r.finishedUtc -or
            $c.classification -cne $r.final.classification -or $c.summarySha256 -cne $s.sha256 -or
            [IO.File]::ReadAllText((Join-Path $diagnostics 'classification.txt')).TrimEnd("`r", "`n") -cne $c.classification) { return $false }
        if ((Resolve-FinalClassification $r.axes).classification -cne $c.classification) { return $false }
        $names = @($c.artifacts | ForEach-Object { $_.path })
        if ($names.Count -ne @($names | Select-Object -Unique).Count -or $names.Count -ne $r.requiredArtifacts.Count -or
            (Compare-Object (Sort-Ordinal $names) (Sort-Ordinal $r.requiredArtifacts) -CaseSensitive)) { return $false }
        foreach ($base in @('summary.json', 'policy.json', 'comparison.json', 'provenance.json')) { if ($base -cnotin $names) { return $false } }
        if ($r.axes.capture -ceq 'SUCCESS' -and 'manifest-before.json' -cnotin $names) { return $false }
        if ($r.axes.post -cin @('UNCHANGED', 'MUTATED') -and 'manifest-after.json' -cnotin $names) { return $false }
        foreach ($a in $c.artifacts) {
            if ($a.path -cnotmatch '^[a-z-]+(?:\.process)?\.json$' -or $a.path -ceq 'completion.json') { return $false }
            $actual = Read-StructuredArtifact $a.path
            if ($actual.length -ne $a.length -or $actual.sha256 -cne $a.sha256) { return $false }
        }
        if ('xml-results.json' -cin $names) {
            $results = (Read-StructuredArtifact 'xml-results.json').value
            foreach ($file in $results.files) {
                $expectedPath = '<run>/gradle-build/app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
                if ($file.path -cne $expectedPath) { return $false }
                $path = Join-Path $owned.root 'app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
                if ((Observe-Path $path).kind -cne 'file') { return $false }
                $bytes = [IO.File]::ReadAllBytes($path)
                if ($file.length -ne $bytes.LongLength -or $file.sha256 -cne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))) { return $false }
            }
        }
        if ($c.classification -ceq 'WINDOWS_GMD_PASS') {
            foreach ($name in @('invocation.json', 'ownership.json', 'gradle.process.json', 'preflight.json', 'xml-before.json', 'xml-after.json', 'xml-results.json', 'pre-run-process-census.json', 'post-run-process-census.json')) { if ($name -cnotin $names) { return $false } }
            Assert-PassRelations $r
        }
        return $true
    } catch { return $false }
}
function Initialize-FixtureContext([string]$Root) {
    $script:worktree = [IO.Path]::GetFullPath($Root)
    $script:repoRoot = $script:worktree
    $script:childEnv = New-FiniteChildEnvironment @{ Path = $env:Path }
    $script:jvmOptionObservations = Get-JvmOptionObservations @{}
    $script:environmentConflict = $false
    $script:owned = @{ runId = 'fixture'; root = Join-Path $Root 'build/fixture-gradle-build'; initPath = Join-Path $Root 'build/owned-output.init.gradle' }
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'build'))
    if (-not (Test-Path -LiteralPath $owned.initPath)) { [IO.File]::WriteAllText($owned.initPath, (Get-OwnedInitText 'fixture'), [Text.UTF8Encoding]::new($false)) }
}
function Verify-CompletionFixtures([string]$Fixture, [string]$LauncherPath) {
    $root = Join-Path $Fixture 'completion-cases'
    [void][IO.Directory]::CreateDirectory($root)
    Initialize-FixtureContext $root
    foreach ($dir in @('gradle/wrapper', 'scripts')) { [void][IO.Directory]::CreateDirectory((Join-Path $root $dir)) }
    [IO.File]::Copy($LauncherPath, (Join-Path $root 'scripts/run-windows-gmd.ps1'))
    [IO.File]::WriteAllText((Join-Path $root 'gradle/wrapper/gradle-wrapper.properties'), 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip')
    [void](Git @('init', '--quiet')); [void](Git @('add', '.'))
    $id = 'completion-fixture-' + [Guid]::NewGuid().ToString('N')
    Initialize-OwnedRun $id
    $script:requiredArtifacts.Clear(); $script:publishedArtifacts.Clear()
    $script:writeState.failed = $false
    $script:configRedactions.Clear()
    $baseline = Capture-ExecutionAuthority 'Full' '' 66
    foreach ($secret in @('a', '1', 'true', '"\{value}', ('private-' + [Guid]::NewGuid().ToString('N')))) {
        $parent = @{ Path = $env:Path; PROBE_SECRET = $secret; API_TOKEN = $secret; ORDINARY_UNKNOWN = $secret
            ORG_GRADLE_PROJECT_unknown = $secret; THINKCANVAS_VERSION_CODE = $secret; THINKCANVAS_VERSION_NAME = $secret }
        $signingKeys = @('THINKCANVAS_INTERNAL_KEYSTORE_FILE', 'THINKCANVAS_INTERNAL_STORE_PASSWORD', 'THINKCANVAS_INTERNAL_KEY_ALIAS', 'THINKCANVAS_INTERNAL_KEY_PASSWORD')
        foreach ($key in $signingKeys) { $parent[$key] = $secret }
        $script:childEnv = New-FiniteChildEnvironment $parent
        $psi = New-Child 'unused.exe' @()
        Check 'SS finite child drops secret unknown project and signing keys' (@($parent.Keys | Where-Object { $_ -notin @('Path', 'THINKCANVAS_VERSION_CODE', 'THINKCANVAS_VERSION_NAME') -and $psi.Environment.ContainsKey($_) }).Count -eq 0)
        Check 'SS arbitrary parent version replaced by fixed public profile' ($psi.Environment['THINKCANVAS_VERSION_CODE'] -ceq '1' -and $psi.Environment['THINKCANVAS_VERSION_NAME'] -ceq '0.1.0')
        $snap = Capture-ExecutionAuthority 'Full' '' 66
        Check 'SS parent secrets neither block nor enter JSON or fingerprint' ((Evaluate-ExecutionAuthorityPolicy $snap.manifest).result -ceq 'ALLOWED' -and $snap.canonicalJson -ceq $baseline.canonicalJson)
        # Actual launcher child route emits only excluded fields: both streams must be empty.
        $probe = Invoke-Child (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile', '-Command', 'if (Test-Path Env:PROBE_SECRET) { Write-Output $env:PROBE_SECRET }; if (Test-Path Env:THINKCANVAS_INTERNAL_KEY_PASSWORD) { Write-Error $env:THINKCANVAS_INTERNAL_KEY_PASSWORD }') 'environment-probe'
        Check 'SS short inherited values absent from both actual child text logs' ($probe.exitCode -eq 0 -and $probe.stdout -ceq '' -and $probe.stderr -ceq '' -and
            [IO.File]::ReadAllText((Join-Path $diagnostics 'environment-probe.stdout.log')) -ceq '' -and [IO.File]::ReadAllText((Join-Path $diagnostics 'environment-probe.stderr.log')) -ceq '')
    }
    foreach ($key in @('JAVA_OPTS', 'GRADLE_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')) {
        $parent = @{ Path = $env:Path }; $parent[$key] = '-Dunreviewed=true'
        $script:childEnv = New-FiniteChildEnvironment $parent
        $script:jvmOptionObservations = Get-JvmOptionObservations $parent
        Check "SS forbidden option $key blocked before Java and not inherited" ('FORBIDDEN_JVM_OPTION' -cin (Evaluate-ExecutionAuthorityPolicy (Capture-ExecutionAuthority 'Full' '' 66).manifest).reasons -and -not $childEnv.ContainsKey($key))
    }
    $script:jvmOptionObservations = Get-JvmOptionObservations @{}
    $knownOption = '-Djdk.net.unixdomain.tmpdir=' + (Join-Path $systemRoot 'Temp')
    $script:childEnv = New-FiniteChildEnvironment @{ Path = $env:Path; JAVA_TOOL_OPTIONS = $knownOption }
    Check 'SS only exact public NIO option is launcher-set' ($childEnv['JAVA_TOOL_OPTIONS'] -ceq $knownOption -and
        -not @(Get-JvmOptionObservations @{ JAVA_TOOL_OPTIONS = $knownOption } | Where-Object { $_.forbidden }).Count)
    $script:childEnv = New-FiniteChildEnvironment @{ Path = $env:Path }
    $childEnv['PROBE_SECRET'] = 'a'
    $refused = $false; try { [void](New-Child 'unused.exe' @()) } catch { $refused = $true }
    Check 'SS corrupted child map fails closed on every launcher child path' $refused
    [void]$childEnv.Remove('PROBE_SECRET')
    foreach ($secret in @('a', '1', 'true', '"', '\', '{')) {
        $script:configRedactions.Clear(); [void]$configRedactions.Add($secret)
        $save = Save-DiagnosticText 'suppressed.log' $secret
        Check 'SS short ambiguous stream suppressed without bytes' ($save.status -ceq 'SUPPRESSED_SECRET_SAFETY' -and -not (Test-Path (Join-Path $diagnostics 'suppressed.log')))
    }
    $script:configRedactions.Clear(); [void]$configRedactions.Add('abcdef'); [void]$configRedactions.Add('cdefgh'); [void]$configRedactions.Add('redacted')
    Check 'SS overlap merged on original text without replacing placeholders' ((Protect-Text 'abcdefgh' -ConfigValues) -ceq '<redacted>')
    $script:configRedactions.Clear()
    $privatePath = Join-Path $childEnv['GRADLE_USER_HOME'] 'gradle.properties'
    [void][IO.Directory]::CreateDirectory((Split-Path $privatePath))
    [IO.File]::WriteAllText($privatePath, 'password=a')
    $handle = [IO.File]::Open($privatePath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $snap = Capture-ExecutionAuthority 'Full' '' 66
        Check 'SS external private properties observed without read or hash' ('EXTERNAL_PRIVATE_PROPERTIES_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $snap.manifest).reasons -and
            @($snap.manifest.externalConfig | Where-Object { $null -ne $_.sha256 -or $null -ne $_.length }).Count -eq 0)
    } finally { $handle.Dispose() }
    [IO.File]::Delete($privatePath)
    $distributionPath = Join-Path $snap.distribution.root 'gradle.properties'
    [void][IO.Directory]::CreateDirectory((Split-Path $distributionPath))
    [IO.File]::WriteAllText($distributionPath, 'password=true')
    $distributionSnap = Capture-ExecutionAuthority 'Full' '' 66
    Check 'SS distribution private properties presence-only policy block' ('EXTERNAL_PRIVATE_PROPERTIES_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $distributionSnap.manifest).reasons -and
        @($distributionSnap.manifest.externalConfig | Where-Object { $null -ne $_.sha256 -or $null -ne $_.length }).Count -eq 0)
    [IO.File]::Delete($distributionPath)
    $projectPath = Join-Path $root 'gradle.properties'
    [IO.File]::WriteAllText($projectPath, 'password=a')
    $snap = Capture-ExecutionAuthority 'Full' '' 66
    Check 'SS known secret project config rejected without enclosing hash' ('SECRET_SAFETY_POLICY_BLOCK' -cin (Evaluate-ExecutionAuthorityPolicy $snap.manifest).reasons -and
        @($snap.manifest.workingTreeFiles | Where-Object { $_.path -ceq '<worktree>/gradle.properties' -and $null -eq $_.sha256 }).Count -eq 1)
    [IO.File]::Delete($projectPath)
    $unsafe = ConvertFrom-StructuredJson $baseline.canonicalJson
    $unsafe.externalEnvironment['sha256'] = 'forbidden-verifier'
    Check 'SS forbidden environment verifier rejected before publication' (-not (Write-Artifact 'manifest-before.json' @{ manifest = $unsafe; fingerprint = $baseline.fingerprint }))
    $originalDiagnostics = $diagnostics
    $script:diagnostics = Join-Path $root 'missing-directory'
    $unavailable = Save-DiagnosticText 'optional.log' 'public diagnostic'
    $script:diagnostics = $originalDiagnostics
    Check 'SS optional text I/O failure does not fail structured writes' ($unavailable.status -ceq 'UNAVAILABLE_IO' -and -not $writeState.failed)
    $empty = $true; try { Assert-OwnedNamespaceEmpty } catch { $empty = $false }
    Check 'CE01 run namespace starts empty' $empty
    $reuse = $false; try { Initialize-OwnedRun $id } catch { $reuse = $true }
    Check 'CE namespace reuse rejected without cleanup' $reuse
    $init = Get-OwnedInvocation
    Check 'CE07 init exact bytes/hash stable' ((Get-AuthorityDigest $init) -ceq (Get-AuthorityDigest (Get-OwnedInvocation)))
    Check 'CE08 init only relocates build directories and validates binding' ($init.script.utf8 -match 'layout.buildDirectory.set' -and
        ([regex]::Matches($init.script.utf8, '\.set\(')).Count -eq 1 -and $init.script.utf8 -notmatch 'System.getenv|repositories|dependencies|sourceSets|doFirst|doLast|commandLine|exec\s*\{')
    Check 'CE root and app output mapping unique' ($init.projects.Count -eq 2 -and $init.projects[0].output -cne $init.projects[1].output)
    $name = 'com.thinkcanvas.canvas.InkGestureTest#secondFingerCancelsWetInkAndSingleFingerStillDraws'
    $childStart = [DateTime]::UtcNow.AddSeconds(-1)
    $ownedXml = Join-Path $owned.root 'app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
    function XmlText([string[]]$TestNames) {
        $body = @($TestNames | Group-Object { ($_ -split '#')[0] } | ForEach-Object {
            $class = $_.Name; $cases = @($_.Group | ForEach-Object { '<testcase classname="' + $class + '" name="' + ($_ -split '#')[1] + '" />' }) -join ''
            '<testsuite name="' + $class + '" tests="' + $_.Count + '" failures="0" errors="0" skipped="0"><properties><property name="device" value="_app_pixel7Api37DebugAndroidTest" /></properties>' + $cases + '</testsuite>'
        }) -join ''
        return '<testsuites tests="' + $TestNames.Count + '" failures="0" errors="0" skipped="0">' + $body + '</testsuites>'
    }
    $xmlText = XmlText @($name)
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $ownedXml))
    [IO.File]::WriteAllText($ownedXml, $xmlText)
    $block = $false; try { Assert-OwnedNamespaceEmpty } catch { $block = $true }
    Check 'CE02 pre-existing owned XML blocks invocation' $block
    [IO.File]::Delete($ownedXml)
    $shared = Join-Path $root 'app/build/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $shared)); [IO.File]::WriteAllText($shared, $xmlText)
    Check 'CE03 shared old XML alone cannot PASS' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::WriteAllText($shared, (XmlText @(Get-ExpectedTestNames)))
    Check 'CE04 unrelated concurrent shared XML update cannot PASS' ((Read-OwnedXmlResult $childStart $true 1 @($name)).classification -ceq 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE')
    [IO.File]::WriteAllText($ownedXml, $xmlText)
    Check 'CE05 exact owned focused result eligible' ((Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    Check 'CE unproven Gradle output binding rejected' (-not (Read-OwnedXmlResult $childStart $false 1 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, (XmlText @('com.thinkcanvas.canvas.InkGestureTest#wrongMethod')))
    Check 'CE06 wrong testcase count/name failure' ((Read-OwnedXmlResult $childStart $true 1 @($name)).classification -ceq 'WINDOWS_GMD_RESULT_COUNT_MISMATCH')
    [IO.File]::WriteAllText($ownedXml, (XmlText @($name, $name)))
    Check 'CE duplicate testcase rejected' (-not (Read-OwnedXmlResult $childStart $true 2 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, $xmlText.Replace('_app_pixel7Api37DebugAndroidTest', 'anotherDevice'))
    Check 'CE wrong device rejected' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, $xmlText.Replace('<testcase ', '<testcase ').Replace(' /></testsuite>', '><failure /></testcase></testsuite>'))
    Check 'CE zero failure counter cannot hide failure element' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, '<!DOCTYPE testsuites [<!ENTITY secret SYSTEM "file:///private">]>' + $xmlText)
    Check 'CE XML DTD prohibited' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, $xmlText.Substring(0, $xmlText.Length - 5))
    Check 'CE partial XML rejected' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::WriteAllText($ownedXml, (XmlText @(Get-ExpectedTestNames)))
    Check 'CE full finite 66 testcase identities eligible' ((Read-OwnedXmlResult $childStart $true 66 @(Get-ExpectedTestNames)).eligible)
    [IO.File]::WriteAllText($ownedXml, $xmlText)
    [IO.File]::SetLastWriteTimeUtc($ownedXml, $childStart.AddMinutes(-1))
    Check 'CE XML predating child rejected' (-not (Read-OwnedXmlResult $childStart $true 1 @($name)).eligible)
    [IO.File]::SetLastWriteTimeUtc($ownedXml, [DateTime]::UtcNow)
    $pc = ConvertTo-ProcessCensus @(@{ Name = 'java.exe'; ProcessId = 10; ParentProcessId = 1; CommandLine = "java -classpath $worktree/gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:pixel7Api37DebugAndroidTest" })
    Check 'CE09 same-worktree active Gradle/GMD hard block' $pc.conflict
    $idle = ConvertTo-ProcessCensus @(@{ Name = 'java.exe'; ProcessId = 11; ParentProcessId = 1; CommandLine = "java $worktree org.gradle.launcher.daemon.bootstrap.GradleDaemon" })
    Check 'CE idle daemon not active execution' (-not $idle.conflict)
    $manual = ConvertTo-ProcessCensus @(@{ Name = 'emulator.exe'; ProcessId = 12; ParentProcessId = 1; CommandLine = "emulator $worktree -avd ThinkCanvas_API37" })
    Check 'CE manual ThinkCanvas_API37 untouched' (-not $manual.conflict)
    $incomplete = ConvertTo-ProcessCensus @(@{ Name = 'java.exe'; ProcessId = 13; ParentProcessId = 1; CommandLine = $null })
    Check 'CE incomplete census recorded without automatic block' ($incomplete.completeness -ceq 'PARTIAL_COMMAND_LINE' -and -not $incomplete.conflict)
    $script:childEnv[$classKey] = $name
    foreach ($secret in @('a', '1', '"', '\', '{', 'true', '/', 'classification', ('secret-' + [Guid]::NewGuid().ToString('N')))) {
        # Parent values are excluded before capture, without even a value hash.
        $parent = @{ Path = $env:Path; PROBE_SECRET = $secret; ORG_GRADLE_PROJECT_probe = $secret
            THINKCANVAS_VERSION_CODE = $secret; THINKCANVAS_VERSION_NAME = $secret }
        foreach ($key in @('THINKCANVAS_INTERNAL_KEYSTORE_FILE', 'THINKCANVAS_INTERNAL_STORE_PASSWORD', 'THINKCANVAS_INTERNAL_KEY_ALIAS', 'THINKCANVAS_INTERNAL_KEY_PASSWORD')) { $parent[$key] = $secret }
        $script:childEnv = New-FiniteChildEnvironment $parent; $script:childEnv[$classKey] = $name
        $snap = Capture-ExecutionAuthority 'Focused' $name 1
        Save-Json 'manifest-before.json' @{ manifest = $snap.manifest; fingerprint = $snap.fingerprint }
        $round = (Read-StructuredArtifact 'manifest-before.json').value
        Check "CE10/11 filtered short parent does not change structured authority ($($secret.Length))" ($round.manifest.externalEnvironment.secretValuesRequired -eq 0 -and (Evaluate-ExecutionAuthorityPolicy $snap.manifest).result -ceq 'ALLOWED')
        if ($secret.StartsWith('secret-')) { Check 'CE11 raw unique secret absent from structured bytes' (-not [IO.File]::ReadAllText((Join-Path $diagnostics 'manifest-before.json')).Contains($secret)) }
    }
    $snap = Capture-ExecutionAuthority 'Focused' $name 1
    $scriptBytes = [IO.File]::ReadAllBytes($owned.initPath)
    [IO.File]::AppendAllText($owned.initPath, '// tamper')
    $tamper = Capture-ExecutionAuthority 'Focused' $name 1
    Check 'CE init tamper changes authority and policy rejects' ((Compare-ExecutionAuthority $snap $tamper).result -ceq 'MUTATED' -and (Evaluate-ExecutionAuthorityPolicy $tamper.manifest).result -ceq 'BLOCKED')
    [IO.File]::WriteAllBytes($owned.initPath, $scriptBytes)
    $pass = New-PhaseState; $pass.capture = 'SUCCESS'; $pass.policy = Evaluate-ExecutionAuthorityPolicy $snap.manifest; $pass.afterPolicy = $pass.policy; $pass.post = 'UNCHANGED'
    $pass.execution = @{ state = 'PASS_CANDIDATE'; classification = ''; reason = ''; xmlGatePassed = $true; ownershipPassed = $true; runtimePreflightPassed = $true; gradleExitCode = 0; gradleStartedUtc = $childStart.ToString('o') }
    $totals = Read-OwnedXmlResult $childStart $true 1 @($name)
    Save-Json 'preflight.json' @{ worktree = '<worktree>'; sharedRepositoryRoot = '<shared-repo-root>'; completed = $true }
    Save-Json 'invocation.json' @{ executable = '<java-home>/bin/java.exe'; argv = $snap.manifest.invocationContract.argv; ownedInit = Get-OwnedInvocation }
    Save-Json 'ownership.json' @{ namespace = '<run>/gradle-build'; emptyBeforeChild = $true; bindingConfirmed = $true; result = 'OWNED'; reason = 'OWNED_XML_ELIGIBLE' }
    Save-Json 'pre-run-process-census.json' $idle; Save-Json 'post-run-process-census.json' $idle
    Save-Json 'gradle.process.json' @{ startedUtc = $childStart.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o'); exitCode = 0; stdoutTruncated = $false; stderrTruncated = $false; diagnosticLogs = @{ stdout = 'SUPPRESSED_SECRET_SAFETY'; stderr = 'UNAVAILABLE_IO' } }
    Save-Json 'xml-before.json' @(); Save-Json 'xml-after.json' @($totals.files); Save-Json 'xml-results.json' $totals
    $record = @{ runId = $id; startedUtc = $childStart.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o'); axes = $pass; final = Resolve-FinalClassification $pass }
    $comparison = Compare-ExecutionAuthority $snap $snap
    Check 'CE17 PASS requires ownership diagnostics unchanged authority' (Write-TerminalEvidence $record $snap $snap $comparison @{})
    Check 'CE15 completion published last' ($publishedArtifacts[-1] -ceq 'completion.json')
    Check 'CE valid completion consumer' (Test-TerminalEvidence $id)
    $marker = (Read-StructuredArtifact 'completion.json').value
    Check 'SS completion has no requiredLogs and seals only structured artifacts' (-not $marker.Contains('requiredLogs') -and @($marker.artifacts | Where-Object { $_.path -notmatch '\.json$' }).Count -eq 0)
    $logPath = Join-Path $diagnostics 'gradle.stdout.log'
    [IO.File]::WriteAllText($logPath, 'diagnostic changed')
    Check 'SS text alteration does not invalidate completion' (Test-TerminalEvidence $id)
    [IO.File]::Delete($logPath)
    Check 'SS text deletion does not invalidate completion' (Test-TerminalEvidence $id)
    $xmlSaved = [IO.File]::ReadAllBytes($ownedXml)
    [IO.File]::AppendAllText($ownedXml, ' ')
    Check 'SS XML exact-byte tamper invalidates completion' (-not (Test-TerminalEvidence $id))
    [IO.File]::WriteAllBytes($ownedXml, $xmlSaved)
    Check 'CE stale run marker rejected' (-not (Test-TerminalEvidence 'another-run'))
    Check 'CE completed namespace immutable' (-not (Write-TerminalEvidence $record $snap $snap $comparison @{}))
    $comparisonPath = Join-Path $diagnostics 'comparison.json'; $saved = [IO.File]::ReadAllBytes($comparisonPath)
    [IO.File]::WriteAllText($comparisonPath, '{"schemaVersion":1')
    Check 'CE13 completion references corrupt JSON invalid' (-not (Test-TerminalEvidence $id))
    [IO.File]::WriteAllBytes($comparisonPath, $saved)
    [IO.File]::WriteAllText($comparisonPath, [Text.Encoding]::UTF8.GetString($saved).Replace('UNCHANGED', 'MUTATED'))
    Check 'CE14 valid JSON digest mismatch invalid' (-not (Test-TerminalEvidence $id))
    [IO.File]::WriteAllBytes($comparisonPath, $saved)
    $classificationPath = Join-Path $diagnostics 'classification.txt'
    [IO.File]::WriteAllText($classificationPath, 'WINDOWS_GMD_TEST_FAILURE')
    Check 'CE classification mismatch invalid' (-not (Test-TerminalEvidence $id))
    [IO.File]::WriteAllText($classificationPath, "WINDOWS_GMD_PASS`n")
    [IO.File]::Delete((Join-Path $diagnostics 'completion.json'))
    Check 'CE missing completion invalid' (-not (Test-TerminalEvidence $id))
    [IO.File]::WriteAllText((Join-Path $diagnostics 'xml-before.json'), '{"truncated":')
    $written = Write-TerminalEvidence $record $snap $snap $comparison @{}
    $pass.writeFailed = -not $written
    Check 'CE12 truncated required JSON diagnostic failure' (-not $written -and (Resolve-FinalClassification $pass).classification -ceq 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE')
    Check 'CE wrong schema never published as successful artifact' (-not (Write-Artifact 'xml-before.json' @{ wrong = $true }))
    $pass.execution.state = 'FAILURE'; $pass.execution.classification = 'WINDOWS_GMD_TEST_FAILURE'
    $decision = Resolve-FinalClassification $pass
    Check 'CE16 execution failure plus diagnostic failure preserves original INVALID' ($decision.classification -ceq 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE' -and $decision.validity -ceq 'INVALID' -and $decision.provisionalExecution.classification -ceq 'WINDOWS_GMD_TEST_FAILURE')
    $pass.writeFailed = $false; $pass.execution.state = 'PASS_CANDIDATE'; $pass.execution.classification = ''; $pass.execution.ownershipPassed = $false
    Check 'CE PASS candidate without ownership cannot PASS' ((Resolve-FinalClassification $pass).classification -cne 'WINDOWS_GMD_PASS')
}
function Verify-StaticFixtures {
    # Isolated synthetic repository only. No Java, Gradle, SDK or GMD invocation.
    $fixture = Join-Path $worktree ('build/diagnostics/authority-fixtures/' + [Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($fixture)
    $launcherPath = Join-Path $PSScriptRoot 'run-windows-gmd.ps1'
    $sourceNames = @(foreach ($f in (Get-ChildItem (Join-Path $worktree 'app/src/androidTest') -Recurse -Filter '*.kt')) {
        $text = [IO.File]::ReadAllText($f.FullName)
        if ($text -cmatch '(?m)^package ([A-Za-z0-9_.]+)') {
            $package = $Matches[1]
            if ($text -cmatch '\bclass ([A-Za-z0-9_]+)') {
                $class = $Matches[1]
                foreach ($m in [regex]::Matches($text, '@Test\s+fun\s+([A-Za-z0-9_]+)\s*\(')) { "$package.$class#$($m.Groups[1].Value)" }
            }
        }
    })
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
    Check 'current main finite 66 identities match source census' ($sourceNames.Count -eq 66 -and
        -not (Compare-Object (Sort-Ordinal $sourceNames) (Sort-Ordinal @(Get-ExpectedTestNames)) -CaseSensitive))
    function PassState {
        $s = New-PhaseState; $s.capture = 'SUCCESS'; $s.policy = @{ result = 'ALLOWED'; reasons = @() }; $s.afterPolicy = $s.policy; $s.post = 'UNCHANGED'
        $s.execution = @{ state = 'PASS_CANDIDATE'; classification = ''; reason = ''; xmlGatePassed = $true; ownershipPassed = $true; runtimePreflightPassed = $true; gradleExitCode = 0 }
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
    Check '12 private properties no enclosing verifier' ((Compare-ExecutionAuthority $propertyChange (Snapshot)).result -ceq 'UNCHANGED' -and
        'EXTERNAL_PRIVATE_PROPERTIES_PRESENT' -cin (Evaluate-ExecutionAuthorityPolicy $propertyChange.manifest).reasons -and @($propertyChange.manifest.externalConfig | Where-Object { $null -ne $_.sha256 -or $null -ne $_.length }).Count -eq 0)
    $secretCapture = Snapshot
    Check '12 private properties remain policy block' ((Evaluate-ExecutionAuthorityPolicy $secretCapture.manifest).result -ceq 'BLOCKED')
    Check '13 raw secret absent from manifest' (-not $secretCapture.canonicalJson.Contains($fixtureSecret))
    [void]$configRedactions.Add($fixtureSecret)
    Check '13 child secret redaction' (-not (Protect-Text $fixtureSecret -ConfigValues).Contains($fixtureSecret))
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
    [IO.File]::WriteAllText((Join-Path $fixture 'gradle.properties'), 'systemProp.gradle.user.home=elsewhere')
    Check 'home redirect blocked' ('GRADLE_USER_HOME_REDIRECT' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    $script:jvmOptionObservations = Get-JvmOptionObservations @{ JAVA_OPTS = '-Dunreviewed=true' }
    Check 'forbidden JVM options policy blocked' ('FORBIDDEN_JVM_OPTION' -cin (Evaluate-ExecutionAuthorityPolicy (Snapshot).manifest).reasons)
    $state = PassState; $state.policy = $initPolicy; $state.afterPolicy = $initPolicy; $state.post = 'RECAPTURE_FAILURE'
    Check 'real recapture failure invalidates block preserves reason' ((Resolve-FinalClassification $state).originalPolicy.reasons -contains 'EXTERNAL_INIT_PRESENT')
    # Required terminal writer failure injection via a nonexistent destination.
    $script:diagnostics = Join-Path $fixture 'missing/evidence'
    $failureState = PassState; $failureState.execution.state = 'FAILURE'; $failureState.execution.classification = 'WINDOWS_GMD_TEST_FAILURE'
    $record = @{ runId = 'fixture'; startedUtc = [DateTime]::UtcNow.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o'); axes = $failureState; final = Resolve-FinalClassification $failureState }
    Check 'actual terminal write failure reported' (-not (Write-TerminalEvidence $record $stable $stable @{ result = 'UNCHANGED' } @{}))
    $script:diagnostics = Join-Path $fixture 'build/evidence'
    Check 'complete terminal writer success' (Write-TerminalEvidence $record $secretCapture $secretCapture @{ result = 'UNCHANGED' } @{})
    $diagnosticText = (Get-ChildItem -LiteralPath $diagnostics -File | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
    Check '13 raw secret absent from saved diagnostics' (-not $diagnosticText.Contains($fixtureSecret))
    Check 'completion round trip valid' (Test-TerminalEvidence 'fixture')
    [IO.File]::Delete((Join-Path $diagnostics 'completion.json'))
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
    Verify-CompletionFixtures $fixture $launcherPath
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
$runId = $started.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N')
$diagnostics = Join-Path $worktree "build/diagnostics/windows-gmd/$runId"
$state = New-PhaseState
$before = $null; $after = $null; $comparison = @{ result = 'NOT_APPLICABLE' }; $provenance = @{}
try { Initialize-OwnedRun $runId } catch {
    $writeState.failed = $true
    $state.execution.state = 'FAILURE'; $state.execution.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; $state.execution.reason = 'RUN_NAMESPACE_NOT_NEW_OR_WRITABLE'
    $diagnostics = $null
}
try {
    # Bind invocation before capture; no Java/Gradle/runtime probes precede policy.
    if ($PSCmdlet.ParameterSetName -eq 'Focused') {
        if ($Test -cnotmatch '^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*#[A-Za-z_$][A-Za-z0-9_$]*$') { throw 'Focused mode requires one nonempty Class#method' }
        $ExpectedTestCount = 1
    }
    if (-not $Test -and $ExpectedTestCount -ne @(Get-ExpectedTestNames).Count) { throw 'Full mode requires current finite suite count' }
    if ($Test -and $Test -cnotin @(Get-ExpectedTestNames)) { throw 'Focused test is outside current finite suite' }
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
        try { $provenance = @{ before = Get-Provenance } } catch { $provenance = @{ before = @{ head = $null; branchDigest = $null }; limitation = 'GIT_PROVENANCE_UNAVAILABLE' } }
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
    if ($state.capture -ceq 'NOT_ATTEMPTED' -and $state.execution.state -cne 'FAILURE') {
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
