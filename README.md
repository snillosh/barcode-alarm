# Barcode Alarm

Minimal offline Kotlin Android alarm for Pixel 7 Pro / GrapheneOS, with one-shot or selected-day repeats and five-minute snooze. Native widgets, AlarmManager.setAlarmClock, offline ZXing, local preferences. No accounts, analytics, Internet permission or Google Play Services. Original requirements: [BRIEF.md](BRIEF.md).

## Build and install

Open this folder in Android Studio with Android SDK 36 and JDK 17 or newer supported by Gradle 9.6. The build uses Android Gradle Plugin 9.2 with built-in Kotlin. Internet is needed on the development machine to download build dependencies; the installed application is offline.

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If required, set `sdk.dir=/path/to/Android/Sdk` in an untracked `local.properties`. The APK is debug signed; keep the same signing key for updates. No release signing configuration is included.

## Download an APK from GitHub

Open [Android build and test](https://github.com/snillosh/barcode-alarm/actions/workflows/android.yml),
select a successful run, and download **barcode-alarm-debug-…** under **Artifacts** (GitHub sign-in
required). Extract the downloaded archive and install `app-debug.apk` on your phone. The run
summary also links to the APK artifact. APK downloads are retained for 30 days; **Run workflow**
can create a fresh build at any time.

The pipeline runs on pushes to `main`, pull requests targeting `main`, and manual runs. It runs
all JVM unit tests, Android lint, builds the debug APK, and checks the scanner runtime classes
inside that APK. Test/lint reports are retained for 14 days, including after failed checks.
Only a build that passes every check publishes an APK.

CI uses its own debug signing key, cached across builds. It differs from a locally built APK's
key, so moving from a local install to CI may require uninstalling the old app first, which
clears saved alarms and barcodes. Later CI builds normally update each other; if GitHub evicts
the signing-key cache, the key is regenerated. These are development builds, not release-signed
packages. Phone/GrapheneOS behavior still needs the physical-device checks below.

## First use

1. Open Settings and diagnostics. Grant exact alarm access, notifications and full-screen intent access. Grant camera permission when scanning. Open app battery settings and select unrestricted background use manually.
2. Add named codes in Barcodes. Add an alarm, choose a time, select its library barcode and optionally choose a system alarm sound. Select any Mon–Sun repeat days, or leave them all off for a one-shot alarm. Save, then enable its switch.
3. Test Alarm schedules the first saved alarm ten seconds ahead, replacing any existing occurrence (including a pending snooze). Snooze temporarily silences it; final dismissal still requires the barcode or full maths challenge.

Each registered delivery fires once. One-shot alarms disable after successful dismissal. Repeating alarms stay enabled and register the next selected day after successful dismissal. Both retain their definition and barcode. Disabling an alarm cancels its pending delivery, including snoozes. A DST gap moves to the next valid time and an overlap uses the earlier offset once. Future one-shot timestamps are preserved across reboot/zone changes; regular repeats follow local calendar time and selected days. Missed one-shots are disabled and logged; missed repeats advance to the next future selected day.
Ringing consumes the pending occurrence before starting a media-playback foreground service. Duplicate/stale receiver deliveries are ignored by matching the persisted occurrence timestamp. Simultaneous alarms are handled in saved order; each requires its own dismissal. Settings cannot edit or disable a ringing alarm. Maths progress uses elapsed realtime and synchronous local persistence; leaving the screen restores full app playback volume. Snooze retains maths progress and its elapsed timer, as backgrounding already does. A reboot resets maths progress for ringing or snoozed alarms because the monotonic clock has restarted.

## Physical device verification required

The user reported earlier releases working on GrapheneOS. **The new snooze/repeat release has not been tested on the physical phone.** Verify these cases on the Pixel:

- Run the ten-second test unlocked, locked, then idle (also test an alarm several hours ahead).
- With an alarm scheduled, background the app and run `adb shell am kill uk.co.barcodealarm`; verify delivery. This is different from force-stop, which blocks delivery until the app is launched again.
- Reboot before a future alarm, unlock once, verify the original timestamp is restored. Reboot across a missed alarm and inspect diagnostics.
- Scan an incorrect code, correct code, EAN-13 / UPC-A equivalents, UPC-E, Code 128 and QR. Deny camera access and verify maths remains available.
- Answer 15 questions before five minutes: it must keep ringing. Wait five minutes with fewer than 15: it must keep ringing. Rotate, background, return and switch to scanning: progress must survive. Verify app playback is quieter only while maths is visible, with system alarm volume unchanged.
- Disable, edit, delete and re-enable alarms; verify Android's next-alarm indicator. Schedule overlapping alarms and dismiss each.
- Select weekdays, weekend-only and mixed repeat days. Dismiss by barcode and maths and verify the next selected day stays enabled. Leave every day off to verify one-shot behaviour.
- Snooze for five minutes, background/terminate the app normally, and verify it rings again. Check that Snooze is temporary and final dismissal still requires barcode/maths. Disable a pending snooze and confirm it does not ring.
- Reboot during a pending snooze and before a future repeat; unlock once, verify restoration. After an interrupted repeating occurrence, verify a future selected day is scheduled. Check local-time repeats after changing time zone.
- Deny/revoke exact alarm, notification and full-screen access, then inspect Settings and scheduling errors. Android can remove scheduled alarms when exact access is revoked; reopen and re-enable affected alarms after granting access.
- Select a system sound and then make it unavailable; verify fallback and inspect the local diagnostic log. Check alarm volume, DND policy and vibration on the actual device.
- Kill the ringing process normally: verify foreground service recreation, audio and persisted dismissal progress. Verify low-memory and battery behavior.

## Limits and implementation notes

No pre-unlock boot support: storage is credential encrypted. No app can guarantee delivery when powered off, force-stopped or blocked by the OS. Full-screen presentation depends on Android/GrapheneOS permissions, lock state and notification policy; the ongoing notification opens the ringing screen. Normal navigation and device controls remain available.

A reboot does not launch a playback foreground service (Android restricts media playback services from boot receivers). An unresolved one-shot ringing alarm resumes when you open the app. An interrupted repeating occurrence advances to the next selected day after reboot so it cannot block the series indefinitely. Future enabled alarms are restored after unlock. Notifications denied or a muted alarm stream can prevent useful alarm presentation/audio; review Settings before use. No DND bypass or battery-management bypass is requested.

The bounded local diagnostic log records scheduling, firing, dismissal, missed occurrences and caught playback errors. It contains no barcode values. Android's own storage/OS failures can still prevent delivery or persistence.

Unit tests cover scheduling, DST gap handling, dismissal state, barcode comparison, question generation and both maths thresholds. Receiver/PendingIntent de-duplication, playback, permission flows and process recreation require Android/device verification; no instrumentation suite is included yet.

## Sources

- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Foreground service launch requirements](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Android 15 foreground service restrictions](https://developer.android.com/about/versions/15/behavior-changes-15)
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache 2.0)

