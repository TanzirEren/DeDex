#!/usr/bin/env bash
# Runs INSIDE the GitHub Actions job, once the emulator is booted (invoked by
# reactivecircus/android-emulator-runner as its "script" step).
set -uo pipefail

APK_PATH="${APK_PATH:-apk/target.apk}"
OUT_DUMPS="mem_dumps"
MAX_REGION_BYTES=$((120 * 1024 * 1024))   # skip absurdly large regions (safety/time)
SETTLE_SECONDS="${SETTLE_SECONDS:-10}"

mkdir -p "$OUT_DUMPS"
adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true

echo "== Installing $APK_PATH"
adb install -r "$APK_PATH"

BUILD_TOOLS="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PKG="$("$BUILD_TOOLS/aapt" dump badging "$APK_PATH" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
if [ -z "$PKG" ]; then
  echo "Could not read package name with aapt, aborting."
  exit 1
fi
echo "== Package: $PKG"

echo "== Launching"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "== Waiting ${SETTLE_SECONDS}s for the app to initialise (loader runs here)"
sleep "$SETTLE_SECONDS"

PID="$(adb shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')"
if [ -z "$PID" ]; then
  echo "App is not running (pidof empty). It may have crashed or detected the emulator."
  adb logcat -d | tail -n 200
  exit 1
fi
echo "== PID: $PID"

echo "== Switching adb to root"
adb root
sleep 2
adb wait-for-device

if ! adb shell "su 0 id" 2>/dev/null | grep -q uid=0 && ! adb shell "id" | grep -q uid=0; then
  echo "Could not get root inside the emulator (adb root failed)."
  exit 1
fi

MAPS="$(adb shell run-as "$PKG" cat /proc/$PID/maps 2>/dev/null || adb shell cat /proc/$PID/maps)"
echo "$MAPS" > full_maps.txt
echo "== $(echo "$MAPS" | wc -l) memory map lines captured"

n=0
while IFS= read -r line; do
  range="$(echo "$line" | awk '{print $1}')"
  perms="$(echo "$line" | awk '{print $2}')"
  path="$(echo "$line" | awk '{print $6}')"
  start_hex="${range%-*}"
  end_hex="${range#*-}"
  start=$((16#$start_hex))
  end=$((16#$end_hex))
  size=$((end - start))

  # Only anonymous or heap-like regions; must be readable; skip tiny or huge ones.
  case "$perms" in r*) ;; *) continue ;; esac
  if [ -n "$path" ] && [[ "$path" != "[anon:"* ]] && [[ "$path" != "[heap]" ]]; then continue; fi
  if [ "$size" -lt 4096 ] || [ "$size" -gt "$MAX_REGION_BYTES" ]; then continue; fi

  blocks=$((size / 4096))
  skip=$((start / 4096))
  n=$((n + 1))
  out="$OUT_DUMPS/region_$(printf '%04d' "$n")_${start_hex}.bin"
  adb exec-out "dd if=/proc/$PID/mem bs=4096 skip=$skip count=$blocks 2>/dev/null" > "$out" 2>/dev/null
  if [ ! -s "$out" ]; then rm -f "$out"; n=$((n - 1)); fi
done <<< "$MAPS"

echo "== Captured $n region(s) into $OUT_DUMPS/"
du -sh "$OUT_DUMPS" || true
