#!/usr/bin/env bash
set -euo pipefail
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ANDROID_HOME/ANDROID_SDK_ROOT 未设置" >&2
  exit 2
fi
python3 tools/validate_source.py
chmod +x gradlew
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
