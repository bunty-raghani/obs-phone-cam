#!/usr/bin/env bash
# OBS PhoneCam server launcher for macOS: ensures Python 3.10+, ADB, then starts the relay.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_ROOT"

find_python() {
    local name path
    for name in python3 python3.14 python3.13 python3.12 python3.11 python3.10 \
                /opt/homebrew/bin/python3 /usr/local/bin/python3; do
        if [[ "$name" == /* ]]; then
            path="$name"
        else
            path="$(command -v "$name" 2>/dev/null || true)"
        fi
        [[ -n "$path" && -x "$path" ]] || continue
        if "$path" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    return 1
}

find_adb() {
    local path
    path="$(command -v adb 2>/dev/null || true)"
    if [[ -n "$path" ]]; then
        printf '%s\n' "$path"
        return 0
    fi
    for path in "$PROJECT_ROOT/.tools/android-sdk/platform-tools/adb" "$PROJECT_ROOT/.tools/platform-tools/adb"; do
        if [[ -x "$path" ]]; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    return 1
}

PYTHON="$(find_python || true)"
if [[ -z "$PYTHON" ]]; then
    echo "ERROR: Python 3.10 or newer was not found in PATH." >&2
    echo "       Install it from https://www.python.org/downloads/ or with: brew install python" >&2
    exit 1
fi

if ! find_adb >/dev/null; then
    echo "ADB was not found; downloading Android Platform-Tools..."
    "$PROJECT_ROOT/scripts/macos/setup.sh"
fi

exec "$PYTHON" "$PROJECT_ROOT/relay/server.py" "$@"
