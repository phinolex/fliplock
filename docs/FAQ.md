# FlipLock — FAQ

Short answers to the questions people actually ask. Everything here is grounded in what the app
measures on a real device, not in guesswork.

[← back to the README](../README.md)

---

### My flip case has no magnet. Can anything detect it closing?

Yes, but not the way the official cases do. Official flip covers hide a **magnet**, and the phone
has a **Hall sensor** that detects it. That is the entire mechanism, and it is why third-party
cases do nothing: there is no magnet, so there is no signal, so Android cannot know.

FlipLock uses the **front ambient light sensor** instead. An opaque flap closing over the front of
the phone makes the light collapse from a few hundred lux to near zero in well under a second.
That collapse is the signal.

### Will it lock my phone every time the room gets dark?

No, and this is the thing the app is actually built around. A fixed `lux < 2` rule would be
useless — it would fire when you walk into a dark room, when the sun sets, when you put the phone
face down.

FlipLock requires the drop to be **fast** (the last bright reading must be less than 900 ms old),
**deep** (at least 85 % and at least 5 lux below a rolling median baseline) and **sustained**
(300 ms). A room dimming over several seconds fails the speed test. A hand passing over the phone
fails the duration test. An already dark room fails the baseline test.

### Does it need root, ADB, Shizuku or a PC?

No. None of them. You install the APK and grant one accessibility permission from Android
Settings. That is all.

### Why does it need an accessibility service?

Because `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` is the only supported way for a normal
Android app to lock the screen without being a Device Admin. Device Admin is far more invasive.

The service is configured to the absolute minimum: `accessibilityEventTypes` is **not declared**,
so it defaults to 0 and the service receives **no accessibility events at all**.
`canRetrieveWindowContent` is `false`, so it cannot read window content even if it wanted to. It
calls exactly one platform API. The whole file is about 400 lines and you can read it in ten
minutes.

### Does it drain the battery?

Locking costs essentially nothing: no polling, no loop, just a `SensorEventListener` on a 0.09 mA
sensor, and the listener is unregistered the moment the screen goes off.

Waking on open costs a little, and the app tells you exactly how much. For 60 seconds after each
lock it holds a *partial* wake lock (screen stays off) so it can hear the Non-wakeup light sensor.
That draws roughly 50 mW, which works out to about **0.004 % of the battery per lock**. At fifty
locks a day that is 0.2 % a day. After that window only hardware wake-up sensors stay armed, at
about 0.001 mA. You can shorten the window to zero in Advanced settings.

### Does it work when FlipLock is not the app on screen?

Yes. The accessibility service does the watching, and Android keeps it alive independently of the
app UI. If your phone ever stops honouring that, there is an optional persistent service in
Advanced settings, off by default, that pins the process in the foreground state.

### Does it survive a reboot?

Yes, with no action from you. Android itself stores which accessibility services are enabled and
re-binds them after boot. Your settings live in DataStore on disk. The service reconnects after
your first unlock, which you have to do anyway.

### Will the screen come back on when I open the case?

On most phones, yes — but it is genuinely harder than locking, and here is why.

Once the screen is off the processor suspends, and on almost every phone the ambient light sensor
is declared `Non-wakeup`, meaning it **cannot wake the processor**. Listening to it continuously
would need a permanent wake lock, which would be a real battery problem.

So FlipLock uses hardware **wake-up** motion sensors (`tilt_detector`, `pick_up_gesture`,
`wake_up_motion`, `significant_motion`) at around 0.001 mA. They fire when you pick the phone up,
FlipLock then checks the light for at most 1.5 s, and turns the screen on if the light came back.
The 60-second instant watch described above covers the case where you never put the phone down.

Waking **never unlocks**. The screen comes up at the lock screen; your PIN, fingerprint or face is
still required.

### Does it work in a completely dark room?

No, and that is deliberate. In a pitch-black room, a closed flap and an open flap both read about
0 lux. There is no information to work with. FlipLock refuses to lock rather than guess, because
guessing means locking your phone at random every night.

