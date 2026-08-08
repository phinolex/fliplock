# FlipLock — lock your Android screen when you close a flip case that has no magnet

**Your wallet / flip / folio case does not turn the screen off when you close it, because it has
no magnet. FlipLock fixes that.** Close the flap → the phone locks. Open it → the screen comes
back on.

No magnet. No root. No ADB. No Shizuku. No server. No Internet.

**[⬇ Download the APK](../../releases/latest)** · [FAQ](docs/FAQ.md)

**Languages:** English · [Français](README.fr.md) · [简体中文](README.zh.md) · [한국어](README.ko.md) · [日本語](README.ja.md)

<p align="center">
  <img src="docs/flow.svg" alt="Close to lock, open to wake" width="100%">
</p>

## Is this your problem?

You bought a flip case that is not the manufacturer's own, and:

- closing the flap **does not turn the screen off** — the phone stays awake inside your bag
- opening the flap **does not wake** the phone
- Samsung's *Smart View Cover* / *Cover screen* setting does nothing, or the option is not even
  there
- your phone burns battery and gets warm in your pocket because the screen never went off
- the apps you found only offer *double tap to sleep*, or want root / ADB / Shizuku
- "pocket mode" apps lock your phone **every time the room gets dark**, which is worse

**Here is why.** Official flip covers hide a small **magnet**, and the phone has a **Hall sensor**
that detects it. That is the whole mechanism. Third-party cases almost never include the magnet,
so Android has literally no way to know the case is closed — there is nothing to detect. No
setting will fix it, because the hardware signal does not exist.

**What FlipLock does instead.** It watches the **ambient light sensor** on the front of the phone.
An opaque flap closing over it makes the light collapse. FlipLock looks for that *event* — a drop
that is fast, deep and sustained — rather than for a fixed lux value. That distinction is the
entire point: a dark room, a passing hand, dusk falling or walking indoors all end at the same
low lux, and none of them lock your phone.

- Package `com.fliplock.cover` · MIT licence · English / Français / 简体中文 / 한국어 / 日本語
- `minSdk` 28 (lowest API exposing `GLOBAL_ACTION_LOCK_SCREEN`) · `targetSdk` / `compileSdk` 36
- No Internet permission. No analytics. No server. Entirely on-device.
- Built and validated on a Samsung Galaxy **SM-S948B**, Android 16 / One UI, with a magnet-free
  third-party wallet case

**Also searched as:** flip cover not working, smart cover clone, folio case screen off, case
close lock screen, no hall sensor, magnet-free flip case, cover screen not detected, auto lock
when closing case, third-party flip cover Samsung · *coque à rabat qui n'éteint pas l'écran, étui
portefeuille sans aimant, la coque ne verrouille pas le téléphone, capteur à effet Hall* ·
*翻盖保护套 不能自动熄屏, 无磁铁 皮套 自动锁屏*

---

## What it looks like

| Home | Sensor diagnostics | Advanced settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" alt="FlipLock home screen showing live lux, case state and lock permission" width="240"> | <img src="docs/screenshots/diagnostics.png" alt="Diagnostics screen with live lux, rolling baseline and timestamped reading history" width="240"> | <img src="docs/screenshots/advanced.png" alt="Advanced settings with every detection threshold and the detection mode" width="240"> |
| Live lux, case state and the lock permission at a glance | Every reading timestamped, the rolling baseline, and the exact decision the engine reached | Every threshold is yours, with the reasoning next to each one |

And the screen that matters most — the engine **refusing** to lock:

<p align="center">
  <img src="docs/screenshots/rejected.png" alt="Diagnostics showing decision: drop too gradual (not a close), with 100 percent drop but no candidate" width="270">
</p>

The light really did reach **0.0 lux** and the drop really was **100 %**. FlipLock still said no:
`drop too gradual (not a close)`. The last bright reading was more than 900 ms earlier, so this
was a room going dark, not a flap coming down. That single decision is what stops your phone
locking itself all day long.

## Why not just `if (lux < 2) lock()`

Because your phone would lock every time you walk into a dark room.

<p align="center">
  <img src="docs/how-it-works.svg" alt="A threshold cannot tell a closing flap from a dimming room; an event can" width="100%">
</p>

`CoverDetectionEngine` requires **all** of these at once:

