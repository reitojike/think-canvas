#Requires -Version 7.3
[CmdletBinding(DefaultParameterSetName = 'Full')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Full')][ValidateRange(1, 100000)][int]$ExpectedTestCount,
    [Parameter(Mandatory, ParameterSetName = 'Focused')][AllowEmptyString()][string]$Test,
    [Parameter(Mandatory, ParameterSetName = 'Static')][switch]$VerifyStatic
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$worktree = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
$repoRoot = $worktree
$systemRoot = [Environment]::GetFolderPath('Windows')
$classKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.class'
$regexKey = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.tests_regex'
# Known-good Windows GMD GPU mode, same as .github/workflows/android.yml. The AGP default (auto-no-window)
# left emulator-5554 offline after boot and timed out the test task on this host.
$gpuProperty = '-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect'
$runtimeEnvironmentKeys = @('Path', 'TEMP', 'TMP', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA',
    'LOCALAPPDATA', 'ProgramData', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')
$environmentConflict = $false
$appliedMitigation = @()
$diagnostics = $null
$owned = $null
# Selective salvage: #56 process launch, #57 properties, #58 byte scope,
# #59 owned XML and #60 finite environment. No completion attestation layer.
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
    $result = Invoke-Child $gitExe (@('-c', "safe.directory=$worktree", '-c', 'core.excludesfile=', '-C', $worktree) + $Arguments) ''
    if ($result.exitCode -ne 0) { throw 'Git source-state capture failed' }
    return $result.stdout.TrimEnd("`r", "`n")
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

function Sort-Ordinal([string[]]$Values) {
    $sorted = [string[]]@($Values)
    [Array]::Sort($sorted, [StringComparer]::Ordinal)
    return ,$sorted
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
    // AGP exposes multiple result directories. Bind all to this run; the XML reader
    // separately requires the exact managed-device XML location and device identity.
    def resultRoot = base.canonicalFile.toPath()
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
        if ($paths.Count -eq 0) { $r.classification = 'WINDOWS_GMD_RUNTIME_NON_EXECUTION'; $r.reason = 'FRESH_XML_NOT_GENERATED'; return $r }
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
    $psi = New-Child $gitExe @('-c', "safe.directory=$worktree", '-c', 'core.excludesfile=', '-C', $worktree, 'check-ignore', '-z', '--stdin')
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

function Save-Json([string]$Name, $Value) {
    # Structured objects contain fixed identifiers and typed facts; never redact JSON.
    $json = ConvertTo-Json -InputObject $Value -Depth 20
    [IO.File]::WriteAllText((Join-Path $diagnostics $Name), $json, [Text.UTF8Encoding]::new($false))
    $null = [IO.File]::ReadAllText((Join-Path $diagnostics $Name)) | ConvertFrom-Json -ErrorAction Stop
}
function Safe-Path([string]$Value) {
    foreach ($pair in @(@($worktree, '<worktree>'), @($repoRoot, '<shared-repo-root>'),
        @($env:USERPROFILE, '<user-profile>'), @($systemRoot, '<windows>'))) {
        if ($pair[0]) { $Value = $Value.Replace($pair[0], $pair[1]).Replace($pair[0].Replace('\', '/'), $pair[1]) }
    }
    return $Value.Replace('\', '/')
}
function Save-Stream([string]$Name, [string]$Value) {
    # No execution secret is required. External config is rejected; parent credentials
    # are dropped. Suppress credential-looking text instead of publishing raw values.
    if ($Value -match '(?i)(ghp_|gho_|github_pat_|(?:password|token|secret|credential|api.?key)\s*[=:]|https?://[^\s/@]+:[^\s/@]+@)') {
        return @{ status = 'SUPPRESSED_SECRET_SAFETY'; truncated = $false }
    }
    $safe = Safe-Path $Value
    $safe = $safe -replace '(?m)^\s*user\.name = .+$', '    user.name = <redacted>'
    $truncated = $safe.Length -gt 2MB
    if ($truncated) { $safe = '<tail only>' + "`n" + $safe.Substring($safe.Length - 2MB) }
    try {
        [IO.File]::WriteAllText((Join-Path $diagnostics $Name), $safe, [Text.UTF8Encoding]::new($false))
        return @{ status = 'SAVED_DIAGNOSTIC_ONLY'; truncated = $truncated }
    } catch { return @{ status = 'UNAVAILABLE_IO'; truncated = $truncated } }
}
function Invoke-Child([string]$Executable, [string[]]$Arguments, [string]$LogName) {
    $p = [Diagnostics.Process]::new(); $p.StartInfo = New-Child $Executable $Arguments
    $begin = [DateTime]::UtcNow
    try {
        [void]$p.Start()
        $out = $p.StandardOutput.ReadToEndAsync(); $err = $p.StandardError.ReadToEndAsync()
        $p.WaitForExit()
        $r = @{ exitCode = $p.ExitCode; stdout = $out.GetAwaiter().GetResult(); stderr = $err.GetAwaiter().GetResult()
            startedUtc = $begin.ToString('o'); finishedUtc = [DateTime]::UtcNow.ToString('o') }
        if ($LogName) {
            Save-Json "$LogName.process.json" @{ startedUtc = $r.startedUtc; finishedUtc = $r.finishedUtc; exitCode = $r.exitCode
                stdout = Save-Stream "$LogName.stdout.log" $r.stdout; stderr = Save-Stream "$LogName.stderr.log" $r.stderr }
        }
        return $r
    } finally { $p.Dispose() }
}
function Assert-Selector([string]$Selector) {
    if ($Selector -cnotmatch '^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*#[A-Za-z_$][A-Za-z0-9_$]*$') {
        throw 'SINGLE_CLASS_METHOD_REQUIRED'
    }
}
function Get-ExpectedTestNames {
    # Current suite uses one AndroidJUnit4 class per Kotlin file and plain @Test methods.
    # Unsupported runner shapes fail closed rather than guessing a count.
    $names = @()
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $worktree 'app/src/androidTest') -Recurse -File -Filter '*.kt')) {
        $text = [IO.File]::ReadAllText($file.FullName)
        $annotations = [regex]::Matches($text, '@Test\b')
        if (-not $annotations.Count) { continue }
        if ($text -match '@Ignore\b|Parameterized|@Test\s*\(') { throw 'UNSUPPORTED_TEST_CENSUS' }
        $package = [regex]::Match($text, '(?m)^package\s+([\w.]+)\s*$')
        $classes = [regex]::Matches($text, '(?m)^class\s+(\w+)\s*\{')
        $methods = [regex]::Matches($text, '@Test\s+fun\s+(\w+)\s*\(\s*\)')
        if (-not $package.Success -or $classes.Count -ne 1 -or $methods.Count -ne $annotations.Count) { throw 'UNSUPPORTED_TEST_CENSUS' }
        foreach ($m in $methods) { $names += $package.Groups[1].Value + '.' + $classes[0].Groups[1].Value + '#' + $m.Groups[1].Value }
    }
    if (-not $names.Count -or @($names | Select-Object -Unique).Count -ne $names.Count) { throw 'INVALID_TEST_CENSUS' }
    return ,(Sort-Ordinal $names)
}
function Assert-ExternalConfig($Distribution) {
    foreach ($root in @($childEnv['GRADLE_USER_HOME'], $Distribution.root)) {
        if ((Observe-Path (Join-Path $root 'gradle.properties')).present) { throw 'EXTERNAL_PROPERTIES_PRESENT' }
        if ($root -eq $childEnv['GRADLE_USER_HOME']) {
            foreach ($name in @('init.gradle', 'init.gradle.kts', 'init.gradle.dcl')) {
                if ((Observe-Path (Join-Path $root $name)).present) { throw 'EXTERNAL_INIT_PRESENT' }
            }
        }
        $dir = Observe-Path (Join-Path $root 'init.d')
        if ($dir.present) {
            if ($dir.kind -ne 'directory') { throw 'EXTERNAL_INIT_PATH_UNSUPPORTED' }
            if (@(Get-ChildItem -LiteralPath $dir.item.FullName -Force | Where-Object { $_.Name -cmatch '\.gradle(?:\.kts|\.dcl)?$' }).Count) {
                throw 'EXTERNAL_INIT_PRESENT'
            }
        }
    }
}
function Get-SourceSnapshot {
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradle.properties',
        'gradle.lockfile', 'gradlew', 'gradlew.bat', 'scripts/run-windows-gmd.ps1')) { [void]$paths.Add($path) }
    foreach ($root in @('', 'app', 'buildSrc')) {
        foreach ($name in @('local.properties', 'build.gradle.dcl', 'settings.gradle.dcl')) {
            $path = if ($root) { "$root/$name" } else { $name }
            if ((Observe-Path (Join-Path $worktree $path)).present) { throw 'LOCAL_OR_UNSUPPORTED_CONFIG_PRESENT' }
        }
    }
    if ((Observe-Path (Join-Path $worktree 'buildSrc')).present) { throw 'UNSUPPORTED_BUILD_TOPOLOGY' }
    foreach ($root in @('gradle', 'app')) {
        $pending = [Collections.Generic.Stack[string]]::new(); $pending.Push($root)
        while ($pending.Count) {
            $dir = Observe-Path (Join-Path $worktree $pending.Pop())
            if ($dir.kind -ne 'directory') { throw 'SOURCE_PATH_UNSUPPORTED' }
            foreach ($item in (Get-ChildItem -LiteralPath $dir.item.FullName -Force)) {
                $path = [IO.Path]::GetRelativePath($worktree, $item.FullName).Replace('\', '/')
                if (-not (Test-ExecutionPath $path)) { continue }
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'SOURCE_PATH_REDIRECTED' }
                if ($item.PSIsContainer) { $pending.Push($path) } else { [void]$paths.Add($path) }
            }
        }
    }
    foreach ($record in ((Git @('ls-files', '-v', '-z')) -split "`0" | Where-Object { $_ })) {
        if ((Test-ExecutionPath $record.Substring(2)) -and ($record[0] -ceq 'S' -or [char]::IsLower($record[0]))) { throw 'HIDDEN_INDEX_FLAG_PRESENT' }
    }
    $ordered = Sort-Ordinal @($paths)
    if (@(Get-IgnoredActiveFiles $ordered).Count) { throw 'IGNORED_ACTIVE_SOURCE_CONFIG' }
    $records = @(foreach ($path in $ordered) {
        $item = Observe-Path (Join-Path $worktree $path)
        if ($item.kind -notin @('absent', 'file')) { throw 'SOURCE_PATH_UNSUPPORTED' }
        $hash = $null; $length = $null
        if ($item.present) {
            $bytes = [IO.File]::ReadAllBytes($item.item.FullName)
            if ($path -cmatch '(^|/)gradle\.properties$') {
                $properties = Read-AuthorityProperties $bytes
                # No private value hashes; reject unsupported execution overrides before hashing.
                foreach ($key in $properties.Keys) {
                    if ($key -match '(?i)password|token|secret|credential|keystore|api.?key') { throw 'PRIVATE_PROJECT_PROPERTIES' }
                    if ($key -match '^(systemProp\.|android\.testInstrumentationRunnerArguments\.|org\.gradle\.java\.home$)') { throw 'COMPETING_PROPERTY_CONFIG' }
                }
            }
            if ($path -cmatch '\.gradle(?:\.kts)?$') {
                $text = [Text.Encoding]::UTF8.GetString($bytes)
                $includes = if ($path -ceq 'settings.gradle.kts') { $text -creplace '\binclude\(":app"\)', '' } else { $text }
                if ($text -cmatch '\b(includeBuild|apply\s*\(|apply\s+from|mavenLocal\s*\(|projectDir\s*=|srcDirs?\s*[=(]|setSrcDirs\s*\()' -or $includes -cmatch '\binclude\b') {
                    throw 'UNSUPPORTED_BUILD_TOPOLOGY'
                }
            }
            $length = $bytes.LongLength; $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
        }
        [ordered]@{ path = $path; present = $item.present; length = $length; sha256 = $hash }
    })
    $dist = Get-DistributionSelection ([IO.File]::ReadAllBytes((Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties')))
    if ($dist.status -ne 'SUPPORTED') { throw 'UNSUPPORTED_WRAPPER_SELECTION' }
    Assert-ExternalConfig $dist
    return [ordered]@{ head = Git @('rev-parse', 'HEAD'); files = $records }
}
function Test-WrapperCache($Selection) {
    return (Observe-Path (Join-Path $Selection.cacheDirectory ($Selection.name + '.zip.ok'))).kind -eq 'file' -and
        (Observe-Path (Join-Path $Selection.root 'lib')).kind -eq 'directory' -and
        (Observe-Path (Join-Path $Selection.root 'bin/gradle.bat')).kind -eq 'file'
}
function Get-GmdArguments {
    return @('-Dfile.encoding=UTF-8', '-Xmx64m', '-Xms64m', '-Dorg.gradle.appname=gradlew', '-classpath',
        (Join-Path $worktree 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain',
        ':app:pixel7Api37DebugAndroidTest', '--rerun', '--no-daemon', '--offline', '--console=plain',
        '-Pandroid.builder.sdkDownload=false', '-Porg.gradle.java.installations.auto-download=false',
        $gpuProperty, '--init-script', $owned.initPath)
}

function Initialize-Run {
    $id = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N')
    $path = Join-Path $worktree "build/diagnostics/windows-gmd/$id"
    if ((Observe-Path $path).present) { throw 'RUN_NAMESPACE_EXISTS' }
    [void][IO.Directory]::CreateDirectory($path)
    if ((Observe-Path $path).kind -ne 'directory' -or @(Get-ChildItem -LiteralPath $path -Force).Count) { throw 'RUN_NAMESPACE_NOT_NEW' }
    $script:diagnostics = $path
    $script:owned = @{ runId = $id; root = Join-Path $path 'gradle-build'; initPath = Join-Path $path 'owned-output.init.gradle' }
    [IO.File]::WriteAllText($owned.initPath, (Get-OwnedInitText $id), [Text.UTF8Encoding]::new($false))
    [void][IO.Directory]::CreateDirectory($owned.root)
}
function Resolve-Runtime {
    if (-not $IsWindows) { throw 'WINDOWS_REQUIRED' }
    if ($environmentConflict) { throw 'ENVIRONMENT_KEY_CONFLICT' }
    foreach ($key in $runtimeEnvironmentKeys) {
        if (-not $childEnv.ContainsKey($key) -or -not $childEnv[$key]) { continue }
        $paths = if ($key -eq 'Path') { @($childEnv[$key] -split ';' | Where-Object { $_ }) } else { @($childEnv[$key]) }
        foreach ($path in $paths) {
            if ($key -eq 'HOMEDRIVE' -and $path -match '^[A-Za-z]:$') { continue }
            if ($key -eq 'HOMEPATH' -and $path -match '^\\[^:]') { continue }
            if ($path -match '[\r\n"<>|?*]' -or $path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') { throw 'UNSAFE_RUNTIME_PATH' }
        }
    }
    $sdk = $null
    foreach ($key in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        if ($childEnv.ContainsKey($key) -and $childEnv[$key]) { $sdk = $childEnv[$key]; break }
    }
    if (-not $sdk) { $sdk = Join-Path $childEnv['LOCALAPPDATA'] 'Android/Sdk' }
    $sdk = [IO.Path]::GetFullPath($sdk)
    $childEnv['ANDROID_HOME'] = $sdk; $childEnv['ANDROID_SDK_ROOT'] = $sdk
    foreach ($component in @('platform-tools/adb.exe', 'emulator/emulator.exe', 'platforms/android-37.0/android.jar',
        'build-tools/36.0.0/aapt2.exe', 'system-images/android-37.0/google_apis/x86_64/system.img')) {
        if (-not (Test-Path -LiteralPath (Join-Path $sdk $component) -PathType Leaf)) { throw 'SDK_COMPONENT_MISSING' }
    }
    $exe = if ($childEnv.ContainsKey('JAVA_HOME') -and $childEnv['JAVA_HOME']) {
        Join-Path $childEnv['JAVA_HOME'] 'bin/java.exe'
    } else { @(Get-Command java.exe -CommandType Application -ErrorAction Stop)[0].Source }
    $java = Invoke-Child $exe @('-XshowSettings:properties', '-version') 'java-version'
    if ($java.exitCode -ne 0 -or $java.stderr -notmatch '(?m)^\s*java.home = (.+)\r?$') { throw 'JAVA_HOME_PROBE_FAILED' }
    $childEnv['JAVA_HOME'] = $Matches[1].Trim()
    if ($java.stderr -notmatch '(?m)^\s*java.specification.version = 25\s*$') { throw 'JAVA_25_REQUIRED' }
    $script:javaExe = Join-Path $childEnv['JAVA_HOME'] 'bin/java.exe'
    Save-Json 'runtime.json' @{ javaHome = Safe-Path $childEnv['JAVA_HOME']; androidSdk = Safe-Path $sdk
        gradleUserHome = Safe-Path $childEnv['GRADLE_USER_HOME']; androidUserHome = Safe-Path $childEnv['ANDROID_USER_HOME']
        environmentKeys = (Sort-Ordinal @($childEnv.Keys)); systemRootPresent = $true; windirPresent = $true
        pathEntries = @($childEnv['Path'] -split ';').Count; versionCode = '1'; versionName = '0.1.0' }
    foreach ($tool in @(@('adb', 'platform-tools/adb.exe', 'version'), @('emulator', 'emulator/emulator.exe', '-version'))) {
        $r = Invoke-Child (Join-Path $sdk $tool[1]) @($tool[2]) "$($tool[0])-version"
        if ($r.exitCode -ne 0) { throw ($tool[0].ToUpperInvariant() + '_PROBE_FAILED') }
    }
}
function Verify-StaticChecks {
    function Check([bool]$Value, [string]$Name) {
        if (-not $Value) { throw ('FIXTURE_FAILED_' + $Name) }
        $script:fixturePassed++
    }
    $script:fixturePassed = 0
    foreach ($bad in @('', 'a.b.C', 'a.b.C#m,a.b.C#n', 'a.b.C#(m)', ' a.b.C#m')) {
        $rejected = $false; try { Assert-Selector $bad } catch { $rejected = $true }; Check $rejected 'SELECTOR_REJECT'
    }
    Assert-Selector 'a.b.C#m'; Check $true 'SELECTOR_ACCEPT'
    $oldEnv = $childEnv; $oldOwned = $owned; $oldTree = $worktree
    $script:childEnv = New-FiniteChildEnvironment @{ Path = $env:Path; TOKEN = 'a'; THINKCANVAS_INTERNAL_KEY_PASSWORD = 'true'
        ORG_GRADLE_PROJECT_arbitrary = 'private'; THINKCANVAS_VERSION_CODE = '99' }
    $psi = New-Child 'unused.exe' @('single argument')
    Check (-not $psi.Environment.ContainsKey('TOKEN') -and -not $psi.Environment.ContainsKey('THINKCANVAS_INTERNAL_KEY_PASSWORD') -and
        -not $psi.Environment.ContainsKey('ORG_GRADLE_PROJECT_arbitrary') -and $psi.Environment['THINKCANVAS_VERSION_CODE'] -eq '1') 'SECRET_DROP'
    Check (@($psi.Environment.Keys | Where-Object { $_ -ieq 'Path' }).Count -eq 1) 'PATH_CASE'
    $script:childEnv = $oldEnv
    $script:owned = @{ initPath = 'fixture-init.gradle' }; $gmdArgs = @(Get-GmdArguments); $script:owned = $oldOwned
    Check (@($gmdArgs | Where-Object { $_ -ceq $gpuProperty }).Count -eq 1 -and
        @($gmdArgs | Where-Object { $_ -like '*emulator.gpu*' }).Count -eq 1) 'GPU_PROPERTY_ONCE'
    $allNames = Get-ExpectedTestNames; Check ($allNames.Count -eq 66) 'SOURCE_CENSUS_66'
    $dist = Get-DistributionSelection ([IO.File]::ReadAllBytes((Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties')))
    Check ($dist.cacheDirectory.EndsWith('3m7h6ceboy5k31n8kzwzuxssm')) 'WRAPPER_CACHE_KEY'
    Check (Test-WrapperCache $dist) 'REAL_CACHE_PRESENT'
    $fixture = Join-Path $diagnostics 'fixtures'; [void][IO.Directory]::CreateDirectory($fixture)
    $script:owned = @{ root = Join-Path $fixture 'outputs' }
    $xmlPath = Join-Path $owned.root 'app/outputs/androidTest-results/managedDevice/debug/pixel7Api37/TEST-pixel7Api37.xml'
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($xmlPath))
    $start = [DateTime]::UtcNow.AddMinutes(-1)
    function XmlFixture([string]$Name, [int]$Count) {
        $case = if ($Count) { '<testcase classname="a.b.C" name="' + $Name + '" />' } else { '' }
        [IO.File]::WriteAllText($xmlPath, '<testsuites tests="' + $Count + '" failures="0" errors="0" skipped="0"><testsuite name="a.b.C" tests="' + $Count + '" failures="0" errors="0" skipped="0"><properties><property name="device" value="_app_pixel7Api37DebugAndroidTest" /></properties>' + $case + '</testsuite></testsuites>')
    }
    XmlFixture 'm' 0; Check (-not (Read-OwnedXmlResult $start $true 1 @('a.b.C#m')).eligible) 'ZERO_TEST_FAIL'
    XmlFixture 'wrong' 1; Check (-not (Read-OwnedXmlResult $start $true 1 @('a.b.C#m')).eligible) 'WRONG_NAME_FAIL'
    XmlFixture 'm' 1; Check ((Read-OwnedXmlResult $start $true 1 @('a.b.C#m')).eligible) 'EXACT_XML_PASS'
    Check (-not (Read-OwnedXmlResult ([DateTime]::UtcNow.AddMinutes(1)) $true 1 @('a.b.C#m')).eligible) 'STALE_XML_FAIL'
    Check (-not (Read-OwnedXmlResult $start $false 1 @('a.b.C#m')).eligible) 'FOREIGN_BINDING_FAIL'
    $script:owned = @{ root = Join-Path $fixture 'different-run' }
    Check (-not (Read-OwnedXmlResult $start $true 1 @('a.b.C#m')).eligible) 'OTHER_RUN_XML_FAIL'
    $script:owned = $oldOwned
    $snapshot = Get-SourceSnapshot
    Save-Json 'static-source.json' $snapshot
    $script:worktree = Join-Path $fixture 'source'; [void][IO.Directory]::CreateDirectory($worktree)
    $script:childEnv = New-FiniteChildEnvironment @{}
    $script:childEnv['GRADLE_USER_HOME'] = Join-Path $worktree 'missing-cache'
    $missingDist = Get-DistributionSelection ([IO.File]::ReadAllBytes((Join-Path $oldTree 'gradle/wrapper/gradle-wrapper.properties')))
    Check (-not (Test-WrapperCache $missingDist)) 'MISSING_CACHE_FAIL'
    [void][IO.Directory]::CreateDirectory($childEnv['GRADLE_USER_HOME'])
    [IO.File]::WriteAllText((Join-Path $childEnv['GRADLE_USER_HOME'] 'gradle.properties'), 'token=synthetic')
    $blocked = $false; try { Assert-ExternalConfig $missingDist } catch { $blocked = $true }; Check $blocked 'EXTERNAL_CONFIG_FAIL'
    $script:worktree = $oldTree; $script:childEnv = $oldEnv
    # Actual-byte comparison fixture in an isolated copy, including hidden index flags.
    $sourceCopy = Join-Path $fixture 'repo'
    $clone = Invoke-Child $gitExe @('-c', "safe.directory=$repoRoot", '-c', "safe.directory=$repoRoot/.git", 'clone', '--quiet', '--no-hardlinks', '--no-checkout', $repoRoot, $sourceCopy) 'fixture-clone'
    if ($clone.exitCode) { throw 'FIXTURE_CLONE_FAILED' }
    $script:worktree = $sourceCopy
    $null = Git @('checkout', '--quiet', $snapshot.head)
    Copy-Item -LiteralPath (Join-Path $oldTree 'scripts/run-windows-gmd.ps1') -Destination (Join-Path $sourceCopy 'scripts/run-windows-gmd.ps1')
    $a = Get-SourceSnapshot
    [IO.File]::AppendAllText((Join-Path $sourceCopy 'gradle.properties'), "`norg.gradle.parallel=false`n")
    $b = Get-SourceSnapshot
    Check ((ConvertTo-Json -InputObject $a -Depth 10 -Compress) -cne (ConvertTo-Json -InputObject $b -Depth 10 -Compress)) 'SOURCE_BYTES_MISMATCH'
    $null = Git @('update-index', '--assume-unchanged', 'gradle.properties')
    $blocked = $false; try { $null = Get-SourceSnapshot } catch { $blocked = $_.Exception.Message -eq 'HIDDEN_INDEX_FLAG_PRESENT' }; Check $blocked 'HIDDEN_INDEX_FAIL'
    $null = Git @('update-index', '--no-assume-unchanged', 'gradle.properties')
    $null = Git @('update-index', '--skip-worktree', 'gradle.properties')
    $blocked = $false; try { $null = Get-SourceSnapshot } catch { $blocked = $_.Exception.Message -eq 'HIDDEN_INDEX_FLAG_PRESENT' }; Check $blocked 'SKIP_WORKTREE_FAIL'
    $script:worktree = $oldTree
    Save-Json 'static-checks.json' @{ passed = $fixturePassed; sourceCensus = $allNames.Count; gradleInvocations = 0 }
    Write-Output "STATIC_PASS: $fixturePassed checks; source census $($allNames.Count); Gradle not started"
}

$parent = [Environment]::GetEnvironmentVariables()
$childEnv = New-FiniteChildEnvironment $parent
$optionFacts = Get-JvmOptionObservations $parent
$parent = $null
$gitExe = @(Get-Command git.exe -CommandType Application -ErrorAction Stop)[0].Source
$summary = [ordered]@{ runId = ''; startedUtc = [DateTime]::UtcNow.ToString('o'); finishedUtc = $null
    mode = $PSCmdlet.ParameterSetName; phase = 'preflight'; classification = 'WINDOWS_GMD_PREFLIGHT_BLOCKED'; reason = ''
    expectedTestCount = $ExpectedTestCount; selector = $Test; childExit = $null; childStartedUtc = $null; childFinishedUtc = $null
    xml = $null; sourceUnchanged = $null; appliedMitigation = @(); manualInterventions = 0; complete = $false }
$before = $null; $initHash = $null
try {
    Initialize-Run; $summary.runId = $owned.runId
    if ((Git @('rev-parse', '--show-toplevel')).Replace('/', '\') -ine $worktree.Replace('/', '\')) { throw 'WORKTREE_ROOT_MISMATCH' }
    $common = Git @('rev-parse', '--path-format=absolute', '--git-common-dir')
    if ((Split-Path -Leaf $common) -ne '.git') { throw 'UNSUPPORTED_SHARED_REPOSITORY_LAYOUT' }
    $repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $common))
    $childEnv['GRADLE_USER_HOME'] = Join-Path $repoRoot '.gradle-user'
    $childEnv['ANDROID_USER_HOME'] = Join-Path $childEnv['GRADLE_USER_HOME'] 'android-user'
    if ($VerifyStatic) { Verify-StaticChecks; $summary.classification = 'WINDOWS_GMD_STATIC_PASS'; $summary.phase = 'static'; exit 0 }
    if (@($optionFacts | Where-Object { $_.forbidden }).Count) { throw 'FORBIDDEN_JVM_OPTION' }
    $summary.appliedMitigation = @($appliedMitigation)
    if ($childEnv.ContainsKey('JAVA_TOOL_OPTIONS')) { $summary.appliedMitigation += 'JAVA_NIO_UNIXDOMAIN_TMPDIR' }
    [void]$childEnv.Remove($classKey); [void]$childEnv.Remove($regexKey)
    $names = Get-ExpectedTestNames
    if ($PSCmdlet.ParameterSetName -eq 'Focused') {
        Assert-Selector $Test
        if ($Test -cnotin $names) { throw 'SELECTOR_NOT_IN_SOURCE_CENSUS' }
        $childEnv[$classKey] = $Test; $ExpectedTestCount = 1; $names = @($Test)
    } elseif ($names.Count -ne $ExpectedTestCount) { throw 'EXPECTED_COUNT_DIFFERS_FROM_SOURCE' }
    $summary.expectedTestCount = $ExpectedTestCount
    Save-Json 'selector.json' @{ classExact = $(if ($Test) { $childEnv[$classKey] -ceq $Test } else { $false })
        classUnset = -not $childEnv.ContainsKey($classKey); testsRegexUnset = -not $childEnv.ContainsKey($regexKey); expectedNames = $names }
    $before = Get-SourceSnapshot
    Save-Json 'source-before.json' $before
    Save-Json 'provenance.json' @{ head = $before.head; branch = Git @('branch', '--show-current'); worktree = '<worktree>'; repository = '<shared-repo-root>' }
    $dist = Get-DistributionSelection ([IO.File]::ReadAllBytes((Join-Path $worktree 'gradle/wrapper/gradle-wrapper.properties')))
    Save-Json 'wrapper-cache.json' @{ distributionUrl = $dist.url; cache = Safe-Path $dist.cacheDirectory; ready = Test-WrapperCache $dist }
    if (-not (Test-WrapperCache $dist)) { $summary.classification = 'WINDOWS_GMD_WRAPPER_DISTRIBUTION_MISSING'; throw 'WRAPPER_CACHE_MISSING_NO_DOWNLOAD' }
    $census = Get-ProcessCensus; Save-Json 'process-before.json' $census
    if ($census.conflict) { throw 'SAME_WORKTREE_ACTIVE_EXECUTION' }
    $tracking = Join-Path $childEnv['ANDROID_USER_HOME'] 'avd/gradle-managed/active_gradle_devices'
    $locks = 0
    if ((Observe-Path $tracking).present) {
        $text = [IO.File]::ReadAllText($tracking)
        if ($text -notmatch '^MDLockCount\s+(\d+)\s*$') { throw 'UNKNOWN_GMD_TRACKING_FORMAT' }
        $locks = [int]$Matches[1]
    }
    Save-Json 'gmd-state.json' @{ trackingPresent = (Observe-Path $tracking).present; MDLockCount = $locks
        sidecarPresent = (Observe-Path "$tracking.lock").present; automaticCleanup = $false }
    if ($locks -ge 4) { throw 'GMD_DEVICE_LIMIT_SATURATED' }
    Resolve-Runtime
    if (@(Get-ChildItem -LiteralPath $owned.root -Force).Count) { throw 'OWNED_NAMESPACE_NOT_EMPTY' }
    $argv = Get-GmdArguments
    $initHash = (Get-FileHash -LiteralPath $owned.initPath).Hash
    Save-Json 'invocation.json' @{ executable = Safe-Path $javaExe; argv = @($argv | ForEach-Object { Safe-Path $_ })
        emptyResultNamespace = $true; initTemplate = 'BuildDirectoryRelocation/v1'; initSha256 = $initHash }
    Save-Json 'xml-before.json' @()
    $summary.phase = 'wrapper-start'; $summary.childStartedUtc = [DateTime]::UtcNow.ToString('o')
    Save-Json 'summary.json' $summary
    Write-Host "GMD running once; diagnostics: $diagnostics"
    $g = Invoke-Child $javaExe $argv 'gradle'
    $summary.childExit = $g.exitCode; $summary.childStartedUtc = $g.startedUtc; $summary.childFinishedUtc = $g.finishedUtc
    $log = $g.stdout + "`n" + $g.stderr
    $summary.phase = 'gradle'
    if ($log -match ':app:pixel7Api37Setup') { $summary.phase = 'gmd-setup' }
    if ($log -match 'Starting \d+ tests|INSTRUMENTATION_|\d+ tests completed') { $summary.phase = 'instrumentation' }
    $binding = $log.Contains("WINDOWS_GMD_OUTPUT_BOUND|$($owned.runId)|:app:pixel7Api37DebugAndroidTest")
    $xml = Read-OwnedXmlResult ([DateTime]::Parse($g.startedUtc).ToUniversalTime()) $binding $ExpectedTestCount $names
    $summary.xml = $xml
    Save-Json 'xml-results.json' $xml
    Save-Json 'ownership.json' @{ runId = $owned.runId; emptyBeforeChild = $true; bindingConfirmed = $binding; ownedXml = $xml.ownershipPassed }
    $postCensus = Get-ProcessCensus; Save-Json 'process-after.json' $postCensus
    if ($xml.files.Count) { $summary.phase = 'xml-results' }
    if ($xml.ownershipPassed -and $xml.tests -gt 0 -and ($xml.failures -or $xml.errors -or $xml.skipped)) {
        $summary.classification = 'WINDOWS_GMD_TEST_FAILURE'; $summary.reason = $xml.reason
    } elseif ($log -match 'Timed out trying to check default_boot .* is loadable') {
        $summary.classification = 'WINDOWS_GMD_DEFAULT_BOOT_TIMEOUT'; $summary.reason = 'DEFAULT_BOOT_NOT_LOADABLE'
    } elseif ($log -match 'Could not acquire device lock|Failed to setup|pixel7Api37Setup FAILED') {
        $summary.classification = 'WINDOWS_GMD_SETUP_FAILURE'; $summary.reason = 'GMD_SETUP_FAILED'
    } elseif ($log -match 'WINDOWS_GMD_OUTPUT_BINDING_FAILURE|WINDOWS_GMD_OWNERSHIP_TOPOLOGY') {
        $summary.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; $summary.reason = 'TASK_OUTPUT_BINDING_FAILED'
    } elseif ($g.exitCode -ne 0) {
        $summary.classification = 'WINDOWS_GMD_GRADLE_FAILURE'; $summary.reason = 'CHILD_NONZERO_EXIT'
    } elseif (-not $xml.eligible) {
        $summary.classification = $xml.classification; $summary.reason = $xml.reason
    } else { $summary.classification = 'WINDOWS_GMD_PASS'; $summary.reason = 'FRESH_EXACT_XML' }
    if ($postCensus.conflict) { $summary.classification = 'WINDOWS_GMD_RESULT_OWNERSHIP_FAILURE'; $summary.reason = 'OVERLAPPING_ACTIVE_EXECUTION' }
} catch {
    # Exceptions are fixed reason codes; never persist free-form exceptions or config.
    $reason = $_.Exception.Message
    if ($reason -cnotmatch '^[A-Z][A-Z0-9_]+$') { $reason = 'PHASE_OPERATION_FAILED' }
    $summary.reason = $reason
    if ($summary.childStartedUtc) { $summary.classification = 'WINDOWS_GMD_GRADLE_FAILURE' }
} finally {
    if ($before) {
        try {
            $after = Get-SourceSnapshot; Save-Json 'source-after.json' $after
            $same = (ConvertTo-Json -InputObject $before -Depth 10 -Compress) -ceq (ConvertTo-Json -InputObject $after -Depth 10 -Compress)
            if ($initHash -and (Get-FileHash -LiteralPath $owned.initPath).Hash -cne $initHash) { $same = $false }
            $summary.sourceUnchanged = $same
            if (-not $same) { $summary.observedClassification = $summary.classification; $summary.classification = 'WINDOWS_GMD_SOURCE_CONFIG_MISMATCH'; $summary.reason = 'EXECUTION_INPUT_CHANGED' }
        } catch { $summary.observedClassification = $summary.classification; $summary.classification = 'WINDOWS_GMD_SOURCE_CONFIG_MISMATCH'; $summary.reason = 'RECAPTURE_FAILED' }
    }
    $summary.finishedUtc = [DateTime]::UtcNow.ToString('o'); $summary.complete = $true
    if ($diagnostics) {
        try {
            Save-Json 'summary.json' $summary
            [IO.File]::WriteAllText((Join-Path $diagnostics 'classification.txt'), $summary.classification + "`n")
        } catch { $summary.classification = 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE' }
    } else { $summary.classification = 'WINDOWS_GMD_DIAGNOSTIC_WRITE_FAILURE' }
}
Write-Output $summary.classification
Write-Output "Reason: $($summary.reason)"
Write-Output "Diagnostics: $diagnostics"
if ($summary.classification -eq 'WINDOWS_GMD_PASS') { exit 0 }
exit 1
