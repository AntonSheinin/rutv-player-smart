# AGENTS.md

This file documents repo-specific commands that work in this environment so future agents do not need to rediscover them.

## Build the app (Windows/PowerShell)

Notes:
- This repo has `gradlew` but no `gradlew.bat`.
- Running `./gradlew` directly from PowerShell may fail depending on shell setup.
- Reliable method here: invoke Gradle Wrapper main class via Java directly.

Command:

```powershell
& 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe' `
  -classpath gradle\wrapper\gradle-wrapper.jar `
  org.gradle.wrapper.GradleWrapperMain `
  :app:compileDebugKotlin :app:assembleDebug --console=plain
```

APK output location:

```powershell
app/build/outputs/apk/debug/
```

Find latest debug APK:

```powershell
Get-ChildItem app/build/outputs/apk/debug/*.apk |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1 FullName,Length,LastWriteTime
```

## ADB remote install (STB over IP)

Use explicit adb path (works even when `adb` is not in PATH):

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

Connect/check device:

```powershell
& $adb connect 10.100.102.10:5555
& $adb devices -l
```

Install/reinstall APK:

```powershell
& $adb -s 10.100.102.10:5555 install -r app/build/outputs/apk/debug/rutv-debug-1.4.apk
```

Launch app:

```powershell
& $adb -s 10.100.102.10:5555 shell am start -n com.rutv/.presentation.MainActivity
```

## Startup timing check

Single cold-start measurement:

```powershell
& $adb -s 10.100.102.10:5555 shell am force-stop com.rutv
Start-Sleep -Milliseconds 700
& $adb -s 10.100.102.10:5555 shell am start -W -n com.rutv/.presentation.MainActivity
```

Multiple cold-start runs:

```powershell
1..5 | ForEach-Object {
  & $adb -s 10.100.102.10:5555 shell am force-stop com.rutv | Out-Null
  Start-Sleep -Milliseconds 900
  & $adb -s 10.100.102.10:5555 shell am start -W -n com.rutv/.presentation.MainActivity
  Start-Sleep -Milliseconds 700
}
```

## Crash check from logs

```powershell
$logs = & $adb -s 10.100.102.10:5555 logcat -d -t 5000
$logs | Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime|am_crash|ANR in com.rutv|Process: com.rutv'
```

## Profiling script

Use the repo script:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/profile-adb.ps1 `
-Serial 10.100.102.10:5555 `
  -PackageName com.rutv `
  -ActivityName com.rutv/.presentation.MainActivity `
  -LaunchRuns 3 `
  -InteractionSteps 8
```

Reports are saved under:

```powershell
build/perf/
```