| Criterion | Purpose |
|---|---|
| `lux ≤ closeThreshold` | darkness reached |
| last bright reading < `fallWindowMs` (900 ms) ago | **speed** — a slowly dimming room is rejected |
| baseline ≥ `minBaselineLux` and ≥ 2 samples | **dark room** — light alone cannot decide, so it refuses |
| `baseline − lux ≥ minAbsoluteDropLux` (5 lux) | absolute drop |
| `dropPercent ≥ minDropPercent` (85 %) | relative drop |
| held for `confirmationMs` (300 ms) | **duration** — a passing hand or shadow is rejected |
| `cooldownMs` (1500 ms) after a lock | no repeat firing |
| `PowerManager.isInteractive` | never locks an already-off screen |

The **baseline** is a rolling median over 3 s, fed *only* by bright readings and frozen the
moment a candidate starts. Hysteresis (`release = threshold × 2.5 + 1`) cancels the candidate as
soon as light returns.

**Strategies** — `AUTO` (default), `LIGHT_ONLY`, `LIGHT_PLUS_PROXIMITY`. In `AUTO`, proximity is
used **only** when the room is too dark for light to decide, **and** only if that sensor really
emits events — probed at runtime, never assumed.

## Waking on open

The hard part: on most phones the ambient light sensor is `Non-wakeup`. Once the screen is off
the SoC suspends and that sensor **cannot wake the processor**. Listening to it continuously
would need a permanent wake lock, which is out of the question.

FlipLock uses two phases instead:

**Phase 1 — instant watch (60 s, adjustable 0–300 s).** A *partial* wake lock (screen stays off),
bounded by `acquire(timeout)`, plus direct light listening. This covers "I close it and reopen it
straight away, phone still in my hand". Measured cost: ≈ 50 mW, about **0.004 % of the battery
per lock** for 60 s.

**Phase 2 — hardware triggers only, ~0.001 mA.** Wake-up sensors (`tilt_detector`,
`wake_up_motion`, `pick_up_gesture`, `significant_motion`). These fire when you *pick the phone
up after it has been at rest* — which is why they cannot cover phase 1 on their own.

The wake threshold is adaptive: `max(closeThreshold × 1.5, 15, luxAtLockTime × 3 + 10)`. The last
term prevents a false wake in bright sunlight, where light leaking under the flap can exceed the
nominal threshold.

Waking **never unlocks**: the screen comes up at the lock screen.

## Compatibility

Nothing in the code is model-specific: FlipLock reads `getSensorList(TYPE_ALL)` and adapts to
what the device actually exposes. It runs on any Android 9+ phone with a front ambient light
sensor.

Built and validated on a **Samsung Galaxy (SM-S948B), Android 16 / One UI**, with a third-party
wallet case with an opaque flap and **no magnet**.

| Requirement | Why | If missing |
|---|---|---|
| `TYPE_LIGHT` front sensor | detect the light drop | the app cannot work |
| An **opaque** flap | the drop must be sharp | calibration reports "not good enough" |
| A **wake-up** motion sensor | wake on open | locking still works, waking does not |

The built-in **Diagnostics** screen tells you exactly which of these your device is missing, with
a *Copy diagnostic* button that produces a full technical report — model, Android version, every
sensor with its real probe result, measured values — and no personal data. That report is the
best possible way to open an issue.

## Install

Grab the APK from the **[Releases](../../releases)** page, then:

1. open the file on the phone → **Install**;
2. if Android asks to allow the source, allow it **for this app only**;
3. open FlipLock → **Enable the permission** → turn on the accessibility service;
4. **Sensor diagnostics**: check the lux value moves when you close the flap;
5. **Calibrate my case** → **Apply these settings**;
6. flip the FlipLock switch on.

**One UI / Auto Blocker.** If Samsung shows "Restricted setting" when you enable accessibility,
use the **"Open App info"** button on the home screen, then ⋮ → **Allow restricted settings**.
Do not disable Auto Blocker globally.

**App sleeping.** *Settings → Battery → Background usage limits → Never sleeping apps* → add
FlipLock, otherwise One UI may kill the service after a few days.

**"App not installed"?** If you were running a build signed with a different key — a debug build,
for instance — Android refuses to replace it. Uninstall FlipLock first, then install the APK.
Uninstalling erases your settings, so redo the calibration afterwards. Updating from one published
release to the next never has this problem: they all share the same signing key. Other causes are
covered in the [FAQ](docs/FAQ.md).

## Privacy

Permissions in the APK, verified with `aapt2 dump permissions`:

