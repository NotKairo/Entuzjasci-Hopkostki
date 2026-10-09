#!/usr/bin/env bash
# Runs the debug APK on the CI emulator: grants permissions the way a user would in Settings, walks the
# settings app, fires every test event through the real engine, and records the screen.
# Output: device-run/ (PNG screenshots, flux-run.mp4 screen recording, logcat).
set -u
PKG=app.flux
OUT=device-run
mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)

cmd() { adb shell am broadcast -n "$PKG/.debug.DebugCommandReceiver" --es cmd "$1" ${2:+--es name "$2"} > /dev/null; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || return 1
  local xy
  xy=$(python3 - "$1" /tmp/ui.xml <<'PY'
import re, sys, xml.etree.ElementTree as ET
want, path = sys.argv[1].lower(), sys.argv[2]
for n in ET.parse(path).iter('node'):
    label = (n.get('text') or n.get('content-desc') or '').strip().lower()
    if label == want:
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  [ -n "$xy" ] || return 1
  adb shell input tap $xy
}

adb wait-for-device
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell svc power stayon true
adb shell wm dismiss-keyguard
adb shell input keyevent 82

# A punch-hole cutout so the pill placement can be judged (emulation overlays differ between images).
adb shell cmd overlay list | grep -i cutout | tee "$OUT/cutout-overlays.txt"
for o in hole punch tall corner; do
  name=$(grep -io "com.android.internal.display.cutout.emulation.$o[a-z_]*" "$OUT/cutout-overlays.txt" | head -1)
  if [ -n "$name" ]; then adb shell cmd overlay enable --user 0 "$name" && echo "cutout: $name" && break; fi
done
sleep 3

adb install -r "$APK"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN || true
adb shell cmd notification allow_listener "$PKG/$PKG.notifications.FluxNotificationListener" || true
adb shell dumpsys deviceidle whitelist +"$PKG" > /dev/null || true

adb shell screenrecord --bit-rate 8000000 --time-limit 175 /sdcard/flux-run.mp4 &
REC=$!
sleep 1

# ── The settings app ──────────────────────────────────────────────────────────────────────────
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
sleep 4; shot app_01_home
adb shell input swipe 540 1800 540 700 400; sleep 2; shot app_02_home_scrolled
adb shell input swipe 540 1800 540 700 400; sleep 2; shot app_03_home_scrolled_more
i=4
for label in Bar Position Motion Look Activities Preview Settings Timers Test Diagnostics Setup; do
  if tap_text "$label"; then
    sleep 2; shot "app_$(printf %02d $i)_$(echo "$label" | tr 'A-Z ' 'a-z_')"; i=$((i + 1))
  fi
done

# ── The overlay, driven through the real engine ───────────────────────────────────────────────
cmd enable; sleep 4
adb shell input keyevent KEYCODE_HOME; sleep 2; shot overlay_00_idle_home

run_event() { # name, screenshot prefix, expand?
  cmd event "$1"; sleep 2.5; shot "overlay_$2_compact"
  if [ "${3:-yes}" = yes ]; then cmd expand; sleep 2; shot "overlay_$2_expanded"; cmd collapse; sleep 1.5; fi
}
run_event MusicPlaying 01_music
cmd event MusicNextTrack; sleep 1; cmd expand; sleep 2.5; shot overlay_02_music_next_expanded; cmd collapse; sleep 1.5
run_event NotificationMessage 03_notification
run_event IncomingCall 04_call
run_event ChargingStarted 05_charging
run_event LowBattery 06_low_battery
run_event BluetoothEarbuds 07_bluetooth
cmd event BluetoothPairingCard; sleep 1; shot overlay_08_bt_card_sliding; sleep 2; shot overlay_08_bt_card
adb shell input keyevent KEYCODE_BACK; sleep 2
run_event Timer15s 09_timer
sleep 16; shot overlay_10_timer_done
cmd event ClearAll; sleep 2
cmd event MusicPaused; sleep 2; cmd expand; sleep 2; shot overlay_11_music_paused_expanded

wait $REC 2> /dev/null
sleep 2
adb pull /sdcard/flux-run.mp4 "$OUT/flux-run.mp4" || true
adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
grep -E "FATAL|AndroidRuntime|FluxDebug|app.flux" "$OUT/logcat.txt" | tail -80 > "$OUT/logcat-flux.txt" || true
ls -la "$OUT"
exit 0