If your proximity sensor reacts to the flap, hybrid mode covers this case. Run *Calibrate my case*
to find out whether yours does — many modern phones use a *virtual* proximity sensor that does not
react to a cover at all.

### My proximity sensor does not react to the flap. Is that a problem?

Only in the dark. Light-only detection works fine in any normally lit room. The Diagnostics screen
tells you plainly whether your proximity sensor produced any events, and the calibration tells you
whether it saw NEAR while the flap was closed.

### How fast does it lock?

About 300 ms after the flap covers the sensor, which is the confirmation window. You can lower it
in Advanced settings, at the cost of more false positives.

### Which phones does it work on?

Any Android 9 or newer phone with a front ambient light sensor. Nothing in the code is
model-specific — it reads `getSensorList(TYPE_ALL)` and adapts.

It was built and validated on a Samsung Galaxy **SM-S948B** running Android 16 / One UI, with a
magnet-free third-party wallet case.

### One UI keeps killing it, or shows "Restricted setting"

Two separate Samsung behaviours:

- **Restricted setting** when enabling accessibility for a sideloaded app: use the *Open App info*
  button on the FlipLock home screen, then the ⋮ menu, then **Allow restricted settings**. Do not
  disable Auto Blocker globally.
- **Deep sleep** after a few days: *Settings → Battery → Background usage limits → Never sleeping
  apps* → add FlipLock.

### Does it send anything anywhere?

No. There is no `INTERNET` permission in the manifest, so the app is physically incapable of
making a network request. No analytics, no Firebase, no crash reporting, no telemetry, no account,
no server. Cloud backup is disabled and the data extraction rules exclude every domain. Logs live
in a memory ring buffer and are cleared when you ask or when the process ends.

You can verify all of this yourself: `aapt2 dump permissions FlipLock-*.apk`.

### Why is it not on the Play Store?

Google forbids using the accessibility API for anything that is not accessibility. FlipLock uses
it to lock the screen, so Play policy would reject it — even though the use is honest, minimal and
fully disclosed. Install the APK from Releases, or use Obtainium to track updates.

### Android says "App not installed" / "Application non installée"

Four causes, in order of likelihood.

**1. You already have a build signed with a different key.** Android refuses to replace an app
with a version signed by a different certificate — that is a security guarantee, not a bug,
because otherwise anyone could push a malicious "update" over your apps. It shows up as
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

This happens if you were running a **debug build** (signed with Android's shared debug key) and
are now installing a release from this repo (signed with the project key). Fix: uninstall
FlipLock, then install the APK. **Uninstalling erases your settings and calibration**, so redo
*Calibrate my case* and re-enable the accessibility permission afterwards — about a minute.

Releases published here all use the same key, so updating from one release to the next never has
this problem.

**2. Samsung Auto Blocker.** *Settings → Security and privacy → Auto Blocker* — turn it off long
enough to install, then turn it back on.

**3. Play Protect.** It sometimes blocks unknown sideloaded apps. Play Store → your avatar → Play
Protect → *Scan apps with Play Protect*, install, then re-enable.

**4. A truncated download.** Compare the size with the one on the Releases page, and check the
signing certificate with `apksigner verify --print-certs`.

### Why is the APK only 2 MB?

Because release builds go through R8, which strips unused code and resources. The unminified debug
build is around 26 MB, almost all of it Jetpack Compose. 2 MB is the normal size for the shipped
app — nothing is missing.

### It does not detect my case. What now?

Open **Sensor diagnostics**, tap **Probe ALL sensors**, then **Copy diagnostic**, and paste that
into a [new issue](../../issues/new). The report contains your phone model, Android version, every
sensor with its real probe result, and the measured values — no personal data. That is exactly
what is needed to work out what is happening on your device.

The most common causes: the flap is not fully opaque, the room is too dark, or the calibration
picked up the flap mid-movement (redo it, closing the flap firmly before the countdown ends).
