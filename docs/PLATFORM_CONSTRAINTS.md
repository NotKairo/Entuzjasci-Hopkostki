# Platform constraints and honest limitations

What Android allows, what we do about it, and what the app does **not** claim.

## Overlay window
| Constraint | Consequence in Galaxy Pulse |
|---|---|
| `TYPE_APPLICATION_OVERLAY` is layered **below** the status bar / navigation bar / IME. | Status-bar icons can draw on top of the overlay's upper region. We never promise to draw above SystemUI. The interactive pill is placed below the status-bar band. |
| The status-bar window receives touches in its own area. | Compact pill is not placed inside the status bar. Expanded card keeps controls below the status bar; only artwork/gradient extends upward. |
| No drawing on the lock screen / AOD. | The overlay hides while the device is locked and returns after unlock. |
| Some apps / screens opt out of overlays (Settings, banking, secure windows). | Respected; we do not try to bypass. |
| `SYSTEM_ALERT_WINDOW` must be granted by the user in Settings. | Onboarding explains why, deep-links to the exact screen; nothing is granted silently. |
| A transparent overlay window still blocks touches inside its frame. | The window is sized to the visible content (grown before an expansion, shrunk after the collapse settles) and `FLAG_NOT_TOUCH_MODAL` is used so outside taps reach the app below. `FLAG_WATCH_OUTSIDE_TOUCH` lets an expanded card collapse on an outside tap (coordinates are not provided for foreign windows, which is all we need). |

## Camera cutout
- Geometry is computed at runtime from `WindowMetrics`, `DisplayCutout.boundingRects`, status-bar and cutout
  insets (nothing hard-coded per model; S10/S10e punch-holes are off-centre, S20–S25 centred).
- The camera is **never drawn** (no fake circle, border or wrapping capsule). Expanded cards extend the
  artwork *backdrop* up to the top edge and fade to black there, so the lens sits in a dark region.
- Because the status bar draws above overlays, status icons stay visible over that gradient. We do not try
  to hide them.

## Media
- Active sessions are only visible to an app holding **notification-listener access**. Without it, the
  media feature shows a clear "access needed" state.
- Metadata/artwork availability depends on the media app. If no artwork bitmap or `content://` URI is
  supplied, a neutral animated gradient is shown. Network image URIs are intentionally not fetched
  (core experience stays local, no `INTERNET` permission).
- Transport buttons are enabled only when the session advertises the matching `PlaybackState` action.
  Shuffle/repeat are shown only if the session publishes them as custom actions.
- Position is extrapolated from the session's own timestamp/speed; no progress is fabricated when the
  duration or position is unknown.

## Notifications
- Requires explicit notification-listener access. Android 13+ shows **"Restricted setting"** for
  sideloaded apps — the user must open App info → ⋮ → *Allow restricted settings* first (onboarding says so).
- We cannot suppress the system heads-up for the same notification; the Samsung/system presentation may
  appear alongside ours. A per-app switch lets the user disable ours.
- Redaction: private/secret notifications are never shown with content while the device is locked.
- We never cancel, snooze or modify other apps' notifications.

## Bluetooth
- Connection events come from ACL/profile broadcasts (needs `BLUETOOTH_CONNECT`).
- Pairing: **Connect** calls `BluetoothDevice.createBond()` and Android shows its own pairing dialog when
  needed. We do not replace OEM pairing UI (e.g. Samsung Galaxy Buds / Apple proprietary flows).
- Battery: public APIs do not expose per-bud/case levels. Where a headset reports battery via the
  Hands-Free vendor-specific broadcast we show **one** level; left/right/case rows appear only if a
  parsed report provides them. Otherwise the field is omitted. Not verified with real hardware yet.
- We do not scan in the background; discovery only runs while the user opens the Bluetooth screen.

## Battery / charging
- Event-driven (`ACTION_BATTERY_CHANGED`, power connected/disconnected); no polling.
- Charging speed, temperature and time-to-full appear only when Android reports plausible values
  (`BatteryManager.computeChargeTimeRemaining()` > 0; temperature present). No watt estimates.

## Timer / stopwatch
- Elapsed/remaining time is computed from persisted timestamps (monotonic clock + boot count, wall-clock
  fallback after reboot) — never from a running animation.
- Completion uses `AlarmManager.setExactAndAllowWhileIdle`. API 31–32 need the user to allow *Alarms &
  reminders*; API 33+ use the auto-granted `USE_EXACT_ALARM` (appropriate for timer apps). Without exact
  alarm permission the app falls back to an inexact alarm and says so in Diagnostics.

## Samsung One UI background restrictions
- A minimal foreground service (type `specialUse`) keeps the overlay alive; its notification is
  user-hideable. Set *Battery → Unrestricted* and keep the app out of "Sleeping/Deep sleeping apps"
  (guidance is in the app).
- After a force-stop the app cannot restart itself until the user opens it again (Android rule).

## Not claimed / not done
- No hardware-verified claims for Bluetooth pairing, earbud battery, or visual alignment on any specific
  Galaxy model until tested on a device — see the verification matrix in the README.
- No accessibility-service tricks, no hidden-API usage, no root.
