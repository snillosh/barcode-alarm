# Barcode Alarm

Minimal offline Kotlin Android alarm for Pixel 7 Pro / GrapheneOS. Native widgets, AlarmManager.setAlarmClock, offline ZXing, local preferences. No accounts, analytics, Internet permission or Google Play Services. Original requirements: [BRIEF.md](BRIEF.md).

## Build and install

Open this folder in Android Studio with Android SDK 36 and JDK 17 or newer supported by Gradle 9.6. The build uses Android Gradle Plugin 9.2 with built-in Kotlin. Internet is needed on the development machine to download build dependencies; the installed application is offline.

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If required, set `sdk.dir=/path/to/Android/Sdk` in an untracked `local.properties`. The APK is debug signed; keep the same signing key for updates. No release signing configuration is included.

## First use

1. Open Settings and diagnostics. Grant exact alarm access, notifications and full-screen intent access. Grant camera permission when scanning. Open app battery settings and select unrestricted background use manually.
2. Add an alarm, choose a time, register a barcode and optionally choose a system alarm sound. Save, then enable its switch.
3. Test Alarm schedules the first saved alarm ten seconds ahead, replacing any existing occurrence. It requires the real barcode or the full maths challenge to stop.

Enabled alarms fire once. Successful dismissal keeps the definition and barcode but disables the alarm. Enabling schedules the next local occurrence; a DST gap moves to the next valid time and an overlap uses the earlier offset. After scheduling, the absolute intended timestamp is preserved across time-zone changes and reboot, rather than silently moving the alarm. A pending occurrence whose timestamp passed during restart is disabled and logged. Re-enable it manually.

Ringing consumes the pending occurrence before starting a media-playback foreground service. Duplicate/stale receiver deliveries are ignored by matching the persisted occurrence timestamp. Simultaneous alarms are handled in saved order; each requires its own dismissal. Settings cannot edit or disable a ringing alarm. Maths progress uses elapsed realtime and synchronous local persistence; leaving the screen restores full app playback volume. A reboot resets maths progress for an unresolved ringing alarm because the monotonic clock has restarted.

## Physical device verification required

This app has **not been tested on GrapheneOS**. Verify each case on the Pixel before relying on it:

- Run the ten-second test unlocked, locked, then idle (also test an alarm several hours ahead).
- With an alarm scheduled, background the app and run `adb shell am kill uk.co.barcodealarm`; verify delivery. This is different from force-stop, which blocks delivery until the app is launched again.
- Reboot before a future alarm, unlock once, verify the original timestamp is restored. Reboot across a missed alarm and inspect diagnostics.
- Scan an incorrect code, correct code, EAN-13 / UPC-A equivalents, UPC-E, Code 128 and QR. Deny camera access and verify maths remains available.
- Answer 15 questions before five minutes: it must keep ringing. Wait five minutes with fewer than 15: it must keep ringing. Rotate, background, return and switch to scanning: progress must survive. Verify app playback is quieter only while maths is visible, with system alarm volume unchanged.
- Disable, edit, delete and re-enable alarms; verify Android's next-alarm indicator. Schedule overlapping alarms and dismiss each.
- Deny/revoke exact alarm, notification and full-screen access, then inspect Settings and scheduling errors. Android can remove scheduled alarms when exact access is revoked; reopen and re-enable affected alarms after granting access.
- Select a system sound and then make it unavailable; verify fallback and inspect the local diagnostic log. Check alarm volume, DND policy and vibration on the actual device.
- Kill the ringing process normally: verify foreground service recreation, audio and persisted dismissal progress. Verify low-memory and battery behavior.

## Limits and implementation notes

No pre-unlock boot support: storage is credential encrypted. No app can guarantee delivery when powered off, force-stopped or blocked by the OS. Full-screen presentation depends on Android/GrapheneOS permissions, lock state and notification policy; the ongoing notification opens the ringing screen. Normal navigation and device controls remain available.

A reboot does not launch a playback foreground service (Android restricts media playback services from boot receivers). An unresolved ringing alarm resumes when you open the app. Future enabled alarms are restored after unlock. Notifications denied or a muted alarm stream can prevent useful alarm presentation/audio; review Settings before use. No DND bypass or battery-management bypass is requested.

The bounded local diagnostic log records scheduling, firing, dismissal, missed occurrences and caught playback errors. It contains no barcode values. Android's own storage/OS failures can still prevent delivery or persistence.

Unit tests cover scheduling, DST gap handling, dismissal state, barcode comparison, question generation and both maths thresholds. Receiver/PendingIntent de-duplication, playback, permission flows and process recreation require Android/device verification; no instrumentation suite is included yet.

## Sources

- [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Foreground service launch requirements](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Android 15 foreground service restrictions](https://developer.android.com/about/versions/15/behavior-changes-15)
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache 2.0)

## Validation in this workspace

Debug APK assembly, all **16 JVM unit tests**, and Android lint completed successfully using Android Studio's bundled JDK. Lint has warnings (including SDK updates, deliberate synchronous persistence, and the service-lifetime alarm wake lock); no lint errors. API 36 is the current project baseline; test newer OS compatibility before raising the target SDK. The system `/usr/bin/java` here is a runtime without `javac`; select Android Studio's bundled JDK when building.

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
