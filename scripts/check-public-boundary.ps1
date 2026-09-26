# Checks candidate public files without printing any matching secret value.
$ErrorActionPreference = 'Stop'

$files = @(git -c core.excludesfile= ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not enumerate Git files.'
}

$forbiddenPath = '(?i)(^|/)(local\.properties|google-services\.json|GoogleService-Info\.plist|secrets\.properties|PRD(\.[^/]*)?|\.env(\.[^/]*)?|[^/]+\.(pem|p8|p12|jks|keystore|key))$|(^|/)(private|non-public|conversation-logs)/|(^|/)[^/]*mock[^/]*\.html?$'
$secretPatterns = @(
    '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '(?:ghp_|gho_|github_pat_)[A-Za-z0-9_]{20,}',
    'AKIA[0-9A-Z]{16}',
    'AIza[0-9A-Za-z_-]{30,}',
    '(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\s*[:=]\s*["'']?[A-Za-z0-9+/=_-]{16,}',
    '[A-Za-z]:\\Users\\[^\\\s]+\\',
    ('/Users/' + '[^/\s]+/')
)

$issues = New-Object System.Collections.Generic.List[string]
foreach ($path in $files) {
    $normalized = $path.Replace('\', '/')
    if ($normalized -notmatch '(?i)(^|/)\.env\.example$' -and $normalized -match $forbiddenPath) {
        $issues.Add("Forbidden path: $path")
        continue
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    if ($normalized -match '(?i)\.(jar|png|jpg|jpeg|gif|webp|ico|pdf|zip)$') { continue }
    try {
        $content = [System.IO.File]::ReadAllText((Join-Path (Get-Location) $path))
    } catch {
        $issues.Add("Unreadable file: $path")
        continue
    }
    foreach ($pattern in $secretPatterns) {
        if ($content -match $pattern) {
            $issues.Add("Sensitive content pattern: $path")
            break
        }
    }
}

if ($issues.Count -gt 0) {
    $issues | Sort-Object -Unique | ForEach-Object { Write-Output $_ }
    exit 1
}

Write-Output "Public boundary check passed ($($files.Count) candidate files)."
