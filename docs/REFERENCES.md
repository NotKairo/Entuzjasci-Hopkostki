# Reference research, licenses and attribution

Flux was designed after reading the projects below (Phase 1). **No source code or assets from
any of them were copied.** Where a project's license is restrictive or absent, only publicly documented
Android behaviour was learned from it and the implementation here is independent.

| # | Project | License (as found in the repo) | Reuse decision |
|---|---|---|---|
| 1 | [Arnav-Dugad/dynamic-island-android](https://github.com/Arnav-Dugad/dynamic-island-android) | **No license file** anywhere in the repository. Without a license all rights are reserved. | **No code reused.** Studied for platform findings only (see below). |
| 2 | [bguerraDev/DynamicIslandMusic](https://github.com/bguerraDev/DynamicIslandMusic) | "MIT License with Attribution Requirement" (custom; requires visible credit and retaining the notice if code is used/redistributed). | **No code reused.** Credited anyway in the About screen and README as the inspiration for the media-first overlay concept. |
| 3 | [andrasulthan-alt/OmniLand](https://github.com/andrasulthan-alt/OmniLand) | **GPL-3.0** (fork of NothingLand, MIT, and following SmartIsland, GPL-3.0). | **No code reused** — copying would force GPL-3.0 onto this project. Independent implementation. |
| 4 | [kriwinter/bt-popup](https://github.com/kriwinter/bt-popup) | MIT | **No code reused.** Studied the event flow (ACL connect/disconnect broadcasts, device categorisation). Deliberately did *not* adopt its hidden-API reflection for Bluetooth battery. |
| 5 | [airbnb/lottie-android](https://github.com/airbnb/lottie-android) | Apache-2.0 | Used as a **library dependency** (`lottie-compose` 6.7.1) for decorative animation. License text in `THIRD_PARTY_NOTICES.md`. |
| 6 | [greensock/GSAP](https://github.com/greensock/GSAP) | GSAP "Standard License" (not OSI open source) | **Design reference only** — timeline/stagger/easing ideas. Not a dependency (web library). |
| 7 | [darkroomengineering/lenis](https://github.com/darkroomengineering/lenis) | MIT | **Design reference only** — inertia/damped-follow feel informed the drag-to-seek and overlay swipe damping. Not a dependency. |
| 8 | [DavidHDev/react-bits](https://github.com/DavidHDev/react-bits) | MIT + Commons Clause (as published by the project) | **Design reference only** — restrained gradient / micro-interaction ideas. Not a dependency. |

GSAP, Lenis and React Bits are web libraries and cannot run in a native overlay; they inform motion
design (sequencing, staggering, damped follow-through) which is re-implemented with Jetpack Compose
springs and `Animatable`.

## What each reference taught us (facts about Android, not code)

**dynamic-island-android (documentation, README "Known Android limitations")**
- Overlays of type `TYPE_APPLICATION_OVERLAY` sit **below** SystemUI's status-bar window. Status-bar
  icons draw over the overlay and touches directly beside the camera go to the status bar. → We keep the
  interactive compact pill *below* the status-bar band and never claim to draw above SystemUI.
- Overlays cannot draw on the lock screen / AOD. → The overlay is hidden while locked; documented.
- Apps like system Settings or banking apps may block overlays; this is respected.
- Charging "speed" has no public flag; time-to-full only when Android estimates it. → We only show what
  `BatteryManager` reliably exposes.
- Bluetooth battery is only shown when the device reports it to Android.
- Samsung background restrictions require a foreground service and "Unrestricted" battery mode.
- Observed practice: window context via `createWindowContext(TYPE_APPLICATION_OVERLAY)`,
  `layoutInDisplayCutoutMode = ALWAYS`, `fitInsetsTypes = 0`, `FLAG_WATCH_OUTSIDE_TOUCH` for
  tap-outside-to-collapse.
- Observed practice: Play Protect's enhanced fraud protection can block *browser-installed* APKs that
  declare a `NotificationListenerService`; sideloading via `adb install` or a file manager avoids this.

**DynamicIslandMusic**
- Compose-in-a-service needs hand-rolled `LifecycleOwner` / `SavedStateRegistryOwner` /
  `ViewModelStoreOwner` set on the `ComposeView` (`setViewTreeLifecycleOwner`, …).
- `MediaSessionManager.getActiveSessions(listenerComponent)` requires notification-listener access.
- Pre-blurred artwork is cheaper than live `RenderEffect` blur in an always-on window.

**OmniLand**
- Notification and media handling flow, per-app rules and multiple simultaneous activities — the
  *concept* of an activity registry with priorities. Our scheduler is a pure reducer (testable) rather
  than the plugin architecture used there.

**bt-popup**
- ACL connect/disconnect (+ A2DP/Headset profile) broadcasts are the practical way to detect headphone
  connections; device class (`BluetoothClass`) plus name heuristics categorise devices.
- Reads battery via hidden `BluetoothDevice.getBatteryLevel()` reflection. We do **not**: hidden APIs are
  unsupported, can be blocked at any release, and the brief asks for supported APIs. We use the public
  HFP vendor-specific-event broadcast where a headset reports battery, and otherwise omit the field.

## Attribution shown to users

The About section of the app and the README credit the projects above that influenced the concept
(DynamicIslandMusic's author is named explicitly, as its license requests for derived work, even though
no code was taken) and list third-party libraries with their licenses.
