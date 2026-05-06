param(
    [string]$Serial = "10.100.102.14:5555",
    [string]$PackageName = "com.rutv",
    [string]$ActivityName = "com.rutv/.presentation.MainActivity",
    [int]$LaunchRuns = 3,
    [int]$InteractionSteps = 8,
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"

function Get-ADBPath {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return "adb"
}

function Parse-IntValue([string]$line) {
    if ($line -match ':\s*([0-9]+)') { return [int]$matches[1] }
    return $null
}

function Find-Line([object[]]$lines, [string]$pattern) {
    $match = $lines | Select-String $pattern | Select-Object -First 1
    if ($null -eq $match) { return "" }
    return $match.Line.Trim()
}

$adb = Get-ADBPath
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $OutFile = "build/perf/profile-$stamp.txt"
}

New-Item -ItemType Directory -Force -Path (Split-Path $OutFile -Parent) | Out-Null

$results = [ordered]@{
    timestamp = (Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")
    serial = $Serial
    package = $PackageName
    activity = $ActivityName
    launch = @()
    gfxinfo = [ordered]@{}
    sections = [ordered]@{}
    meminfo = [ordered]@{}
    cpuSamples = @()
    perfLogHighlights = @()
}

Write-Host "Using ADB: $adb"
& "$adb" devices -l | Out-Host

# Startup timing
for ($i = 1; $i -le $LaunchRuns; $i++) {
    & "$adb" -s $Serial shell am force-stop $PackageName | Out-Null
    Start-Sleep -Milliseconds 700
    $raw = & "$adb" -s $Serial shell am start -W -n $ActivityName
    $statusLine = Find-Line $raw "Status"
    $totalLine = Find-Line $raw "TotalTime"
    $waitLine = Find-Line $raw "WaitTime"
    $thisLine = Find-Line $raw "ThisTime"
    $item = [ordered]@{
        run = $i
        status = $statusLine
        totalTimeMs = Parse-IntValue($totalLine)
        waitTimeMs = Parse-IntValue($waitLine)
        thisTimeMs = Parse-IntValue($thisLine)
    }
    $results.launch += $item
    Write-Host ("Launch #{0}: Total={1}ms Wait={2}ms" -f $i, $item.totalTimeMs, $item.waitTimeMs)
    Start-Sleep -Seconds 2
}

function Read-GfxSection([string]$Name) {
    $section = [ordered]@{}
    $gfx = & "$adb" -s $Serial shell dumpsys gfxinfo $PackageName
    $gfxLines = $gfx | Select-String -Pattern "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands"
    foreach ($line in $gfxLines) {
        $section[$line.Line.Trim()] = $true
    }
    $results.sections[$Name] = $section
    return $section
}

function Start-AppForProfile([int]$WarmupSeconds = 4) {
    & "$adb" -s $Serial shell dumpsys gfxinfo $PackageName reset | Out-Null
    & "$adb" -s $Serial shell am force-stop $PackageName | Out-Null
    Start-Sleep -Milliseconds 500
    & "$adb" -s $Serial shell am start -n $ActivityName | Out-Null
    Start-Sleep -Seconds $WarmupSeconds
}

# Combined startup + simple horizontal interaction, preserved as the main gate.
Start-AppForProfile -WarmupSeconds 4
for ($i = 0; $i -lt $InteractionSteps; $i++) {
    & "$adb" -s $Serial shell input keyevent 22 | Out-Null
    & "$adb" -s $Serial shell input keyevent 21 | Out-Null
    Start-Sleep -Milliseconds 350
}
Start-Sleep -Seconds 2
$combined = Read-GfxSection "combined_startup_interaction"
foreach ($key in $combined.Keys) { $results.gfxinfo[$key] = $true }

# Playlist-focused profile: wait for playback setup, open playlist, navigate visible rows.
Start-AppForProfile -WarmupSeconds 6
& "$adb" -s $Serial shell input keyevent 21 | Out-Null
Start-Sleep -Seconds 1
for ($i = 0; $i -lt $InteractionSteps; $i++) {
    & "$adb" -s $Serial shell input keyevent 20 | Out-Null
    & "$adb" -s $Serial shell input keyevent 19 | Out-Null
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Seconds 1
Read-GfxSection "playlist_navigation" | Out-Null

# EPG-focused profile: wait for playback setup, open EPG, navigate rows/date edge work.
Start-AppForProfile -WarmupSeconds 6
& "$adb" -s $Serial shell input keyevent 22 | Out-Null
Start-Sleep -Seconds 2
for ($i = 0; $i -lt $InteractionSteps; $i++) {
    & "$adb" -s $Serial shell input keyevent 20 | Out-Null
    & "$adb" -s $Serial shell input keyevent 19 | Out-Null
    Start-Sleep -Milliseconds 250
}
Start-Sleep -Seconds 1
Read-GfxSection "epg_navigation" | Out-Null

# Meminfo
$mem = & "$adb" -s $Serial shell dumpsys meminfo $PackageName
foreach ($line in $mem) {
    if ($line -match "TOTAL PSS:\s*([0-9]+)\s+TOTAL RSS:\s*([0-9]+)") {
        $results.meminfo.totalPssKb = [int]$matches[1]
        $results.meminfo.totalRssKb = [int]$matches[2]
    }
    if ($line -match "Native Heap\s+([0-9]+)\s+([0-9]+)") {
        $results.meminfo.nativeHeapPssKb = [int]$matches[1]
    }
    if ($line -match "Dalvik Heap\s+([0-9]+)\s+([0-9]+)") {
        $results.meminfo.dalvikHeapPssKb = [int]$matches[1]
    }
    if ($line -match "Graphics:\s+([0-9]+)") {
        $results.meminfo.graphicsKb = [int]$matches[1]
    }
}

# CPU samples under interaction
for ($i = 1; $i -le 6; $i++) {
    & "$adb" -s $Serial shell input keyevent 20 | Out-Null
    & "$adb" -s $Serial shell input keyevent 19 | Out-Null
    $topLine = (& "$adb" -s $Serial shell "top -b -n 1 | grep $PackageName")
    if ($topLine) { $results.cpuSamples += $topLine.Trim() }
    Start-Sleep -Milliseconds 500
}

# Performance log highlights
$perfLog = & "$adb" -s $Serial logcat -d -t 1200
$highlights = $perfLog | Select-String -Pattern "Skipped .* frames|OpenGLRenderer: Davey|ANR in $PackageName|Input dispatching timed out|am_anr|am_crash"
$results.perfLogHighlights = $highlights | Select-Object -ExpandProperty Line

# Print summary
Write-Host "`n=== Launch Summary ==="
$launchTotals = $results.launch | ForEach-Object { $_.totalTimeMs } | Where-Object { $_ -ne $null }
if ($launchTotals.Count -gt 0) {
    $avg = [math]::Round((($launchTotals | Measure-Object -Average).Average), 1)
    $min = ($launchTotals | Measure-Object -Minimum).Minimum
    $max = ($launchTotals | Measure-Object -Maximum).Maximum
    Write-Host ("TotalTime ms -> avg={0}, min={1}, max={2}" -f $avg, $min, $max)
}

Write-Host "`n=== Gfxinfo Highlights ==="
$results.gfxinfo.Keys | ForEach-Object { Write-Host $_ }

Write-Host "`n=== Gfxinfo Sections ==="
$results.sections.GetEnumerator() | ForEach-Object {
    Write-Host ("[{0}]" -f $_.Key)
    $_.Value.Keys | ForEach-Object { Write-Host $_ }
}

Write-Host "`n=== Meminfo ==="
$results.meminfo.GetEnumerator() | ForEach-Object { Write-Host ("{0}: {1}" -f $_.Key, $_.Value) }

Write-Host "`n=== CPU Samples ==="
$results.cpuSamples | ForEach-Object { Write-Host $_ }

Write-Host "`n=== Perf Log Highlights ==="
$results.perfLogHighlights | Select-Object -Last 40 | ForEach-Object { Write-Host $_ }

$results | ConvertTo-Json -Depth 8 | Set-Content -Path $OutFile
Write-Host "`nSaved profile report: $OutFile"