## Validation in this workspace

Debug APK assembly, all **116 JVM unit tests**, and Android lint completed successfully using Android Studio's bundled JDK. Lint has warnings (including SDK updates, deliberate synchronous persistence, and the service-lifetime alarm wake lock); no lint errors. API 36 is the current project baseline; test newer OS compatibility before raising the target SDK. The system `/usr/bin/java` here is a runtime without `javac`; select Android Studio's bundled JDK when building.

```sh
export JAVA_HOME="$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
./gradlew testDebugUnitTest assembleDebug lintDebug
```

## Scanner crash fix (0.1.1)

Version 0.1 crashed on opening the scanner with `NoClassDefFoundError: androidx.core.content.ContextCompat`. ZXing Embedded's published POM does not bring in AndroidX Core, although its camera permission code references Core classes. Version 0.1.1 includes Core explicitly. Install the new APK over the old one to preserve saved alarms (same package and debug signing key).

After building, run this packaging regression check in addition to the JVM tests:

```sh
python3 scripts/check_scanner_apk.py
```

It inspects actual DEX class definitions for the scanner, decoder, ContextCompat and ActivityCompat. It fails on the original APK. This checks the missing-class failure; opening the scanner, granting/denying camera permission and registering/dismissing with a real barcode still require a device test.

