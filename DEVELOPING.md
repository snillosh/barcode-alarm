# Reading the code as a C# developer

This is a small native Android application. It has no dependency-injection container,
MVVM framework, reactive state library, or navigation framework. The code is intentionally
ordinary classes, immutable records, private methods, event callbacks, and native controls.
The extra lines make the control flow visible; they are not additional functionality.

## Start here

Read these files in order. Production Kotlin lives in `app/src/main/java/uk/co/barcodealarm/`.

| File | Responsibility | Familiar .NET comparison |
| --- | --- | --- |
| `Alarm.kt` | Alarm definition and current occurrence/challenge state | Immutable C# record |
| `AlarmRules.kt` | Time calculation, barcode matching, dismissal conditions | Pure business-rule helper |
| `BarcodeLibrary.kt` | Library validation, selection and migration | Domain operations returning new records/lists |
| `Navigation.kt` | Which page Back should return to and whether a draft changed | Small navigation decision service |
| `AlarmJsonCodec.kt` | Compatible alarm JSON mapping and new-field defaults | Small explicit serializer |
| `RepeatDays.kt` | Stable day ordering and human-readable labels | Calendar formatting helper |
| `AlarmStore.kt` | Reads/writes local JSON; migrates old saved codes | Repository backed by a local settings store |
| `AlarmScheduler.kt` | Creates/cancels Android alarm registrations | Adapter around an operating-system scheduler |
| `AlarmReceiver.kt` | Receives a scheduled occurrence and starts playback | OS-invoked event handler |
| `RestoreReceiver.kt` | Restores future occurrences after system events | Startup/system-event handler |
| `RingService.kt` | Owns looping sound, vibration and notification | OS-managed background service; not ASP.NET middleware |
| `RingActivity.kt` | Displays scan/maths dismissal UI | Window/controller for a screen |
| `MainActivity.kt` | Coordinates normal pages, drafts and user actions | Window/controller with private rendering/action methods |
| `BaseActivity.kt` | Shared UI controls, palette and Android Back integration | Shared base window plus small control-building helpers |
| `MathsQuestion.kt` | Generates one arithmetic question and four choices | Record plus a factory method |
| `PortraitCaptureActivity.kt` | Connects the third-party scanner to the portrait manifest entry | Thin framework subclass |

The original `Rules`, `Store`, `Scheduling` and `Screen` names were replaced with
`AlarmRules`, `AlarmStore`, `AlarmScheduler` and `BaseActivity`. Receiver classes now have
their own files. Android component names and the saved JSON keys remain unchanged.

## Kotlin syntax you will encounter

| Kotlin | Closest C# equivalent / meaning |
| --- | --- |
| `val alarm = ...` | A local/reference you cannot reassign; it does not make the referenced object deeply immutable |
| `var currentPage = ...` | A reassignable local/property |
| `fun saveAlarm(alarm: Alarm)` | A method with the parameter's type written after its name |
| `fun getAlarm(id: Int): Alarm?` | A method returning a nullable `Alarm` |
| `Unit` | Roughly `void` |
| `@Test` | JUnit test marker, like xUnit `[Fact]` or NUnit `[Test]` |
| `data class Alarm(...)` | Roughly a record, with generated equality and a `copy` method |
| `alarm.copy(isEnabled = false)` | Roughly `alarm with { IsEnabled = false }` |
| `alarm?.id` | Null-conditional access, like C# `alarm?.Id` |
| `savedAlarm ?: defaultAlarm` | Null coalescing, like C# `savedAlarm ?? defaultAlarm` |
| `val alarm = getAlarm(id) ?: return` | Get the value, or return immediately if it is null |
| `check(condition) { "message" }` | Throw `IllegalStateException` if a state invariant fails |
| `require(condition) { "message" }` | Throw `IllegalArgumentException` if an argument is invalid |
| `object AlarmRules` | A singleton object; used here like a static helper class |
| `companion object` | A place for class-level factories/constants/shared state, similar to static members |
| `private val store by lazy { ... }` | Create the value on first access, similar in purpose to `Lazy<T>` |
| `lateinit var contentLayout` | Initialized later by lifecycle code; accessing it too soon throws |
| `() -> Unit` | A callback type similar to `Action` |
| `addButton("Save") { saveAlarmDraft() }` | Pass a lambda callback; Kotlin allows the final lambda outside parentheses |
| `{ alarm -> alarm.isRinging }` | A lambda, like `alarm => alarm.IsRinging` |
| `.find { ... }` / `.firstOrNull { ... }` | Similar to LINQ `FirstOrDefault` |
| `.filter { ... }` / `.map { ... }` | Similar to LINQ `Where` / `Select` |
| `when (page) { ... }` | Similar to `switch`, and it can return a value |
| `value.orEmpty()` | Like `value ?? string.Empty` for a nullable string |
| `value.coerceAtLeast(0)` | Like `Math.Max(value, 0)` |
| `"Scan $name"` / `"${alarm.hour}"` | String interpolation, like C# interpolated strings |
| `object : Runnable { ... }` | An inline object implementing an interface; distinct from a singleton declaration |
| `SomeClass::class.java` | The JVM class object, used by Java/Android APIs; comparable in purpose to `typeof(SomeClass)` |

