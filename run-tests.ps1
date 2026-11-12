# Compile MvolaAutomation. Does not install or launch an application.
# Usage: Right-click -> Run with PowerShell, or: .\run-tests.ps1

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

# Refresh PATH so newly added Maven is picked up
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' +
            [Environment]::GetEnvironmentVariable('Path', 'User')

# Use JDK 11+ (project requires Java 11; JDK 17 is installed on this machine)
$jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot"
if (Test-Path $jdk17) {
    $env:JAVA_HOME = $jdk17
    $env:Path = "$jdk17\bin;" + $env:Path
    Write-Host "Using JAVA_HOME: $env:JAVA_HOME"
} else {
    Write-Warning "JDK 17 not found at $jdk17. Set JAVA_HOME manually to Java 11+."
}

# Android SDK from config.properties (androidSdkPath)
$configFile = Join-Path $projectRoot "src\main\resources\config.properties"
$androidSdk = $null
Get-Content $configFile | ForEach-Object {
    if ($_ -match '^\s*androidSdkPath\s*=\s*(.+)\s*$') {
        $androidSdk = $Matches[1].Trim() -replace '/', '\'
    }
}
if ($androidSdk -and (Test-Path $androidSdk)) {
    $env:ANDROID_HOME = $androidSdk
    $env:ANDROID_SDK_ROOT = $androidSdk
    $env:Path = "$androidSdk\platform-tools;$androidSdk\emulator;" + $env:Path
    Write-Host "Using ANDROID_HOME from config.properties: $env:ANDROID_HOME"
} else {
    Write-Warning "androidSdkPath in config.properties is missing or not found: $androidSdk"
}

# Check Maven
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    Write-Error @"
Maven (mvn) not found in PATH.

1. Extract apache-maven-*-bin.zip
2. Add the Maven bin folder to PATH, e.g. C:\Program Files\Apache\apache-maven-3.9.16\bin
3. Open a new terminal and run this script again
"@
}

# Check connected Android device
$adb = if ($androidSdk) { Join-Path $androidSdk "platform-tools\adb.exe" } else { $null }
if (Test-Path $adb) {
    Write-Host "`nConnected devices:"
    & $adb devices
    $devices = (& $adb devices | Select-String "device$" | Where-Object { $_ -notmatch "List of devices" })
    if (-not $devices) {
        Write-Warning "No Android device/emulator detected. Start an emulator before running tests."
    }
} else {
    Write-Warning "adb not found. Add Android SDK platform-tools to PATH."
}

Write-Host "`nCompiling MvolaAutomation. This does not install or launch an application."
mvn -DskipTests test-compile

Write-Host "`nDone. Open reports via:"
Write-Host "  reports\Open Reports (Windows).bat" -ForegroundColor Green
Write-Host "  or: python scripts\open_reports.py" -ForegroundColor Green
$latest = Get-ChildItem "reports" -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "index.html") } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($latest) {
    Write-Host "Latest run folder: $($latest.FullName)" -ForegroundColor Green
}