## Native UI refresh (0.2.0)

The interface now uses a light neutral palette, deep green controls, rounded alarm cards, large time typography, and consistent spacing across alarms, the editor, settings, ringing and maths. Layouts account for system bars and remain scrollable. All controls are native Android views; no UI dependencies were added. Scheduling, persistence, playback, barcode comparison and challenge rules are unchanged.

Barcode registration and dismissal now share a portrait-only ZXing capture activity. Version code 3 can be installed over the previous debug build while retaining saved alarms.

Validation: debug build, 16 JVM tests, lint (warnings only), and scanner APK packaging check pass. Home and editor layouts were inspected in the Pixel emulator, and the running scanner was confirmed in portrait (`ROTATION_0`). A user reported successful physical-device testing of the previous version; this visual refresh still needs a quick check on that device, particularly portrait scanning, large font sizes, and the ringing/maths layouts.

## Dark mode (0.3.0)

Open **Settings → Appearance → Dark mode** to turn the dark palette on or off. The setting applies immediately, stays on Settings after switching, and is saved independently of alarms. Light mode remains the default. Home, editor, ringing, maths, cards, buttons, native controls, and system-bar icons use the selected appearance. The scanner keeps its dark camera interface; Android's external sound picker and permission/settings screens follow system styling.

Validation: debug APK assembly, all 16 JVM tests, Android lint (warnings only), and scanner packaging checks pass. The dark editor was visually checked in the emulator and the saved appearance preference was verified. Alarm scheduling and dismissal rules are unchanged.

## Named barcode library (0.4.0)

Open **Barcodes** from the home screen to scan and name an object or QR code (for example, “Home bathroom shampoo”). Rename entries or delete entries that are not used by any saved alarm. Names must be nonblank, unique ignoring case, and at most 120 characters. Rescanning an existing code reuses its entry, including equivalent UPC-A / EAN-13 representations. Codes themselves cannot be replaced in place; add a new entry instead.

The alarm editor has a **Dismissal barcode** dropdown listing library names. A selection is required before saving. **Manage barcodes** opens the library while preserving the alarm draft and chosen time. Adding a code there selects it for the draft; return with **Back to alarm**. The home and ringing screens show the selected barcode's name. Renaming never changes the code that dismisses an alarm. Deleting an entry used by any saved alarm (including disabled alarms) is blocked; change those selections or delete those alarms first.

On first use, existing alarm codes are imported as “Saved barcode 1”, etc., ready to rename. Matching codes share an entry. Migration saves the library and alarm links in one preferences transaction; it preserves each alarm's original code, configured time, scheduled timestamp, enabled/ringing state, and maths progress. Existing scheduled PendingIntents remain valid. Alarm playback and barcode comparison still use each alarm's saved dismissal value.

The 22 new library unit tests cover naming validation, duplicates, UPC equivalence, selection requirements, per-alarm selection, immutable code values during renaming, deletion protection, migration deduplication and idempotence, and preservation of scheduled/ringing state. The 16 existing alarm tests remain in place.

On the physical phone, verify upgrading retains your alarms, rename an imported entry, add a new scanned code, select it in the dropdown, and test correct/incorrect barcode dismissal. Check that an unused entry can be deleted but a referenced entry cannot, including while its alarm is disabled. Verify rotation and a trip through Manage barcodes retain your editor time and selection.

Validation for 0.4.0: all **38 unit tests** pass; debug APK assembly, lint (warnings only), and scanner packaging checks pass. In a separate temporary Android emulator, old-format preferences were migrated, equivalent UPC/EAN codes deduplicated, original alarm times and dismissal values retained, a library entry renamed, deletion of a referenced entry blocked, a new alarm saved through the named dropdown, and persistence verified after process restart. Physical-camera registration and dismissal with the new library remain device checks.

