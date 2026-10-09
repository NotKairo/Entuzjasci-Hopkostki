# Testing and verification

Two kinds of evidence exist for this project, and they are kept strictly apart:

* **Automated (CI)** — runs on every push in GitHub Actions: build, JVM unit tests, Android lint, and
  Robolectric screenshot rendering of the real overlay composables.
* **On-device** — needs a physical Galaxy phone, real media apps and real Bluetooth hardware.
  **None of this has been run yet.** The development environment had no device, so everything in the
  right-hand column below is "implemented, not yet verified".

## Verification matrix (the brief's checklist)

| # | Brief requirement | Automated | On a Galaxy device |
|---|---|---|---|
| 1 | Functioning Android Studio project | ✅ builds on CI every push (`assembleDebug`) | — |
| 2 | Unit tests | ✅ JVM tests for scheduler, geometry, window planner, timer/stopwatch maths + persistence, battery events, media position/session/revision rules, notification privacy, Bluetooth classification/battery parsing, colour/blur maths, shape maths | — |
| 3 | Lint / static checks | ✅ `lintDebug` is part of the CI command (it caught a real missing-permission guard, now fixed) | — |
| 4 | Compilation errors fixed | ✅ | — |
| 5 | Media playback & real transport controls | ◐ session selection, position extrapolation, revision/auto-hide logic tested; transport code compiled | ⬜ test with Spotify / YouTube Music / Samsung Music |
| 6 | Artwork transitions | ◐ blur + palette maths tested; crossfade/scale code compiled; layout rendered in screenshots | ⬜ judge smoothness/timing on screen |
| 7 | Bluetooth with real hardware | ◐ classifier + battery-report parser tested | ⬜ pair/connect real earbuds; check battery reporting |
| 8 | Charging & battery updates | ◐ event detector tested (edge triggers, low/critical re-arm) | ⬜ plug/unplug, full, low |
| 9 | Timers & stopwatch across process recreation | ◐ state maths + codec tested incl. reboot fallback and corruption | ⬜ kill app, reboot, let a timer finish with the screen off |
| 10 | Notification privacy & permission denial | ◐ privacy decision table tested exhaustively | ⬜ lock screen, private/secret notifications, access revoked |
| 11 | Cutout alignment & status-bar collisions | ◐ geometry tested for centred hole, off-centre hole, taller-than-status-bar cutout, narrow screens; screenshots render the result | ⬜ **must be checked on the real S-series screen** |
| 12 | Idle uses no animation resources | ◐ by construction (windows removed when idle; ticks only while visible); Diagnostics shows a live animation counter | ⬜ confirm counter reads 0 and `adb shell dumpsys gfxinfo` shows no frames |

Legend: ✅ verified by CI · ◐ partly verified (logic yes, platform interaction no) · ⬜ not verified.

## Running the automated checks

```bash
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew lintDebug                  # Android lint
./gradlew assembleDebug              # APK
./gradlew testDebugUnitTest -Pscreenshots   # renders PNGs to app/build/screenshots (Robolectric)
```

CI publishes the APK (`galaxy-pulse-debug-apk`), unit-test and lint reports as artifacts, and pushes the
rendered PNGs to the `ci-screenshots` branch.

## Device test plan

Install, open **Setup**, grant each permission, enable the overlay, then:

1. **Test tab → every button.** Confirm each event appears, returns to the previous activity, and that
   "Running animations" reads 0 a moment after motion ends.
2. **Camera alignment.** With the overlay showing, confirm the pill sits fully below the camera and the
   status icons, centred. Expand the music card and check the artwork fades to black at the top edge with no
   visible rectangle, and the camera area looks like part of the dark composition. Try *Size & position*
   sliders, then rotate the phone (the pill should hide in landscape if that option is on).
3. **Music.** Play something in Spotify / YouTube Music / Samsung Music. Check title, artist, artwork,
   progress, previous/play/pause/next, seeking, custom shuffle/repeat actions, the output-device name,
   auto-hide after pausing (default 8 s), and pause → another app plays (the overlay should follow).
4. **Interrupt and return.** While music plays, send yourself a message: the notification appears briefly
   and music returns (expanded stays expanded). Start a timer; confirm notifications and charging do *not*
   take over a running timer, but an alarm does.
5. **Notifications & privacy.** Lock the phone and send a message: the content must not be shown while
   locked. Test per-app rules (block an app, hide its text). Revoke notification access: music and
   notifications should stop and Diagnostics should say so.
6. **Bluetooth.** Connect already-paired earbuds → card + pill. Disconnect/reconnect within the cooldown →
   no repeat. Pair new earbuds from **Settings → Headphones & Bluetooth → Scan → Connect**: Android's own
   pairing dialog must appear when needed. Note which battery fields (if any) your headset reports.
7. **Timers.** Start a 1-minute timer, swipe the app away, lock the phone: it must ring on time. Repeat
   across a reboot. Stopwatch: start, lap, kill the app, reopen.
8. **Gestures.** Tap to expand/collapse; swipe sideways to dismiss (it must not reappear for the same
   track); tap outside while expanded (the tap must still reach the app beneath).
9. **Samsung background behaviour.** Leave it idle for an hour with Battery = Unrestricted and again with
   Optimised; note whether One UI kills the service.
10. **Reduced motion.** Turn on *Remove animations* in Android settings: the overlay should fade instead of
    spring and the equalizer should be static.

Please report any deviation with the device model, One UI version, and the Diagnostics event log.

## Screenshots

Rendered PNGs are on the `ci-screenshots` branch. They are produced with reduced motion (settled frames),
a synthetic 411×470 dp stage, and a fake cutout that exists only as an input to the geometry maths —
nothing resembling a camera is drawn. They show layout, not animation.
