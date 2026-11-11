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
duplicate deliveries. The receiver persists `isEnabled = false`, clears the scheduled
occurrence, sets `isRinging = true`, and starts `RingService`.

The receiver is short-lived. The service owns the ongoing notification, sound and vibration.
`RingActivity` owns the dismissal screen. Closing or rotating a screen must not own or stop
the player. There is no automatic repeat registration.

### 4. Dismiss

A scan returns to `RingActivity.onActivityResult()`. `AlarmRules.barcodesMatch()` compares
values and formats, including equivalent UPC-A/EAN-13 encodings. A match calls
`dismissAlarm()`.

The alternative maths path stores its monotonic start time once and increments a persisted
correct-answer count. `AlarmRules.canDismissWithMaths()` requires both five minutes and 15
correct answers. Returning to the ringing screen does not reset those values.

`dismissAlarm()` saves a disabled, non-ringing record, stops the playback service and returns
to normal UI. Another simultaneously ringing alarm is handled separately.

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

The three unit-test classes correspond to alarm rules, library rules and navigation. They
exercise pure functions without an emulator. The packaging check verifies actual scanner
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