## Back navigation (0.5.0)

Android Back and the visible return buttons now share page navigation rules. Settings returns home. The barcode library returns to its caller (home or the alarm editor), preserving the editor draft. Leaving an unchanged editor returns home; leaving a changed editor asks whether to discard changes or keep editing. The Cancel button uses the same protection. The page, barcode-library caller and editor fields survive activity recreation. Back from the maths challenge returns to the ringing screen without changing progress and restores full alarm playback volume. Back from the home page keeps Android's default behaviour.

Navigation uses Android's platform `OnBackInvokedDispatcher` on Android 13+ and a legacy Back override on Android 9–12, without adding a navigation framework. The legacy override has a targeted lint suppression because the modern dispatcher is handled separately. Dialogs, the keyboard, and ZXing's camera activity retain their own Back handling. See [Android's Back guidance](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

Thirteen new navigation unit tests cover caller-aware returns, draft-change detection, discard decisions, page restoration and maths navigation; 51 JVM tests pass in total. Scheduling, persistence formats, barcode matching and challenge completion rules are unchanged. On the physical device, check both gesture Back and button Back, keyboard/dialog dismissal, returning from scanning to barcode management, and leaving/re-entering maths while an alarm is sounding.

Validation for 0.5.0: debug APK assembly, all **51 unit tests**, lint (warnings only), and scanner packaging checks pass. A separate temporary Android emulator verified Settings → home with both system Back and an edge-swipe gesture; library → caller; an unchanged editor leaving without a prompt; changed barcode selections surviving nested navigation and offering keep/discard; page restoration after rotation; keyboard → dialog → page dismissal order; and home Back returning to the launcher. An actual answered maths question retained its count and monotonic start time after Back and re-entry, with the alarm foreground service still running.

## Readability refactor (0.5.1)

Start with [DEVELOPING.md](DEVELOPING.md) for a file map, C# / Kotlin syntax comparisons,
Android lifecycle notes, and walkthroughs of creation, scheduling, ringing and dismissal.
The code now uses descriptive model/member names, conventional formatting, named UI/action
methods, and comments explaining Android-specific decisions. Receiver classes and the
alarm/question models have their own files. No application dependencies were added.
The saved-data keys, Android component identities, UI copy, scheduling rules and dismissal
requirements are retained. This release is intended to change readability only.

Validation for 0.5.1: all **51 unit tests**, debug APK assembly, Android lint (warnings only),
and scanner packaging checks pass. The isolated emulator checks covered old-data migration,
UPC/EAN deduplication, library renaming/deletion protection, dropdown saving, persistence after
restart, draft protection, rotation, system/gesture Back, and maths progress after returning
from the challenge. A real `setAlarmClock()` occurrence fired while the app was backgrounded,
started the foreground playback service and consumed the saved one-shot occurrence.
These are emulator checks; the readability refactor has not been tested on the physical phone.


## Snooze and repeat days (0.6.0)

The ringing screen has **Snooze · 5 minutes**, available immediately. The current occurrence
is registered five minutes ahead using `setAlarmClock()` before playback stops. A failed
registration keeps it ringing and displays the error. Snooze does not change the configured
time, barcode or repeat-day selection. It may cross midnight onto an unselected day because
it retries the current occurrence. Final dismissal still requires the barcode or maths.
Existing maths progress is retained; its five-minute timer continues during snooze. After
reboot, monotonic maths progress resets while a still-future snooze delivery is restored.
Snoozes use a saved wall-clock alarm timestamp; manually changing the device date/time can
affect RTC delivery. If multiple alarms are ringing, each must be snoozed/dismissed separately.

The editor offers all **Mon–Sun** checkboxes. No selection means one-shot; any selection
means repeat on those days at the configured local time. The home screen shows the selected
days and the next pending time. Repeat-day changes participate in unsaved-draft protection
and survive editor/library navigation and activity recreation. Editing and saving an enabled
snoozed alarm replaces its snooze with the newly configured regular schedule.

Repeats use separate exact `setAlarmClock()` occurrences, not Android's inexact repeating
API. Only one pending delivery exists per alarm: while it is ringing there is no second
registration; successful dismissal registers the next future selected day. A pending snooze
occupies that same slot. Disabled series never re-enable themselves after restart. Regular
repeats are recalculated for local time-zone/clock changes; future snoozes retain their
explicit timestamp. Missed repeats advance rather than replaying missed alarms immediately.
Interrupted repeating playback after reboot also advances to a future selected day, without
starting a prohibited boot-time media service. See [Android scheduling guidance](https://developer.android.com/develop/background-work/services/alarms).

Existing alarms upgrade as one-shot alarms; new JSON fields have compatible defaults. There
are no new runtime dependencies or permissions. The JSON compatibility tests use a test-only
`org.json` implementation because Android's JVM test stubs cannot perform real JSON parsing.

Validation for 0.6.0: all **94 unit tests** pass, including 43 new repeat/snooze, restoration
and JSON compatibility tests. Debug APK assembly, lint (warnings only) and scanner packaging
checks pass. The isolated emulator selected Mon/Sat/Sun, protected unsaved repeat edits and
retained them through library navigation. A real five-minute snooze silenced playback,
preserved maths progress, survived termination of the app process without force-stop,
and restarted the foreground service on time. Authorised maths dismissal registered the
next selected day at 07:00; disabling it cleared the pending occurrence. With exact-alarm
access denied, Snooze rolled back to the ringing state and kept the playback service running.
The new behaviour still needs the physical-device checks above.

## Gradual volume increase (0.7.0)

Open **Settings → Alarm playback → Gradual volume increase**. It is off by default so existing
alarms keep their immediate full-level playback. When enabled, sound starts at **20% app-player
gain** and rises linearly to 100% over **30 seconds, 1 minute, 2 minutes or 5 minutes** (default
1 minute). Gain is not perceived loudness; try your chosen tone and system alarm level on the
phone. This changes only the app's player, never the system alarm-volume setting or vibration.

The initial gain is set before audio starts, avoiding a full-volume first frame. The ramp's
monotonic start time is saved per ringing occurrence, so rotation, backgrounding and service/
process recreation do not restart it. The ramp starts once a sound is ready, including fallback
tones. Snooze and each new occurrence start a fresh ramp; a reboot resets its monotonic clock.
While maths is visible, playback is 20% of the current ramp level. Returning to the ringing
screen restores that level, and the ramp continues to full gain normally.

Playback options are stored separately from alarm definitions. Older saved alarms default to
an uninitialised playback clock, so upgrading retains all alarms, codes, repeat days and snoozes.
No application dependency or permission was added.

Validation for 0.7.0: all **116 unit tests**, APK assembly, lint (warnings only), and scanner
packaging checks pass. The isolated emulator saved the enabled/30-second preference, retained
the ramp clock after process/service recreation, and left the system alarm-volume preference
unchanged. Android's mixer showed the player increasing from approximately -12 dB early in
the ramp to 0 dB at full gain, then -14 dB during maths (20% gain). Snooze clears the playback
clock for its next ring. Perceived loudness with your chosen tone still needs a phone check.

The restart test also exposed repeated `BOOT_COMPLETED` delivery without an actual device
reboot. Restoration now uses Android's boot counter to reset monotonic clocks once per real
boot, preserving ramp/maths progress and active repeating alarms on ordinary process restart.
If the platform does not expose a boot counter, the existing conservative boot-reset behaviour
is retained. No new permission is requested.

## App icon (0.7.1)

The launcher now uses an original vector alarm-clock mark with a barcode face: cream and
mint on the app's deep green background. Adaptive foreground/background layers support
launcher masks, and Android 13+ resources include a monochrome layer for launchers with
wallpaper-themed icons. A tighter white silhouette is used for the ringing notification.
There are no runtime image libraries or new permissions.

Editable SVG reference and rendered previews live in [artwork](artwork/). The Android vector
resources are in `res/drawable`, with adaptive definitions in `res/mipmap-anydpi-v26` and
`res/mipmap-anydpi-v33`; the manifest sets both icon and roundIcon. SVG edits are not compiled
automatically: keep the corresponding Android vector paths in sync. The mark stays within
the adaptive safe region; see [Android adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Validation for 0.7.1: APK assembly, all **116 unit tests**, lint (warnings only), and scanner
packaging checks pass. The icon was rendered under circular/rounded-square and sample themed
masks, then installed and visually checked in the Android emulator's launcher. This update
changes icon resources and the notification glyph; alarm behaviour is unchanged.


## Optional/two-stage dismissal and verification (0.8.0)

The first barcode dropdown now offers **None — normal alarm**. Such alarms have an immediate
Dismiss button and do not open the camera. Existing saved barcode selections are retained.
Choosing a first barcode reveals an optional second barcode; distinct codes are required.
Two-stage intervals are 0–30 minutes (default 1). Verification is independently optional
(default off), with a delay of 1–60 minutes (default 5) and response allowance of 1–30
minutes (default 2). All choices survive editor recreation.

First scan silences playback without dismissing. A durable eligibility event posts the
second-code notification, and an independent deadline five minutes later restarts playback
at full app gain. The second code completes the occurrence; maths always completes the
whole occurrence. Maths stays silent in the intentionally silenced stage. An escalation
still uses the existing maths gain reduction. Snooze retains the correct dismissal stage.

After full dismissal, enabled verification schedules both notification and response deadline
immediately. **I'm Awake** in the notification or confirmation screen completes it. Swiping
the notification away does not confirm. An unanswered deadline starts full-gain ringing with
the original snapshot's dismissal requirements. That follow-up never starts another
verification. Notifications and PendingIntents use stable occurrence-specific identities.

Definitions and active occurrences are now separate preferences records. Repeat definitions
register their next delivery when they fire, allowing verification from a previous occurrence
to coexist with the next one. Editing a definition never changes an active snapshot. Disabling
or deleting a definition cancels ALL its active/pending occurrences, follow-up notifications
and deadlines, including current ringing. Main displays active-workflow entry buttons rather
than forcing you back into a ringing screen, allowing those cancellation actions.

Restore runs on app open and existing boot/system broadcasts. Future/past deadline recovery
uses saved wall timestamps; the within-boot eligibility countdown uses elapsed realtime.
After first unlock at reboot, elapsed deadlines are rebased and pending exact callbacks
restored. No Direct Boot support or boot-time media service is added. Exact-alarm permission
is required for intermediate deadlines; scheduling failures are reported and transitions
roll back instead of pretending a silent workflow was scheduled. Notification permission and
launcher/lock-screen policy still govern presentation, never the independent timeout.

### Physical Pixel 7 Pro / GrapheneOS checklist

- Create a normal alarm, deny camera permission, and use Dismiss. Test snooze separately.
- Verify an upgraded single-code alarm still rejects ordinary dismissal and incorrect codes.
- Use two named codes and interval 0, then 1 minute. Check that first scan silences both
  sound/vibration and displays the second code name. Scan too early or re-scan the first code.
- Close/lock the app during the wait; verify the ready notification opens the right occurrence.
  Ignore it and verify full-volume escalation after the five-minute completion window.
- Complete maths before and after the first scan; verify progress survives navigation and
  silent stages stay silent. Escalation maths should retain its reduced playback gain.
- Enable verification for normal, one-code and two-code alarms. Confirm directly from the
  notification while locked where Android permits, and by tapping through to confirmation.
- Swipe away/deny/delay notifications: an unanswered response deadline must still ring.
  Verify both barcode stages again on a failed two-stage verification, and no second cycle.
- Snooze a second-stage escalation and a verification escalation; check their resumed stages.
- Schedule overlapping repeating alarms/verification, edit definitions, disable/delete them,
  and confirm no unrelated occurrence is confirmed and no orphan notification remains.
- Kill the process normally and reboot/unlock during each pending state. Verify recovered
  deadlines, permissions diagnostics, and that duplicate callbacks do not duplicate playback.

Deterministic workflow tests use supplied wall/elapsed clock values; no real five-minute
sleeps are required. The new feature still requires the physical-device checks above.

Validation for 0.8.0: all **156 JVM unit tests**, debug APK assembly, lint (no errors), and
scanner packaging checks pass. Isolated Android-emulator smoke tests verified ordinary
dismissal, verification confirmation from both the screen and notification action, silent
second-stage waiting and full-gain timeout escalation, escalation with notification permission
denied, and deletion stopping an active follow-up. Fixture deadlines were shortened for
these tests. Physical camera, audible volume, lock-screen policy and overnight GrapheneOS
delivery still require the device checklist above.

## Labels and alarm-list conveniences (0.9.0)

Alarms now have an optional **Alarm label** (trimmed, up to 50 characters). A blank label
appears as **Alarm**. Existing JSON without a label loads normally. Labels appear on alarm
cards, the ringing/maths and verification screens, and ringing/follow-up notifications.
Active occurrences retain their original snapshot's label when a definition is renamed.

**Duplicate** saves a separate disabled alarm with the same configuration and shared barcode
and sound references. It never schedules anything or copies an active occurrence, snooze,
maths progress, verification progress or playback timer. IDs are durably reserved and are
not reused after deletion. Snooze duration and gradual volume remain app-wide settings;
duplicates use those same settings just like other alarms.

The **Next alarm** card uses enabled definitions' saved future scheduled timestamps, showing
local date/time, label and time remaining. It excludes expired/ringing definitions and never
counts occurrence follow-up deadlines as ordinary alarms. It refreshes after list/editor
changes, foregrounding, system clock/zone changes, and once per minute while home is visible
(or at the next alarm's deadline, if sooner). It does not schedule alarms or keep a service
alive. DST/calendar scheduling remains in the existing alarm scheduler.

Successfully enabling an alarm, including resaving an enabled alarm from the editor, shows a
brief **Alarm set for … from now** toast using the actual registered time. Failed registration
shows the existing diagnostic; disabling, snoozing and follow-up events show no success toast.
Rapid messages replace the previous toast rather than forming a queue.

Manual checks:

- Upgrade with existing alarms; check the default label and unchanged dismissal settings.
- Set/rename/clear a label; rotate or switch theme during editing and confirm the draft survives.
- Duplicate a repeating two-code alarm with verification/custom sound; verify the new alarm is
  disabled and both retain their settings. Delete one and verify the other still works.
- Enable, disable, edit and delete alarms; check Next alarm updates and no stale fired time is
  displayed. Check local Today/Tomorrow dates, 12/24-hour format and clock/time-zone changes.
- Enable a repeat and compare its toast to Next alarm. Deny exact-alarm access and confirm an
  error appears without a success message. Rapidly toggle to check messages do not stack.
- Check labelled ringing and verification notifications, including after editing the definition
  during an active occurrence. Confirm ordinary dismissal and follow-ups still work.

Validation for 0.9.0: all **178 JVM unit tests**, debug assembly, lint (warnings only) and
scanner packaging checks pass. Isolated emulator checks covered trimmed label editing,
independent disabled duplicates, enablement/countdown refresh, automatic countdown expiry,
permission-denied scheduling, and snapshot labels on ringing/maths/verification screens and
notifications. Physical GrapheneOS presentation checks remain in the checklist above.

## Reusable barcode timers (0.10.0)

Use the **Timers** tab beside **Alarms**. Save a label, a duration from 1 second to 7 days,
optional first/second barcodes and scan interval, an alarm sound, and optional **Remind me
every N minutes** chimes. Labels, saved barcode references, sound selection, maths fallback,
vibration and the global gradual-volume settings follow the existing alarm conventions.
There is no timer wake-up verification or countdown pause/resume.

Each preset supports one outstanding run; different presets can run simultaneously. Start
creates a new globally unique occurrence and a configuration snapshot. Editing a preset
changes its next run, including when a previous run is active. The list shows ready, running,
ringing, waiting/ready for the second barcode, snoozed, completed and cancelled states. A
completed or cancelled preset can be started again with a fresh occurrence and full duration.
The visible countdown refreshes once a second, without a countdown foreground service.

Expiration uses the existing exact alarm-clock delivery and shared ringing queue, service,
scanner and maths screen. Newly expired timers join behind occurrences already ringing;
all require their own dismissal, with one audio player. Timer origin and preset IDs are scoped
separately from clock-time alarm definitions. Timers never change repeating alarm schedules.
After the first barcode, silence, scan eligibility, the five-minute completion window and
full-volume escalation all use the existing occurrence workflow. Maths dismisses the entire
run; ordinary Cancel is unavailable once time is up, even if Android's broadcast arrives late.

Interval reminders use a separate **Timer interval reminders** notification channel and its
user-controlled notification sound. Only the next reminder is registered at a time, plus an
independent expiration event. There is no chime at expiration, no acknowledgment, scanner or
full-screen reminder. Android can delay reminder chimes in idle/Doze; late reminders do not
replay a backlog. Reminder quotas cannot delay the alarm-clock expiration. Notification
permission or a muted reminder channel can silence chimes without disabling final ringing.

Cancel before expiration cancels the expiration, reminder and associated notification while
retaining the preset. Deleting a preset also cancels its active countdown. **Ringing, snoozed
or partially dismissed runs must be dismissed before the preset can be deleted.** Barcode
library deletion also checks timer references; deleting a preset does not delete shared codes
or audio. One completed/cancelled result per preset is retained until its next run.

Within a boot, remaining time uses elapsed realtime, so changing the wall clock does not
restart or shorten the countdown. Durable wall deadlines and boot identity support recovery.
After reboot and first unlock, future timers retain the remaining wall-clock time; overdue
countdowns ring once through an immediate exact callback. Missed reminder chimes are skipped.
Already-ringing runs follow the existing recovery policy and resume playback on app open.
Direct Boot is not implemented. Power-off, force-stop and revoked exact-alarm access retain
Android's existing delivery limitations. Timers show permission diagnostics; granting exact
access restores supported pending events.

### Pixel 7 Pro / GrapheneOS timer checklist

- Create/edit/reopen a preset with a label, hours/minutes/seconds, custom sound, two codes and
  reminders; rotate or recreate the editor and confirm unsaved controls survive.
- Start two different short presets, leave Timers, lock/background the phone and verify both
  expire. Check a normal alarm can ring concurrently, with separate dismissals and one player.
- Cancel a running countdown and confirm it never rings. Restart it and confirm the full
  duration returns. At/after expiration, verify Cancel/Delete cannot bypass barcode dismissal.
- Try no code, wrong/correct single code, two codes with interval 0 and 1 minute, second-stage
  ready notification, and the five-minute full-volume escalation retaining the second stage.
- Complete maths before/after the first scan; check progress and attenuation, silent waiting,
  snooze interaction and absence of wake-up verification.
- Try a 3-minute preset with 1-minute reminders: chimes at minutes 1 and 2, only full ringing
  at minute 3. Mute the channel, deny notification access, and check expiration still rings.
- Edit a running preset and verify its snapshot is unchanged. Delete a countdown preset and
  confirm all events disappear. Delete an unrelated alarm and verify timers continue.
- Background and kill the process normally, then reopen: remaining time must not reset.
  Reboot/unlock with future and overdue runs, check the documented recovery policy, change
  the wall clock/time zone, and confirm no duplicate ringing or backlog of reminder chimes.

Validation for 0.10.0: **221 JVM unit tests**, debug APK assembly, lint (no errors), and scanner
packaging checks pass. Isolated Android emulator checks covered native preset creation/editing,
active snapshot isolation, cancellation/deletion, background expiry, separate dismissal of two
simultaneous timers, reminder notifications without playback, alarm/timer ID isolation, normal
process death, actual reboot/unlock deadline restoration and overdue ringing. Camera scanning,
physical audio, lock-screen policy and overnight delivery on GrapheneOS still require the
physical checklist above.
