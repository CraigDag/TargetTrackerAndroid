#!/usr/bin/env bash
# Installs the test APK on an emulator, builds the sample session with no taps,
# and screenshots the main screens. Output in shots/ (uploaded by the workflow).
set -u
APK=android/build/outputs/apk/release/android-release.apk
mkdir -p shots
adb install -r "$APK"
adb logcat -c
open() { adb shell "am start -n app.rangetarget/.MainActivity $1" > /dev/null; }
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "shots/$1.png"; }

open ""
shot 00_first_launch 8
open "--es name Sam --es route home"
shot 01_home_empty
open "--es demo auto"
for i in $(seq 1 180); do
  adb logcat -d -s RangeTarget:I | grep -q "demo ready" && break
  adb logcat -d | grep -q "FATAL EXCEPTION" && break
  sleep 5
done
adb logcat -d -s RangeTarget:I > shots/timings.txt
n=2
for r in "home" "cardcheck/sample-check" "circle/sample-check/1?from=fix" "results/sample-check/1?from=summary" "results/sample-check/2?from=summary" \
         "summary/sample-check" "stats" "markup" "plan" "kit" "settings" "about"; do
  open "--es route '$r'"
  shot "$(printf %02d $n)_$(echo "$r" | cut -d'?' -f1 | tr '/' '_')" 5
  n=$((n+1))
done
adb logcat -d > shots/logcat.txt
cat shots/timings.txt
if grep -q "FATAL EXCEPTION" shots/logcat.txt; then grep -A30 "FATAL EXCEPTION" shots/logcat.txt; exit 1; fi
grep -q "demo ready" shots/timings.txt || { echo "sample session did not finish"; exit 1; }
