#!/bin/bash
# Finder double-click launcher (macOS): opens Terminal and starts the OBS PhoneCam relay.
cd "$(dirname "$0")" || exit 1
exec ./run_server.sh "$@"
