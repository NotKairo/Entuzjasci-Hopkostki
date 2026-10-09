# Architecture

Flux is a single Gradle module (`:app`) organised by package. A multi-module split would add
build cost without adding isolation that packages and the pure/Android boundary below don't already give.

```
app.flux
├── FluxApp, AppGraph          application + hand-wired dependency container (no DI framework)
├── settings/                   FluxSettings (immutable model), SettingsRepository (DataStore)
├── engine/                     ★ pure: ActivityScheduler (state machine), EventDeduper, MediaEntryTracker
│                               FluxEngine (glue: sources → scheduler events), Simulator (test events)
├── overlay/                    ★ pure: OverlayGeometry, WindowPlanner
│                               OverlayWindowHost (WindowManager), FluxOverlayService (foreground service),
│                               DisplayInfoReader, OverlayLifecycleOwner, OutsideAwareLayout
├── ui/design/                  tokens: colours, shapes, type, spacing, sizes; theme; vector icon set
├── ui/anim/                    motion config + springs, interruptible frame animator, gestures, haptics
├── ui/overlay/                 the pill: FluxOverlay, MediaScene, ActivityScenes, BluetoothCard, components
├── ui/app/                     the settings app screens
├── media/                      MediaRepository (MediaSessionManager), MediaSessionPicker, models
├── artwork/                    ArtworkProcessor (decode/crop/blur/palette/cache), ColorMath, ImageBlur
├── notifications/              FluxNotificationListener, NotificationRepository, privacy rules
├── battery/                    BatteryRepository (event driven), BatteryEventDetector
├── bluetooth/                  BluetoothRepository (events + pairing), DeviceClassifier, HeadsetBatteryParser
├── timers/                     TimerRepository (DataStore + AlarmManager), TimerMath/StopwatchMath, notifier
└── core/                       Permissions, ClockSource, BootReceiver, DiagnosticsLog
```

★ = Android-free Kotlin. Everything that carries a rule worth getting right is in these files so it can
be unit-tested on the JVM (155 tests; see `app/src/test`).

## Data flow

```
 MediaSessionManager ─┐
 NotificationListener ─┤                                         ┌─ OverlayWindowHost ── top pill window
 BatteryManager ───────┼─► repositories ─► FluxEngine ─► state ─┤        (WindowManager, Compose)
 Bluetooth broadcasts ─┤     (hot flows)   (applies settings,    └─ bottom card window (Bluetooth)
 Timer/stopwatch store ┘                    maps to events)
                                              │  ▲
                                              ▼  │ SchedulerEvent: Post / Remove / Dismiss /
                                       ActivityScheduler        SetExpanded / Rerank / Tick
                                       (pure reducer)
```

* Each source is a repository exposing a `StateFlow`/`SharedFlow`. They hold no overlay logic.
* `FluxEngine` turns source changes into `SchedulerEvent`s, applying the user's settings (enabled
  events, timeouts, per-app rules, priority order). It also owns the real implementation of every action
  the overlay can ask for (media transport, timer buttons, notification actions, Bluetooth connect).
* `ActivityScheduler.reduce(state, event, now)` is a pure function. The engine sleeps until
  `nextWakeup(state)` and then dispatches `Tick`; nothing polls.
* `OverlayWindowHost` combines scheduler state, settings, display geometry and artwork into an
  `OverlayUi` and hands it to Compose.

### The scheduler

An `ActivityEntry` has a stable `key` (`media`, `timer`, `bt:<addr>`, `notif:<sbnKey>`…), a `kind`, a
`rank`, and a `revision` — the identity of a *genuinely new event*.

* **Selection.** The *holder* is the best-ranked persistent entry (media, timer, stopwatch, call, timer
  alarm). The *candidate* is the best-ranked transient entry (notification, charging, Bluetooth, battery
  alert). The candidate takes the front if there is no holder, the holder is interruptible (media), or the
  candidate's rank is strictly better. Otherwise it waits and is dropped when it goes stale.
* **Return.** A transient simply expires; the holder is the front again. Nothing is "restored" — it was
  never removed. That is how a notification interrupts music and music comes back, and why a running
  timer can never be lost by switching activities.
* **Dedupe / dismissal.** Updates with the same revision refresh the payload but never restart a timer
  or reopen a dismissed entry. A dismissed entry stays hidden until a higher revision arrives (new track,
  playback resumed, new notification, timer state change). `EventDeduper` additionally suppresses
  repeated deliveries (notification re-posts, duplicate Bluetooth broadcasts) before they reach the scheduler.
* **Expansion** is state in the scheduler (`expandedKey`), so an expanded music card that is interrupted
  returns expanded.
* **Priorities** are configurable (`PriorityConfig`); calls (1), timer alarms and critical battery (2) are
  fixed above the user-orderable groups.