Unlike C#, Kotlin classes are final by default (comparable to C# `sealed`) unless marked `open`.
Properties and methods normally use camelCase. There is no `new` keyword for constructors.
We use explicit lambda names and normal blocks rather than nested shorthand where the
shorthand would obscure the behaviour.

## Follow an alarm through the app

### 1. Create or edit

`MainActivity.showAlarmEditor()` draws the time picker, named barcode dropdown and buttons.
The editable record is `alarmDraft`. UI callbacks change the draft only; pressing Save calls
`saveAlarmDraft()`. `BarcodeLibrary.select()` verifies the barcode selection and copies
its value/format into the alarm. `AlarmStore` saves the definition. An enabled alarm also
passes through `AlarmScheduler.enable()`.

Rendering methods start with `show...`, `add...` or `create...`. Actions have names such as
`saveAlarmDraft`, `deleteAlarm`, `navigateBack` or `requestExactAlarmAccess`. This separation
is by ordinary private methods, rather than extra framework layers.

### 2. Enable and schedule

`AlarmScheduler.enable()` cancels the previous token, creates the next occurrence, persists
it, then calls `AlarmManager.setAlarmClock()`. If Android rejects scheduling, the enabled
flag is rolled back and the caller displays the error.

A `PendingIntent` is an OS-held token describing an action Android may perform later. It is
not a .NET `Task`, a thread, or an in-memory delegate. The explicit receiver and alarm ID
identify the registration; the timestamp extra identifies the particular saved occurrence.
That is why the app can ring without its ordinary screen or process already running.

### 3. Fire

Android invokes `AlarmReceiver.onReceive()`. `AlarmRules.shouldTrigger()` rejects stale or
duplicate deliveries. The receiver clears the scheduled occurrence, sets `isRinging = true`, and starts
`RingService`. One-shots become disabled; a repeating series retains `isEnabled = true`
while ringing, with no pending timestamp. Duplicate deliveries are rejected by `isRinging`.

The receiver is short-lived. The service owns the ongoing notification, sound and vibration.
`RingActivity` owns the dismissal screen. Closing or rotating a screen must not own or stop
the player. A repeating alarm registers its next selected day after successful dismissal.

### 4. Dismiss

A scan returns to `RingActivity.onActivityResult()`. `AlarmRules.barcodesMatch()` compares
values and formats, including equivalent UPC-A/EAN-13 encodings. A match calls
`dismissAlarm()`.

The alternative maths path stores its monotonic start time once and increments a persisted
correct-answer count. `AlarmRules.canDismissWithMaths()` requires both five minutes and 15
correct answers. Returning to the ringing screen does not reset those values.

`dismissAlarm()` delegates to `AlarmScheduler.dismiss()`. `AlarmRules.completeOccurrence()`
clears a one-shot, or computes the next selected day for an enabled repeat. The scheduler
registers that next delivery, rolling back its enabled flag if Android rejects it. Dismissal
still stops the current occurrence and reports any future-scheduling failure. Another
simultaneously ringing alarm is handled separately.

### 5. Snooze and restore

`AlarmRules.snooze()` changes a ringing alarm into an enabled pending snooze, preserving
its definition, repeat days and maths progress. `AlarmScheduler.snooze()` saves that state
then registers a five-minute `setAlarmClock()` delivery. On failure it restores the original
ringing record. The Activity stops playback only after successful registration. On delivery,
`markTriggered()` clears the pending snooze flag but retains the original occurrence time.

