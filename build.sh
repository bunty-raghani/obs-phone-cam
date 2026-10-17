#!/usr/bin/env bash
# Local Android build bootstrap for macOS: JDK 17, Gradle 8.9, Android SDK, release APK.
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: build.sh is the macOS build bootstrap. Use build.ps1 on Windows." >&2
    exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_ROOT="$PROJECT_ROOT/.tools"
JDK_ROOT="$TOOLS_ROOT/jdk"
SDK_ROOT="$TOOLS_ROOT/android-sdk"
GRADLE_ROOT="$TOOLS_ROOT/gradle-8.9"
DOWNLOAD_ROOT="$TOOLS_ROOT/downloads"

# Android command-line tools 15859902, the same revision build.ps1 pins for Windows.
CMDLINE_TOOLS_BUILD=15859902
case "$(uname -m)" in
    arm64|aarch64)
        JDK_ARCH='aarch64'
        CMDLINE_ARCH='arm64'
        CMDLINE_SHA1='c4e0dbc53f1a4ce04fc2b11d4e2c4675001a95af'
        ;;
    x86_64)
        JDK_ARCH='x64'
        CMDLINE_ARCH='x86_64'
        CMDLINE_SHA1='1a79e61677b99cb0ce622792713cbea7ff2dc5de'
        ;;
    *)
        echo "ERROR: unsupported Mac architecture: $(uname -m)" >&2
        exit 1
        ;;
esac
JDK_HOME="$JDK_ROOT/Contents/Home"

mkdir -p "$TOOLS_ROOT" "$DOWNLOAD_ROOT" "$SDK_ROOT"

download() {
    curl -L --fail --retry 10 --retry-all-errors --connect-timeout 30 --continue-at - -o "$2" "$1"
}

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
    echo 'Downloading local JDK 17...'
    JDK_TAR="$DOWNLOAD_ROOT/jdk17.tar.gz"
    download "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$JDK_ARCH/jdk/hotspot/normal/eclipse" "$JDK_TAR"
    JDK_EXTRACT="$TOOLS_ROOT/jdk-extract"
    rm -rf "$JDK_EXTRACT"
    mkdir -p "$JDK_EXTRACT"
    tar -xzf "$JDK_TAR" -C "$JDK_EXTRACT"
    EXTRACTED_JDK="$(find "$JDK_EXTRACT" -maxdepth 1 -mindepth 1 -type d | head -n 1)"
    if [[ -z "$EXTRACTED_JDK" ]]; then
        echo 'ERROR: The downloaded JDK archive has an unexpected layout.' >&2
        exit 1
    fi
    rm -rf "$JDK_ROOT"
    mv "$EXTRACTED_JDK" "$JDK_ROOT"
    rm -rf "$JDK_EXTRACT"
fi

if [[ ! -x "$GRADLE_ROOT/bin/gradle" ]]; then
    echo 'Downloading Gradle 8.9...'
    GRADLE_ZIP="$DOWNLOAD_ROOT/gradle-8.9-bin.zip"
    download 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' "$GRADLE_ZIP"
    rm -rf "$GRADLE_ROOT"
    /usr/bin/unzip -q -o "$GRADLE_ZIP" -d "$TOOLS_ROOT"
    chmod +x "$GRADLE_ROOT/bin/gradle"
fi

CMDLINE_TOOLS="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$CMDLINE_TOOLS" ]]; then
    echo 'Downloading Android command-line tools...'
    CMDLINE_ZIP="$DOWNLOAD_ROOT/android-commandline-tools.zip"
    download "https://dl.google.com/android/repository/commandlinetools-mac_${CMDLINE_ARCH}-${CMDLINE_TOOLS_BUILD}_latest.zip" "$CMDLINE_ZIP"
    CMDLINE_HASH="$(shasum -a 1 "$CMDLINE_ZIP" | awk '{print $1}')"
    if [[ "$CMDLINE_HASH" != "$CMDLINE_SHA1" ]]; then
        echo "ERROR: Android command-line tools checksum mismatch: $CMDLINE_HASH" >&2
        exit 1
    fi
    SDK_EXTRACT="$TOOLS_ROOT/sdk-extract"
    rm -rf "$SDK_EXTRACT"
    /usr/bin/unzip -q -o "$CMDLINE_ZIP" -d "$SDK_EXTRACT"
    rm -rf "$SDK_ROOT/cmdline-tools/latest"
    mkdir -p "$SDK_ROOT/cmdline-tools/latest"
    mv "$SDK_EXTRACT/cmdline-tools/"* "$SDK_ROOT/cmdline-tools/latest/"
    rm -rf "$SDK_EXTRACT"
    chmod +x "$CMDLINE_TOOLS"
fi

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$JDK_HOME/bin:$SDK_ROOT/platform-tools:$PATH"

echo 'Accepting Android SDK licenses...'
printf 'y\n%.0s' {1..20} | "$CMDLINE_TOOLS" --sdk_root="$SDK_ROOT" --licenses >/dev/null

echo 'Installing Android SDK packages...'
"$CMDLINE_TOOLS" --sdk_root="$SDK_ROOT" platform-tools 'platforms;android-35' 'build-tools;35.0.0'

# Recent SDK packages ship as path-prefixed archives and this cmdline-tools
# revision extracts them with the package folder nested inside itself
# (platforms/android-35/android-35/...), which makes Gradle fail with
# "Failed to find target with hash string 'android-35'". Flatten those dirs.
flatten_package() {
    local destination="$1"
    local name
    name="$(basename "$destination")"
    if [[ -d "$destination/$name" && ! -f "$destination/source.properties" ]]; then
        echo "Fixing nested extraction in $destination"
        mv "$destination/$name" "$destination.flattening"
        mv -f "$destination.flattening"/* "$destination/"
        rm -rf "$destination.flattening"
    fi
}
for package_directory in "$SDK_ROOT/platforms"/* "$SDK_ROOT/build-tools"/* "$SDK_ROOT/platform-tools"; do
    [[ -d "$package_directory" ]] || continue
    flatten_package "$package_directory"
done

echo 'Building release APK...'
"$GRADLE_ROOT/bin/gradle" --no-daemon --console=plain -p "$PROJECT_ROOT" assembleRelease

BUILT_APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
FINAL_APK="$PROJECT_ROOT/OBSPhoneCam.apk"
cp -f "$BUILT_APK" "$FINAL_APK"
echo "Built: $FINAL_APK"
