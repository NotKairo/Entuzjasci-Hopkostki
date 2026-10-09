# Flux

A Samsung One UI–inspired, Dynamic-Island-style **live activity overlay** for Android: a small floating
pill that becomes a music player, a timer, a charging card, a notification, and an animated headphone
connection card. Native Kotlin + Jetpack Compose. No website, no React Native, no mock-up.

> **Honest status.** Everything below that says *CI-verified* is built, unit-tested and linted by
> GitHub Actions on every push. **The app has not yet been run on a physical Galaxy phone** — the
> environment it was written in has no device — so every behaviour that depends on One UI, the real camera
> cutout, real media apps or real Bluetooth hardware is *implemented against the documented Android APIs
> but unverified*. See [`docs/TESTING.md`](docs/TESTING.md) for the exact matrix and a device test plan.

## Get the APK

The APK is built by GitHub Actions (`.github/workflows/android.yml`), not on a developer machine.

1. Open the repository's **Actions** tab → the latest *Android build* run on this branch.
2. Download the artifact **`flux-debug-apk`** and unzip it → `app-debug.apk`.
3. Install: `adb install -r app-debug.apk` (recommended), or copy it to the phone and open it.
   Enable *Install unknown apps* for your file manager if asked.

The APK is signed with a fixed project debug key (`app/debug.keystore`, password `android`), so every CI
build installs over the previous one without uninstalling. It is a **debug build, not a release key.**

> If Play Protect warns about the app, it is reacting to *Notification access* (needed for music and
> notifications) on a sideloaded APK. Installing with `adb` or from a file manager avoids the browser-install block.

## Build it yourself

Needs JDK 17+ and the Android SDK (platform 37, build-tools 36).

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug     # APK + 155 unit tests + lint
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Reproducible by construction: pinned Gradle wrapper (9.6.0), version catalog (`gradle/libs.versions.toml`),
fixed signing key. CI runs exactly the command above.

## First run

Open **Flux → Setup**. Nothing is granted silently; every item explains why and opens the real
Android screen:

| Permission | Why | Needed for |
|---|---|---|
| Display over other apps | draw the pill | everything (required) |
| Notification access | see the active media session, show notifications | music, notifications, calls |
| Notifications (Android 13+) | timer alarm + the "overlay is on" notification | timers, service |
| Bluetooth connect / scan | detect headphones, pair on **Connect** | Bluetooth card |
| Alarms & reminders (Android 12 only) | exact timer completion | timers |
| Battery → Unrestricted | stop One UI sleeping the overlay | reliability (recommended) |

There is **no INTERNET permission**. Android 13+ shows *Restricted setting* for sideloaded apps; the Setup
screen explains the *App info → ⋮ → Allow restricted settings* step.

Then turn on **Show the Flux overlay** and use the **Test** tab to fire sample events.

## What it does

| Area | Behaviour | Status |
|---|---|---|
| **Pill + motion** | Compact → expanded morph on interruptible springs; width leads when growing, height leads when shrinking; corner radii follow size; artwork glides between slots; press squash; reduced-motion; haptics | logic + build CI-verified; *feel needs tuning on a device* |
| **Camera cutout** | Pill is placed from runtime `WindowMetrics`/`DisplayCutout`/status-bar insets, below `max(status bar, cutout)`; **the camera is never drawn or wrapped**; expanded backdrop fades to black toward the top edge | geometry unit-tested; *alignment unverified on real S-series screens* |
| **Music** | Real `MediaSession` metadata, artwork, state, position, transport (prev/play/pause/next/seek), custom actions (shuffle/repeat), output device; paused → configurable auto-hide; nothing fabricated | session-picker/tracker/position maths unit-tested; *untested with real players* |
| **Artwork** | decode+crop+blur+palette off the main thread, cached; one decode feeds thumbnail and backdrop; restrained palette | blur/colour maths unit-tested |
| **Notifications** | Listener with per-app rules, lock-screen/private redaction, DND respected, dedupe, tap actions; returns to previous activity | privacy rules unit-tested; *untested live* |
| **Charging / battery** | Event-driven (no polling); started/disconnected/full/low/critical; ETA/temperature only if Android reports them | detector unit-tested |
| **Bluetooth** | Connection cards (bottom sheet-style), real pairing via `createBond()` + system dialog, battery only if the headset reports it, per-device mute + cooldown | classifier/parser unit-tested; *untested with hardware* |
| **Timer / stopwatch** | Persisted timestamps (monotonic + boot count + wall fallback), exact alarm, boot restore, laps, final-countdown pulse | maths + codec unit-tested |
| **Scheduler** | Pure state machine: priorities, interrupt-and-return, dismissal, dedupe, expiry | 25+ unit tests |
| **Settings app** | Onboarding, live preview, themes, motion presets, gestures, timeouts, priority order, per-app rules, Bluetooth, diagnostics + test events | builds; *UI not visually reviewed on a device* |

### Not supported / not claimed
* Drawing **above** the status bar, on the lock screen or AOD (Android doesn't allow it for app overlays).
* Left/right/case earbud battery (needs vendor protocols Android doesn't expose to apps).
* Replacing Samsung/Apple pairing UI or suppressing another app's heads-up for the same notification.
* Fast-charging labels, watts, temperature trends (no reliable public data).
* A guarantee that nothing is hidden by apps that block overlays (system Settings, banking…).

Full list and reasoning: [`docs/PLATFORM_CONSTRAINTS.md`](docs/PLATFORM_CONSTRAINTS.md).

## Docs

* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — packages, data flow, scheduler, window sizing, animation, dependency versions
* [`docs/PLATFORM_CONSTRAINTS.md`](docs/PLATFORM_CONSTRAINTS.md) — what Android allows and what the app does about it
* [`docs/REFERENCES.md`](docs/REFERENCES.md) — reference projects, their licenses, what was and wasn't reused
* [`docs/TESTING.md`](docs/TESTING.md) — verification matrix and manual device test plan
* [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)

## Credits and licensing

Independent implementation. No code or assets were taken from the studied projects. The media-first
island concept is shared with **DynamicIslandMusic by Bryan Guerra (@bguerraDev)** and other Android
"dynamic island" projects, credited here and in the app. Lottie (Apache-2.0) and AndroidX/Compose/Kotlin
(Apache-2.0) are used as libraries — see `THIRD_PARTY_NOTICES.md`.

This repository does not yet contain a LICENSE file for Flux itself: choose one before sharing it.
