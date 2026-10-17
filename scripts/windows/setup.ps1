$ErrorActionPreference = 'Stop'

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptRoot)
$ToolsRoot = Join-Path $ProjectRoot '.tools'
$PlatformTools = Join-Path $ToolsRoot 'platform-tools'
$Adb = Join-Path $PlatformTools 'adb.exe'
$Archive = Join-Path $ToolsRoot 'platform-tools-latest-windows.zip'
$DownloadUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'

if (Test-Path -LiteralPath $Adb) {
    Write-Host "Android Platform-Tools already installed: $Adb"
    exit 0
}

New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null

try {
    Write-Host 'Downloading Android Platform-Tools from Google...'
    curl.exe -L --fail --retry 5 --retry-all-errors --connect-timeout 30 -o $Archive $DownloadUrl
    if ($LASTEXITCODE -ne 0) { throw "Download failed with exit code $LASTEXITCODE" }

    if (Test-Path -LiteralPath $PlatformTools) {
        Remove-Item -LiteralPath $PlatformTools -Recurse -Force
    }
    Expand-Archive -LiteralPath $Archive -DestinationPath $ToolsRoot -Force
    if (-not (Test-Path -LiteralPath $Adb)) { throw 'The downloaded archive did not contain adb.exe.' }

    Write-Host "Installed Android Platform-Tools: $PlatformTools"
    & $Adb version
} finally {
    if (Test-Path -LiteralPath $Archive) {
        Remove-Item -LiteralPath $Archive -Force
    }
}