```
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.POST_NOTIFICATIONS
android.permission.WAKE_LOCK
com.fliplock.cover.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION   (auto, signature, app-private)
```

The first three serve only the optional persistent service; `WAKE_LOCK` (a *normal* permission,
no dialog) only the optional wake-on-open. The last one is added automatically by AndroidX for
`registerReceiver(..., RECEIVER_NOT_EXPORTED)`.

**Absent**: Internet, camera, microphone, contacts, SMS, phone, location, files/photos, accounts,
Bluetooth. No analytics, Firebase, Crashlytics, tracking, ads, remote API, telemetry, user
account or server. `allowBackup="false"` plus extraction rules excluding every domain.

The accessibility service is configured to the strict minimum: `accessibilityEventTypes` is **not
declared** (so it defaults to 0 and the service receives *no* events at all),
`canRetrieveWindowContent="false"`, `canPerformGestures="false"`. It calls exactly one platform
API: `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`.

## Architecture

```
com.fliplock.cover
├── detection/    CoverDetectionEngine — 100 % Kotlin/JVM, zero Android imports, unit-tested
├── sensors/      SensorRepository — listeners, flows, real sensor probing
├── service/      FlipLockAccessibilityService (watches + locks)
│                 WakeOnOpenController (wake on open) · FlipLockForegroundService (optional)
├── calibration/  CalibrationManager — statistics, computed thresholds, dark-room test
├── diagnostic/   DiagnosticRepository + DiagnosticReportBuilder
├── data/         DataStore-backed settings
├── log/          In-memory ring-buffer logger
└── ui/           Compose: Home, Diagnostics, Calibration, Advanced, Technical info
```

Physical detection is **separated from the lock action**: the engine emits
`DetectionEvent.LockRequested`, the service performs `performGlobalAction`. Neither the detection
nor the calibration layer produces any user-facing text — they return states and enums, and the
Compose layer resolves them to localised strings. That is what keeps the engine free of Android
dependencies and testable with synthetic values.

## Build

Requires JDK 17+ and the Android SDK (platform 36, build-tools 36.1.0).

```bash
./gradlew testDebugUnitTest    # detection engine tests
./gradlew assembleDebug        # development APK
./gradlew assembleRelease      # distributable APK (R8, ~2 MB)
```

Release builds must be signed with your own key:

```bash
keytool -genkeypair -v -keystore fliplock-release.jks \
        -keyalg RSA -keysize 4096 -validity 10000 -alias fliplock
cp keystore.properties.example keystore.properties   # fill in your passwords
./gradlew assembleRelease
```

`keystore.properties` and `*.jks` are gitignored — **never commit them**. Do not distribute
*debug* APKs: they carry `android:debuggable="true"` and are signed with Android's public debug
key.

## Tests

`app/src/test/.../CoverDetectionEngineTest.kt` — 13 tests covering: sharp drop, gradual dimming,
slow fade to full darkness, dark room, dark room rescued by proximity, 50 ms artefact, darkness
held past the confirmation window, cooldown, screen already off, FlipLock disabled, hand passing
over the sensor, light-only mode ignoring proximity, snapshot contents.

Because the engine takes every timestamp as a parameter and never reads the clock, all of this
runs on the JVM in milliseconds with no device and no `Robolectric`.

## Translating

Default locale is English (`values/strings.xml`), with `values-fr` and `values-zh` (Simplified).
209 strings; the 7 not overridden per locale are identical everywhere (`FlipLock`, `AUTO`,
`NEAR`, `FAR`…). To add a language, copy `values/strings.xml` to `values-<code>/` and translate.

Log lines and the diagnostic report stay in English on purpose: they are meant to be pasted into
issues.

## Contributing

The most useful contribution is a **diagnostic report**: Sensor diagnostics → *Probe ALL sensors*
→ *Copy diagnostic*, pasted into an issue. It carries the full sensor sheet and the measured
values, which is what makes it possible to work out why detection fails on a given device without
guessing at vendor sensor IDs.

Any change to the detection logic must come with a test in `CoverDetectionEngineTest`.

## Not on the Play Store

Google forbids using the accessibility API for anything other than accessibility. FlipLock uses
it to lock the screen, so Play policy would reject it — even though the use is honest, minimal
and fully transparent. Distribution is by APK here, or via F-Droid / Obtainium.

That constraint is also why the service is locked down as hard as it is, and why the code is kept
short enough to audit in one sitting.

## Licence

MIT — see [LICENSE](LICENSE).
