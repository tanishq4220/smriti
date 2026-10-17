#!/usr/bin/env bash
# One command to put Smriti on a connected phone and prove it works.
#
#   ./scripts/verify-on-device.sh    # on-device models only; needs a .litertlm on the phone
#
# Everything it does is idempotent. Safe to re-run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/env.sh"

PKG="com.smriti.app"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*"; }
ok()   { printf '\033[32mok\033[0m   %s\n' "$*"; }

step "device"
state=$(adb get-state 2>/dev/null || echo none)
[[ "$state" == "device" ]] || { fail "no authorised device (state: $state). Plug in and accept the debugging prompt."; exit 1; }
adb shell getprop ro.product.model | tr -d '\r'
adb shell cat /proc/meminfo | grep -E "MemTotal|MemAvailable" | tr -d '\r'

step "build"
( cd "$ROOT" && ./gradlew assembleDebug --no-daemon ) \
  > "$ROOT/build.log" 2>&1 || { fail "build failed, see build.log"; tail -20 "$ROOT/build.log"; exit 1; }
ok "built $(basename "$APK") ($(du -h "$APK" | cut -f1))"

step "permission audit"
PERMS=$("$ANDROID_HOME/build-tools/36.0.0/aapt2" dump permissions "$APK" 2>/dev/null | grep uses-permission || true)
echo "$PERMS" | sed 's/^/    /'
if grep -q "android.permission.INTERNET" <<< "$PERMS"; then
  fail "the APK has INTERNET. The whole product claim depends on it not having it."
  exit 1
fi
ok "no INTERNET permission — the offline claim holds"

step "install"
adb install -r "$APK" 2>&1 | tail -1
# MIUI blocks the FIRST install; updates are fine. If this fails, push and tap once:
#   adb push "$APK" /sdcard/Download/smriti.apk   then open it in Files.

step "permissions"
adb shell pm grant "$PKG" android.permission.CAMERA 2>/dev/null && ok "CAMERA" || echo "    (grant blocked — allow it in-app)"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null && ok "RECORD_AUDIO" || echo "    (grant blocked — allow it in-app)"

step "model on device"
if adb shell ls /data/local/tmp/llm/*.litertlm >/dev/null 2>&1; then
  adb shell ls -lh /data/local/tmp/llm/ | tr -d '\r' | sed 's/^/    /'
else
  fail "no .litertlm model in /data/local/tmp/llm — import one in the app, or push one:"
  echo "    adb shell mkdir -p /data/local/tmp/llm"
  echo "    adb push <model>.litertlm /data/local/tmp/llm/"
fi

step "seed the demo corpus"
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/com.smriti.app.MainActivity" --ez smriti_seed true >/dev/null 2>&1
sleep 12
adb logcat -d -s SmritiSeed:V SmritiBackfill:V 2>/dev/null | sed 's/^.*: /    /' | tail -6

step "model self-test"
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/com.smriti.app.MainActivity" --ez smriti_selftest true >/dev/null 2>&1
for i in $(seq 1 40); do
  sleep 5
  adb logcat -d -s SmritiBench:V 2>/dev/null | grep -q "SELF TEST END" && break
done
adb logcat -d -s SmritiBench:V SmritiEngine:V 2>/dev/null | sed 's/^.*: /    /' | tail -25

step "crash check"
CRASH=$(adb logcat -d -b crash 2>/dev/null | grep -c "$PKG" || true)
[[ "$CRASH" -eq 0 ]] && ok "no crashes" || fail "$CRASH crash lines — adb logcat -b crash"

step "screenshot"
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/com.smriti.app.MainActivity" >/dev/null 2>&1
sleep 4
adb exec-out screencap -p > "$ROOT/notes/device.png" 2>/dev/null
ok "notes/device.png"

printf '\n\033[1mManual checks left to a human:\033[0m\n'
echo "  1. Tap the shutter — a record appears, then press BACK and you should STAY on the camera."
echo "  2. Hold the shutter, speak, release — recording must end on release, not 15 s later."
echo "  3. Open Ask, say or type \"what did I commit to this week?\" — answer plus an evidence photo."
echo "  4. Turn on airplane mode first and confirm all of the above still works."