`repeatDays` is a set of Java `DayOfWeek` values (ISO Monday=1 through Sunday=7, unlike the
numeric order of C#'s `DayOfWeek`). The next-time calculation walks calendar dates, not fixed
24-hour intervals. `occurrenceScheduledAtMillis` preserves the original occurrence across
snoozes, preventing a clock rollback from re-registering that same occurrence on dismissal.

`restoreAfterSystemChange()` is pure and unit tested. It preserves future one-shot/snooze
instants, recalculates regular repeats on local clock/zone changes, advances missed repeats,
and disables missed one-shots. At reboot it clears invalid monotonic challenge progress.
An interrupted repeating alarm advances to its next selected day instead of leaving future
repeats blocked behind an old ringing flag. No receiver starts playback at boot.


## Two clocks, two kinds of state

`scheduledAtMillis` is a Unix wall-clock timestamp, like a `DateTimeOffset` instant. Android
uses it to schedule delivery. `mathsStartedAtElapsedMillis` uses Android's monotonic time
since boot, conceptually like a stopwatch. Changing the wall clock cannot shorten the maths
challenge. Reboot resets the monotonic clock, so the restore receiver resets challenge timing.

`AlarmStore` persists durable state in private `SharedPreferences` JSON. `Bundle` is temporary
Activity recreation state: page, draft and pending scan/name input. It is not the alarm database.
`RingService.isMathsVisible` is only an in-process UI visibility flag controlling playback gain.
The timer/count are never stored solely in that flag.

## Android lifecycle and threading

Android constructs Activities, services and receivers. Their lifecycle methods are overrides:

- `onCreate`: initialize and restore a component.
- `onResume`: an Activity is interactive again.
- `onPause`: an Activity is losing foreground interaction.
- `onSaveInstanceState`: save temporary state for recreation.
- `onDestroy`: release owned resources.
- `onReceive`: handle an OS broadcast.
- `onStartCommand`: a started service has received a start request.

The callbacks used here run on Android's main/UI thread. `Handler(Looper.getMainLooper())`
posts work back to that thread; it does not create a worker thread. Its 250 ms playback
update and 500 ms countdown update maintain an already active alarm. Neither triggers a
scheduled alarm: only `AlarmManager` does that.

This is not ASP.NET request lifetime or a Windows Service singleton lifetime. Android may
recreate components or terminate processes. Durable alarm state is therefore stored before
scheduling, triggering and successful dismissal.

## Navigation and UI helpers

`currentPage` identifies the normal page. A non-null `alarmDraft` tells barcode management
it was opened from the editor. `Navigation` decides Back's destination; `MainActivity`
performs that decision. Changed drafts require confirmation before leaving.

`BaseActivity` creates native controls with common spacing, colours and touch targets.
`addCard { ... }` and `addButtonRow { ... }` temporarily direct child controls into a container,
then restore the parent in `finally`. They are ordinary callback helpers, not a UI DSL library.
The native widget property assignments are similar to setting up WinForms controls in code.

Dark mode uses a saved boolean and a native Android theme. Selecting a theme recreates the
Activity so native controls are also themed; saved instance state retains the current page.
Modern Back uses Android's dispatcher. The legacy override exists only for older Android.
Dialogs, the keyboard, dropdowns and the scanner retain their platform Back handling first.

## Test and change safely

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
python3 scripts/check_scanner_apk.py
```

The unit-test classes cover alarm rules, library rules, navigation, snooze/repeat state
transitions, reboot/clock restoration and backward-compatible JSON mapping. They exercise
pure functions without an emulator. JSON tests have a test-only real `org.json` dependency;
the Android app continues to use the operating system's implementation. The packaging check verifies actual scanner
class definitions in the APK, which ordinary JVM tests cannot inspect.

Unit tests do not establish delivery reliability on GrapheneOS. After changes to lifecycle,
manifest, playback or scheduling, use the physical-device checklist in `README.md` as well.
Keep saved JSON keys stable or write an explicit migration. Keep receiver/service component
names stable while existing Android PendingIntents might still refer to them.

For a business-rule change, start with the relevant pure class and its test. For a UI change,
start with the relevant `show...` method. For a playback problem, inspect `RingService` and
the local diagnostic log. Avoid combining feature additions with a readability-only refactor.

## Readability conventions

Use four-space indentation, one statement per line, descriptive domain names, and named
arguments when several constructor values could be confused. Prefer a straightforward
loop or a named helper over nested scope functions. Small, clear expressions are still
normal Kotlin; the goal is clarity rather than mechanically reproducing C# syntax.

Comments should explain a reason, a lifecycle constraint, a time unit, a persistence
contract, or an unfamiliar Kotlin construct. Avoid comments that merely repeat an assignment.
Keep rendering and user actions in separately named methods, without adding architectural
layers solely to make a file shorter. Do not mix behaviour changes into formatting/refactoring.

The refactor was formatted with standalone ktfmt 0.54 using `--kotlinlang-style`.
That tool is not an application dependency. Generated Gradle wrapper files are left alone.

## Playback volume ramp

`PlaybackSettings` is an immutable record of the global on/off choice and duration.
`PlaybackSettingsStore` persists it in a separate `playback` preferences file. `PlaybackVolume`
is a pure gain calculation using monotonic elapsed time; unit tests cover its bounds, duration,
maths attenuation and invalid-value fallback. The implementation ramps player gain, not system
stream volume or perceived loudness.

`RingService` reads the settings when created. Once a sound is prepared, it records
`playbackStartedAtElapsedMillis` through `AlarmStore.recordPlaybackStart()`, preserving the
ringing queue's order. It sets gain before `MediaPlayer.start()` and updates it in the existing
250 ms playback callback. The persisted clock survives process recreation; the alarm rules
clear it for snooze/new occurrences and reboot. Maths applies a 0.2 multiplier to the current
ramp rather than replacing the ramp's gain. No timer here schedules alarm delivery.

Boot restoration now records the successfully restored `Settings.Global.BOOT_COUNT` in
`AlarmStore`. Android may reissue `BOOT_COMPLETED` when the app is restored; the broadcast
alone does not prove the device rebooted. `AlarmRules.isNewDeviceBoot()` checks the identity
before clearing monotonic timers or advancing an interrupted repeat. Unknown boot identity
keeps conservative reset behaviour. The boot marker is committed after the restoration loop
so interrupted work can retry. Optional playback-clock write failure is logged to Android's
system log and does not block sound playback; the current service retains its in-memory clock.

## Icon resources

`artwork/barcode-alarm-icon.svg` is the editable design reference; `icon-preview.svg/png`
shows rounded-square, circular and sample themed masks. These are design assets, not an
application dependency. `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` contain
the corresponding native Android vector paths. `mipmap-anydpi-v26/ic_launcher.xml` combines
the foreground/background; its `v33` counterpart also supplies the monochrome layer.
`ic_alarm_notification.xml` uses the same white geometry with tighter optical bounds for
the small notification icon. If changing the design, update these paths together and inspect
small-size and adaptive-mask renders. Generated build outputs remain excluded from Git.


## Occurrence workflows (0.8.0)

`Alarm` remains the definition/editor model. `Occurrence` is an immutable state-machine
record containing a unique ID, definition ID and its own Alarm snapshot, plus stage/deadlines.
`OccurrenceRules` contains pure transition/deadline decisions with explicit clock arguments.
`OccurrenceStore` persists snapshots separately. `OccurrenceManager` reconciles exact OS
alarms and notifications after durable transitions. `OccurrenceReceiver` validates each
callback against the persisted stage and expected deadline. All app entry points share the
main thread, and completed records are removed so old actions are harmless.

`ActiveAlarmStore` adapts snapshots to the existing playback/maths code. The audio service
reads only audible occurrences; the Activity can also read silent intermediate stages.
Escalation's fullVolume flag bypasses only the ramp, retaining maths gain attenuation.
Definition cancellation explicitly clears all dependent workflows. Repeat registration is
independent of an older occurrence's verification. Legacy active definitions migrate into
snapshots on opening/restoration; existing scheduled definition PendingIntents stay valid.

## Alarm-list conveniences (0.9.0)

`AlarmOverview` owns label normalization/defaults, clean duplication, selection of saved future
scheduled definitions and shared duration/date presentation. It deliberately reads registered
`scheduledAtMillis` values rather than projecting a new date from the clock controls. Scheduling
still belongs to `AlarmScheduler`, whose enable method now returns the successfully registered
record so UI success feedback cannot run on its failure path. Background callers ignore that
return value and never show a toast.

`AlarmStore.nextAlarmId()` reserves IDs synchronously for new drafts and duplicates. JSON adds
an optional `label` key with a blank default; no destructive migration is needed. Occurrence
snapshot serialization automatically carries labels. `MainActivity` uses native EditText and
existing cards/buttons/Toast, with a lifecycle-scoped home countdown callback and clock-change
receiver. Tests supply explicit wall-clock instants and local zones, including both DST changes.

## GitHub Actions

`.github/workflows/android.yml` provisions Temurin JDK 25 (matching the existing Gradle daemon
criteria), reuses SDK 36/build tools 36.0.0 from the Ubuntu runner image (installing only
if missing), and invokes the tracked Gradle wrapper. SDK installation uses the runner's JDK 17
for command-line-tool compatibility; Gradle builds use JDK 25. It
runs the same unit-test, lint, assembly and scanner checks documented above. Gradle dependencies
and the CI-only debug keystore are cached; pull requests read the main Gradle cache. Actions
are pinned to reviewed commit IDs, the job token is read-only, and no signing secrets are
required. APKs are uploaded only after successful validation; diagnostic reports upload even
on failure. See README for downloads and debug signing/installation behavior.

The user's standing workflow is recorded in `AGENTS.md`: complete relevant checks, commit,
and push completed code changes using the SSH origin.

## Timer presets and shared occurrences (0.10.0)

`TimerPreset` stores duration, reminder minutes (zero means off) and an Alarm configuration
record, reusing its barcode/sound/label fields. `TimerRules` normalizes away alarm execution,
repeats and verification, and supplies pure preset mutations, countdown/reminder transitions,
clock calculations and recovery. `TimerStore`/`TimerJsonCodec` persist presets independently;
`TimerActivity` is a native top-level list/editor with a visible-only countdown callback.

`Occurrence` now has an ALARM/TIMER source, COUNTDOWN/CANCELLED stages and optional timer timing
metadata. Old JSON defaults to ALARM. The shared store reserves all occurrence IDs and keeps
only the latest terminal timer result per preset. Newly expired timers append to the existing
ringing queue; active stores still filter audible/silent dismissal stages and ignore countdowns.
Source-scoped definition matching prevents alarm deletion from cancelling a timer with the
same numeric preset ID. Library deletion checks both preset and occurrence references.

`OccurrenceManager` registers exact expiration via the existing alarm-clock mechanism,
translating monotonic remaining time to the next wall trigger. The next reminder alone uses
an elapsed-realtime exact idle callback. Its persisted index advances before posting an
ordinary notification, making duplicate delivery harmless. Expiration is independent of
reminder delivery and Doze reminder quotas. Boot-count metadata detects an invalid elapsed
clock even if app opening precedes boot restoration; overdue runs ring once after unlock.
Only the existing RingService, RingActivity, scanner and maths implementation handle expiry.

All transition calculations accept supplied wall/elapsed clocks. Timer JVM tests cover
preset CRUD and codecs, validation, independent runs, cancellation/deadline races, reminders,
shared barcode/maths dismissal, source identity and reboot/clock recovery without sleeps.


## Wellness history and optional sync

`CompletionTransition` preserves the save/reconcile/rollback order. `OccurrenceStore`
atomically commits terminal state with a short-lived history recovery marker in the
same preferences file; only settled `WellnessRecord`s can upload. `cancelDefinition`
intentionally bypasses completion capture. Preserve that distinction and the old
JSON keys. A shared lock protects worker acknowledgements and occurrence mutation.
`WellnessEvent` serializes only the versioned public contract, never local snapshots.

`WellnessSynchronizer` handles delivery outcomes, with an injected transport/credential
reader in tests. The production `WellnessSyncWorker` uses platform HTTPS and Keystore,
unique constrained WorkManager jobs and a process-wide network lock. No token enters
Worker Data. `BarcodeAlarmApplication` supplies on-demand WorkManager configuration;
the default initializer is removed so an alarm receiver's startup does not initialize
the work database. Enqueue setup after completion runs off the dismissal thread.

`WellnessSyncActivity` uses the existing native controls and does not store typed keys
in view state. Catch security/network failures with fixed, non-sensitive messages;
never send their raw exceptions to `runUiAction`/diagnostic logging. Keep the manifest
cleartext ban and credential backup/device-transfer exclusions. Test-only Robolectric
and WorkManager libraries do not become application services. See WELLNESS_SYNC.md
for retention, capacity, recovery semantics and the physical-device checklist.

## Snooze and timer protection (0.13.0)

`Alarm.canBeSnoozed` is configurable and part of definition and occurrence JSON. Alarm decode
retains a true legacy default; timer preset and timer occurrence decode pass a false legacy
default. `Occurrence.canSnooze` is the common UI/engine guard. Snoozed timers are available to
the dismissal UI while remaining non-audible. `dismissalStage` retains their pre-snooze barcode
stage, so scanning/maths can finish them without restarting sound or requiring the first code
again. Clock-time snooze presentation is otherwise unchanged.

`TimerSettings` stores the global 0–60 minute cancellation protection (default 1). Starting a
run copies it into `timerCancellationLockMinutes`; old occurrence JSON defaults to zero.
`TimerRules.canCancel` uses the monotonic remaining time with a strict greater-than boundary.
Both Cancel and preset deletion enforce it, and the visible timer list re-renders when that
permission changes. Settings edits never rewrite in-progress snapshots. The added tests include
pure boundary/migration cases and Robolectric persistence, manager and native-view checks.
