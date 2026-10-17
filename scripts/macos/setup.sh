#!/usr/bin/env bash
# Downloads Android Platform-Tools for macOS into .tools/platform-tools.
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: setup.sh downloads the macOS build of Android Platform-Tools." >&2
    echo "       Use setup.ps1 on Windows." >&2
    exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_ROOT="$PROJECT_ROOT/.tools"
PLATFORM_TOOLS="$TOOLS_ROOT/platform-tools"
ADB="$PLATFORM_TOOLS/adb"
ARCHIVE="$TOOLS_ROOT/platform-tools-latest-darwin.zip"
DOWNLOAD_URL="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"

if [[ -x "$ADB" ]]; then
    echo "Android Platform-Tools already installed: $ADB"
    exit 0
fi

mkdir -p "$TOOLS_ROOT"
trap 'rm -f "$ARCHIVE"' EXIT

echo "Downloading Android Platform-Tools from Google..."
curl -L --fail --retry 5 --retry-all-errors --connect-timeout 30 -o "$ARCHIVE" "$DOWNLOAD_URL"

rm -rf "$PLATFORM_TOOLS"
/usr/bin/unzip -q -o "$ARCHIVE" -d "$TOOLS_ROOT"
if [[ ! -f "$ADB" ]]; then
    echo "ERROR: The downloaded archive did not contain an adb executable." >&2
    exit 1
fi
chmod +x "$ADB"
[[ -f "$PLATFORM_TOOLS/fastboot" ]] && chmod +x "$PLATFORM_TOOLS/fastboot"

echo "Installed Android Platform-Tools: $PLATFORM_TOOLS"
"$ADB" version
