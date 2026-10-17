$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolsRoot = Join-Path $ProjectRoot '.tools'
$JdkRoot = Join-Path $ToolsRoot 'jdk'
$SdkRoot = Join-Path $ToolsRoot 'android-sdk'
$GradleRoot = Join-Path $ToolsRoot 'gradle-8.9'
$DownloadRoot = Join-Path $ToolsRoot 'downloads'

New-Item -ItemType Directory -Force -Path $ToolsRoot, $DownloadRoot, $SdkRoot | Out-Null

function Get-Download([string] $Url, [string] $Destination) {
    curl.exe -L --fail --retry 10 --retry-all-errors --connect-timeout 30 --continue-at - -o $Destination $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
}

if (-not (Test-Path -LiteralPath (Join-Path $JdkRoot 'bin\java.exe'))) {
    Write-Host 'Downloading local JDK 17...'
    $JdkZip = Join-Path $DownloadRoot 'jdk17.zip'
    Get-Download 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $JdkZip
    $JdkTemp = Join-Path $ToolsRoot 'jdk-extract'
    if (Test-Path -LiteralPath $JdkTemp) { Remove-Item -LiteralPath $JdkTemp -Recurse -Force }
    Expand-Archive -LiteralPath $JdkZip -DestinationPath $JdkTemp -Force
    $ExtractedJdk = Get-ChildItem -LiteralPath $JdkTemp -Directory | Select-Object -First 1
    Move-Item -LiteralPath $ExtractedJdk.FullName -Destination $JdkRoot
    Remove-Item -LiteralPath $JdkTemp -Recurse -Force
}

if (-not (Test-Path -LiteralPath (Join-Path $GradleRoot 'bin\gradle.bat'))) {
    Write-Host 'Downloading Gradle 8.9...'
    $GradleZip = Join-Path $DownloadRoot 'gradle-8.9-bin.zip'
    Get-Download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $GradleZip
    Expand-Archive -LiteralPath $GradleZip -DestinationPath $ToolsRoot -Force
}

$CommandLineTools = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $CommandLineTools)) {
    Write-Host 'Downloading Android command-line tools...'
    $ToolsZip = Join-Path $DownloadRoot 'android-commandline-tools.zip'
    Get-Download 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $ToolsZip
    $ToolsHash = (Get-FileHash -LiteralPath $ToolsZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($ToolsHash -ne '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a') {
        throw "Android command-line tools checksum mismatch: $ToolsHash"
    }
    $SdkTemp = Join-Path $ToolsRoot 'sdk-extract'
    if (Test-Path -LiteralPath $SdkTemp) { Remove-Item -LiteralPath $SdkTemp -Recurse -Force }
    Expand-Archive -LiteralPath $ToolsZip -DestinationPath $SdkTemp -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot 'cmdline-tools\latest') | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $SdkTemp 'cmdline-tools') | Move-Item -Destination (Join-Path $SdkRoot 'cmdline-tools\latest')
    Remove-Item -LiteralPath $SdkTemp -Recurse -Force
}

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path = "$(Join-Path $JdkRoot 'bin');$(Join-Path $SdkRoot 'platform-tools');$env:Path"

Write-Host 'Accepting Android SDK licenses...'
$LicenseInput = (1..20 | ForEach-Object { 'y' }) -join "`n"
$LicenseInput | & $CommandLineTools --sdk_root=$SdkRoot --licenses | Out-Null

Write-Host 'Installing Android SDK packages...'
& $CommandLineTools --sdk_root=$SdkRoot 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'

Write-Host 'Building release APK...'
& (Join-Path $GradleRoot 'bin\gradle.bat') --no-daemon --console=plain -p $ProjectRoot assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }

$BuiltApk = Join-Path $ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
$FinalApk = Join-Path $ProjectRoot 'OBSPhoneCam.apk'
Copy-Item -LiteralPath $BuiltApk -Destination $FinalApk -Force
Write-Host "Built: $FinalApk"
