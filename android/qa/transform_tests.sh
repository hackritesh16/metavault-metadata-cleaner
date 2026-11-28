#!/usr/bin/env bash
set -euo pipefail
adb install -r apk/app-debug.apk
adb install -r test-apk/*.apk
result=$(adb shell am instrument -w -r com.metavault.cleaner.test/androidx.test.runner.AndroidJUnitRunner)
echo "$result"
if ! grep -Eq 'OK \([0-9]+ tests?\)' <<<"$result"; then
    echo "::error::$(echo "$result" | tail -c 3000 | tr '\n' ' ')"
    exit 1
fi
python3 android/qa/transform_save_smoke.py
