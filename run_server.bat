@echo off
cd /d "%~dp0"

where python >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python 3.10 or newer was not found in PATH.
    pause
    exit /b 1
)

where adb >nul 2>&1
if not errorlevel 1 goto run
if exist ".tools\platform-tools\adb.exe" goto run
if exist ".tools\android-sdk\platform-tools\adb.exe" goto run

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
if errorlevel 1 (
    echo ERROR: Android Platform-Tools setup failed.
    pause
    exit /b 1
)

:run
python server.py %*
set "PHONECAM_EXIT=%ERRORLEVEL%"
pause
exit /b %PHONECAM_EXIT%
