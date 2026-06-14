param(
    [string]$VersionFile = 'version.properties',
    [string]$ChangelogPath = 'CHANGELOG.md',
    [string]$ReleaseTag,
    [switch]$RequireUnreleasedEntries,
    [switch]$CheckCommitMessage
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

if (!(Test-Path -LiteralPath $VersionFile)) {
    Fail "Missing $VersionFile."
}

$versionValues = @{}
foreach ($line in Get-Content -LiteralPath $VersionFile) {
    if ($line -match '^\s*([^#][^=]+?)\s*=\s*(.*?)\s*$') {
        $versionValues[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$versionName = $versionValues['VERSION_NAME']
$versionCode = $versionValues['VERSION_CODE']

if ($versionName -notmatch '^\d+\.\d+\.\d+$') {
    Fail "VERSION_NAME must be SemVer in X.Y.Z form. Actual: '$versionName'."
}

if ($versionCode -notmatch '^\d+$') {
    Fail "VERSION_CODE must be numeric. Actual: '$versionCode'."
}

if ([int64]$versionCode -le 0) {
    Fail "VERSION_CODE must be greater than zero."
}

if (!(Test-Path -LiteralPath $ChangelogPath)) {
    Fail "Missing $ChangelogPath."
}

$changelog = Get-Content -LiteralPath $ChangelogPath -Raw
if ($changelog -notmatch '(?m)^## \[Unreleased\]\s*$') {
    Fail "CHANGELOG.md must contain '## [Unreleased]'."
}

if ($RequireUnreleasedEntries) {
    $match = [regex]::Match($changelog, '(?ms)^## \[Unreleased\]\s*(.*?)(?=^## \[|\z)')
    if (!$match.Success -or $match.Groups[1].Value -notmatch '(?m)^- \S') {
        Fail "CHANGELOG.md must contain at least one Unreleased bullet entry."
    }
}

if ($ReleaseTag) {
    $tagVersion = $ReleaseTag -replace '^refs/tags/', '' -replace '^v', ''
    if ($tagVersion -ne $versionName) {
        Fail "Release tag version '$tagVersion' does not match VERSION_NAME '$versionName'."
    }
    $escapedVersion = [regex]::Escape($versionName)
    if ($changelog -notmatch "(?m)^## \[$escapedVersion\] - \d{4}-\d{2}-\d{2}\s*$") {
        Fail "CHANGELOG.md must contain a dated section for [$versionName]."
    }
}

if ($CheckCommitMessage) {
    $subject = (& git log -1 --pretty=%s).Trim()
    if ($LASTEXITCODE -ne 0 -or $subject.Length -eq 0) {
        Fail 'Unable to read latest git commit subject.'
    }
    if ($subject -notmatch '^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|security)(\([A-Za-z0-9._-]+\))?!?: .+') {
        Fail "Latest commit subject is not Conventional Commit format: '$subject'."
    }
}

Write-Host "Release state validation passed for VERSION_NAME=$versionName VERSION_CODE=$versionCode."
