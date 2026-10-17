#!/usr/bin/env bash
# First-time setup for macOS: install/update OBSPhoneCam.apk, then start the relay.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "$PROJECT_ROOT/scripts/macos/run_server.sh" --install
