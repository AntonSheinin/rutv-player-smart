param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('feat', 'fix', 'change', 'perf', 'refactor', 'remove', 'security')]
    [string]$Type,

    [string]$Scope,

    [Parameter(Mandatory = $true)]
    [string]$Description,

    [string]$ChangelogPath = 'CHANGELOG.md'
)

$ErrorActionPreference = 'Stop'

function Get-Category([string]$CommitType) {
    switch ($CommitType) {
        'feat' { return 'Added' }
        'fix' { return 'Fixed' }
        'remove' { return 'Removed' }
        'security' { return 'Security' }
        default { return 'Changed' }
    }
}

function Get-EntryText([string]$CommitType, [string]$Text) {
    $normalized = $Text.Trim()
    if ($normalized.Length -eq 0) {
        throw 'Description cannot be empty.'
    }

    if ($normalized.EndsWith('.')) {
        $normalized = $normalized.Substring(0, $normalized.Length - 1)
    }

    switch ($CommitType) {
        'feat' {
            if ($normalized -notmatch '^(Added|Add)\b') { $normalized = "Added $normalized" }
        }
        'fix' {
            if ($normalized -notmatch '^(Fixed|Fix)\b') { $normalized = "Fixed $normalized" }
        }
        'remove' {
            if ($normalized -notmatch '^(Removed|Remove)\b') { $normalized = "Removed $normalized" }
        }
        'security' {
            if ($normalized -notmatch '^(Security|Fixed|Hardened)\b') { $normalized = "Fixed $normalized" }
        }
        default {
            if ($normalized -notmatch '^(Changed|Improved|Updated|Reduced|Increased|Moved|Renamed)\b') {
                $normalized = "Changed $normalized"
            }
        }
    }

    return "- $normalized."
}

if (!(Test-Path -LiteralPath $ChangelogPath)) {
    @(
        '# Changelog'
        ''
        'All notable user-visible changes to RuTV are documented in this file.'
        ''
        '## [Unreleased]'
        ''
    ) | Set-Content -LiteralPath $ChangelogPath -Encoding utf8
}

$category = Get-Category $Type
$entry = Get-EntryText $Type $Description
$lines = [System.Collections.Generic.List[string]](Get-Content -LiteralPath $ChangelogPath)

if (-not ($lines -contains '## [Unreleased]')) {
    $insertAt = 0
    if ($lines.Count -gt 0 -and $lines[0] -match '^# ') {
        $insertAt = 1
        while ($insertAt -lt $lines.Count -and $lines[$insertAt].Trim() -ne '') { $insertAt++ }
        while ($insertAt -lt $lines.Count -and $lines[$insertAt].Trim() -eq '') { $insertAt++ }
    }
    $lines.Insert($insertAt, '## [Unreleased]')
    $lines.Insert($insertAt + 1, '')
}

$unreleasedIndex = $lines.IndexOf('## [Unreleased]')
$nextReleaseIndex = $lines.Count
for ($i = $unreleasedIndex + 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^## \[') {
        $nextReleaseIndex = $i
        break
    }
}

for ($i = $unreleasedIndex + 1; $i -lt $nextReleaseIndex; $i++) {
    if ($lines[$i] -eq $entry) {
        Write-Host "Changelog entry already exists under Unreleased: $entry"
        exit 0
    }
}

$categoryHeader = "### $category"
$categoryIndex = -1
for ($i = $unreleasedIndex + 1; $i -lt $nextReleaseIndex; $i++) {
    if ($lines[$i] -eq $categoryHeader) {
        $categoryIndex = $i
        break
    }
}

if ($categoryIndex -lt 0) {
    $insertAt = $unreleasedIndex + 1
    while ($insertAt -lt $lines.Count -and $lines[$insertAt].Trim() -eq '') { $insertAt++ }
    $lines.Insert($insertAt, $categoryHeader)
    $lines.Insert($insertAt + 1, '')
    $lines.Insert($insertAt + 2, $entry)
    $lines.Insert($insertAt + 3, '')
} else {
    $insertAt = $categoryIndex + 1
    while ($insertAt -lt $nextReleaseIndex -and $lines[$insertAt].Trim() -eq '') { $insertAt++ }
    $lines.Insert($insertAt, $entry)
}

$lines | Set-Content -LiteralPath $ChangelogPath -Encoding utf8
Write-Host "Added changelog entry under ${category}: $entry"