## Overlay window and geometry

* Windows are `TYPE_APPLICATION_OVERLAY`, created from a **window context** (`createWindowContext`) so
  configuration changes reach them, with `FLAG_NOT_FOCUSABLE | NOT_TOUCH_MODAL`, `fitInsetsTypes = 0` and
  `layoutInDisplayCutoutMode = ALWAYS`.
* **Geometry is measured, not assumed.** `DisplayInfoReader` reads `WindowMetrics`, the status-bar inset and
  every `DisplayCutout` bounding rect. `OverlayGeometry.layout` puts the pill centred horizontally and
  *below* `max(status-bar bottom, cutout bottom)`, so it can never collide with the camera or the status
  indicators, whichever Galaxy model or hole position it is. The camera is never drawn, referenced as a
  shape, or wrapped.
* **Blending to the camera.** The expanded card's artwork backdrop extends up to the top edge; a smoothly
  eased scrim fades it to solid black through the status-bar band. Because Android layers the status bar
  *above* app overlays, its icons stay visible on top. The black region around the lens is simply part of
  the composition.
* **Touch safety.** A transparent window still swallows touches, so `WindowPlanner` sizes the window to
  what is visible: it **grows before** an expansion (springs are never clipped) and **shrinks after** the
  collapse settles. `FLAG_WATCH_OUTSIDE_TOUCH` lets an expanded card collapse on an outside tap without
  blocking the tap.
* **Idle cost.** With nothing to show, both windows are removed: no view, no composition, no frame
  callbacks. Diagnostics shows the running-animation counter.

## Animation system

* The pill's visible frame is four `Animatable`s (centre-x, top, width, height). `animateTo` on a new
  target continues from the current value *and velocity*, so a new event mid-transition retargets instead
  of restarting. Width leads height when growing; height leads width when shrinking.
* Corner radii are **derived from the live size** (`PillShapeMath.radii`), not animated separately.
* Scenes are laid out once at their final size and *revealed* by the clipped, morphing shape; media
  artwork glides between its compact and expanded slots on the same progress value. A morph therefore
  costs graphics-layer updates rather than recomposition (all animated values are read in layout/draw
  lambdas).
* Motion config = preset (Calm/Balanced/Lively) × intensity × reduced-motion (follow system's
  animator-scale 0, always, never). Reduced motion swaps springs for short fades and disables pulses.
* Timers/ticks run only while their composable is on screen and the thing is actually changing.

## Persistence and timekeeping

* Settings: Preferences DataStore. Timer/stopwatch: a second DataStore holding a compact text record.
* Elapsed/remaining time is **derived**, never counted: `TimerMath`/`StopwatchMath` take a `ClockSample`
  (`elapsedRealtime`, wall clock, `Settings.Global.BOOT_COUNT`). Same boot → the monotonic clock (immune
  to the user changing the time); after a reboot → the wall clock, then re-anchored to the new boot.
* Timer completion: `AlarmManager.setExactAndAllowWhileIdle` → `TimerAlarmReceiver`, which settles state
  idempotently, posts an alarm-category notification (sound/vibration even with the overlay off) and starts
  the overlay if enabled. `BootReceiver` re-arms after reboot/update.

## Dependencies (and why those versions)

| Library | Version | Why |
|---|---|---|
| Android Gradle Plugin | 9.4.0 | Built-in Kotlin; matches the Gradle/Kotlin/Compose set below |
| Gradle | 9.6.0 | Required by AGP 9.4 |
| Kotlin + Compose compiler plugin | 2.2.10 | Version proven with AGP 9.4 + Compose BOM 2026.02 |
| Compose BOM | 2026.02.01 | One consistent set of Compose artifacts |
| AndroidX core-ktx 1.19.0, activity-compose 1.13.0, lifecycle 2.11.0 | — | Current stable; they require **compileSdk 37** (targetSdk stays 36) |
| DataStore Preferences | 1.2.1 | Settings + timer persistence |
| Palette | 1.0.0 | Standard, tiny swatch extraction (final release) |
| kotlinx-coroutines | 1.10.2 | Flows / structured concurrency |
| Lottie Compose | 6.7.1 | One decorative ripple; current release, reduced-motion aware |
| JUnit | 4.13.2 | JVM unit tests |

Not used on purpose: Hilt/Koin (manual wiring is ~40 lines), Room (two small records), Navigation
(five tabs), Coil (no network images), `material-icons-extended` (original vector icons instead),
`INTERNET` permission.

minSdk 31 (Android 12): runtime Bluetooth permissions, window contexts, `RenderEffect`-era APIs; every
Galaxy S from the S21 ships with at least this. targetSdk 36.
