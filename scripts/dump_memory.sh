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

echo "== Clearing logcat so the next lines are only from this app's launch"
adb logcat -c

echo "== Launching"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 -v > monkey_output.txt 2>&1
cat monkey_output.txt

echo "== Polling for the process for up to ${SETTLE_SECONDS}s (loader runs here)"
PID=""
for i in $(seq 1 "$SETTLE_SECONDS"); do
  PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [ -n "$PID" ]; then
    echo "  seen at ${i}s: pid $PID"
    break
  fi
  sleep 1
done

# Always save the full logcat since boot was cleared, regardless of outcome,
# so it can be inspected even when the pidof check above raced the process.
adb logcat -d > full_logcat.txt

if [ -z "$PID" ]; then
  echo "App is not running (pidof empty after ${SETTLE_SECONDS}s)."
  echo "== monkey output:"
  cat monkey_output.txt
  echo "== logcat filtered for this package / crash / native-crash markers:"
  grep -iE "$(echo "$PKG" | sed 's/\./\\./g')|FATAL EXCEPTION|AndroidRuntime|DEBUG *: *\*\*\*|Fatal signal|Process .* has died|libArkStub|ArkVmp" full_logcat.txt || echo "  (no matching lines -- see full_logcat.txt artifact)"
  exit 1
fi
echo "== PID: $PID"
sleep 2  # give it a moment past first sight in case it dies right after
if ! adb shell pidof "$PKG" >/dev/null 2>&1; then
  echo "Process $PID appeared then exited almost immediately (likely self-terminated)."
  adb logcat -d > full_logcat.txt
  grep -iE "$(echo "$PKG" | sed 's/\./\\./g')|FATAL EXCEPTION|AndroidRuntime|Fatal signal|Process .* has died|libArkStub|ArkVmp" full_logcat.txt || echo "  (no matching lines -- see full_logcat.txt artifact)"
  exit 1
fi

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
