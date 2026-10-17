#!/bin/bash
# Finder double-click launcher (macOS): installs/updates OBSPhoneCam.apk and starts the relay.
cd "$(dirname "$0")" || exit 1
exec ./install_and_run.sh "$@"
