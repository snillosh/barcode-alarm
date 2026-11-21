# Agent handoff

Documentation baseline: 18 November 2026. Read this after `AGENTS.md`. Commit IDs
below identify inspected application code, not the later documentation commit.

## Current checkout and code map

Application baseline `dcd3ae2` on `main`, version 0.12.0: charging support merged
following durable alarm history and optional Wellness Sync. On 18 November 2026,
the documentation commit was rebased onto fetched `origin/main` without conflicts;
the earlier local checkout (`cf15f95`, 0.11.0) had been four commits behind.
Backend main already accepts charging. Another worktree,
`../barcode-alarm-phone-charging`, retains `feature/phone-charging` at `6dd1f58`;
preserve it and inspect current refs before further branch work.

Production Kotlin: `app/src/main/java/uk/co/barcodealarm/`.

- `Alarm.kt`, `AlarmRules.kt`, `AlarmStore.kt`, `AlarmJsonCodec.kt`: definitions,
  compatible JSON and pure rules; barcode library records are shared references.
- `Occurrence.kt`, `OccurrenceStore.kt`, `OccurrenceJsonCodec.kt`,
  `OccurrenceManager.kt`: durable occurrence snapshots, transitions and recovery.
- `AlarmScheduler.kt`, `AlarmReceiver.kt`, `RestoreReceiver.kt`: exact OS callbacks
  and restore after system changes. `RingService.kt` owns sound/vibration;
  `RingActivity.kt` owns barcode/maths/verification interaction.
- `TimerPreset.kt`, `TimerStore.kt`, `TimerActivity.kt`: reusable countdowns sharing
  the occurrence engine, with separate preset identity and no wake-up verification.
- `CompletionTransition.kt`, `WellnessEvent.kt`, `WellnessSync.kt`,
  `WellnessSyncWorker.kt`: completion journal, restricted observations, delivery.
- `WellnessSettings.kt`, `WellnessCredential.kt`, `WellnessHttpClient.kt`,
  `WellnessConnection.kt`, `WellnessSyncActivity.kt`: secure optional connection.
- `MainActivity.kt`, `BaseActivity.kt`, `Navigation.kt`: native UI and draft handling.
- `ChargingRules.kt`, `ChargingStore.kt`, `ChargingTracking.kt`,
  `ChargingSettingsActivity.kt`: nightly windows/estimates, private SQLite queue,
  foreground tracking/boundary callbacks and settings. See `CHARGING.md`.

## Build and verification

CI uses JDK 25; source/target compatibility is Java 17. Use Android SDK platform 36,
build-tools 36.0.0, the Gradle wrapper and `ANDROID_HOME` or `local.properties`.

```sh
./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
python3 scripts/check_scanner_apk.py
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Run scanner packaging verification
as well as JVM/Robolectric tests: a compiling build can still omit required scanner
classes. `.github/workflows/android.yml` produces debug APK artifacts. Never install
over the user's app without authorisation and matching signing identity. Do not
uninstall to bypass a key mismatch: local alarms/history would be lost.

## Continue here

Read `DEVELOPING.md` for readability conventions and `WELLNESS_SYNC.md` for capture,
settlement, capacity and retry details. README's earlier version sections are
historical: current definitions and active occurrences are separate, snapshot-based
records; repeat next-delivery registration occurs when an occurrence fires.
Preserve concurrent follow-ups, one playback queue, rollback on scheduling failure,
barcode/maths requirements and process-wide state locking. Never hold that lock
across network requests or make dismissal depend on successful upload.

Timer completions are excluded from alarm observations. History captured with sync
disabled can upload later; older pre-history completions cannot be reconstructed.
Uninstall/clear-data loses pending local observations. Physical camera, lock-screen,
audio, overnight GrapheneOS and Phase 6 sync checks remain separate from JVM tests.
Charging is now merged here; `CHARGING.md`'s feature-branch rollout wording is
historical. Its checks additionally include `assembleRelease`. Physical nightly
capture, restart suppression and concurrent alarm acceptance remain unverified by
this documentation/push task.

## System map

These are four independent Git repositories, normally checked out as siblings:

| Repository | Owns | Integrates through |
| --- | --- | --- |
| wellness-dashboard | React/TypeScript browser UI, static Caddy container | Same-origin `/dashboard-api/v1` proxy to backend |
| wellness-backend | Java API, authentication, PostgreSQL, Flyway, aggregates | Scoped Android bearer API and separate browser session API |
| meditation-timer | Still Android app, local meditation/Mindful Minutes history | Optional upload as application `meditation` |
| barcode-alarm | Native Android alarms, barcode timers, local completion capture | Optional upload as application `barcode-alarm` |

Android apps remain useful offline. Local completion is recorded first; WorkManager
uploads later over HTTPS to `https://wellness-server.bsalter.online`. PostgreSQL
is the server observation store; the browser never connects to it directly.
The dashboard's configured public address is `https://wellness.bsalter.online`.
These addresses describe configuration, not a verified live deployment.

`ServerConfiguration` is a separate infrastructure repo owning production image
pins and home-server proxy/Compose configuration. Publishing an image or APK does
not deploy it. Do not change that repo, the server, DNS, or the user's phone as an
incidental part of an application change.

## Contracts that must survive a handoff

- Android: `POST /api/v1/events`, `GET /api/v1/events/{eventId}` and
  `GET /api/v1/connection`. Keys are application-scoped. Alarm keys use
  `alarm:write`, `alarm:read-own`, `connection:test`; meditation keys use
  `meditation:write`, `meditation:read-own`, `connection:test`. Provision locally
  using backend commands, never a public admin endpoint. Keep secrets out of docs,
  chat, source, command arguments and logs.
- Events retain stable IDs across retries, original occurrence instant and available
  zone/offset context. An accepted upload returns 201; an identical retry returns
  200 with the original receipt timestamp; changed immutable content returns 409.
  Do not regenerate IDs to hide a conflict.
- Schema-1 `alarm.occurrence.completed` represents final alarm completion, including
  verification when configured. Barcode timers, snoozes, intermediate scans and
  deletion are excluded. Completion is a wake-up proxy, not measured waking time.
- Schema-1 `meditation.session.completed` supports `meditation`, `mindful_timed`,
  `mindful_open`. Stable saved session IDs determine event UUIDs. Identical imported
  history can retry from a different installation; backend keeps the first uploader.
  Reliability tests, prompt-only nudges and discarded sessions are excluded.
  Local history deletion does not remove uploaded observations.
- Backend also supports `phone.power.connected`, `phone.power.disconnected`,
  `sleep.bedtime.estimated` for Barcode Alarm, using existing alarm scopes.
  Night windows are 20:00–03:00 in the original IANA zone. Estimates retain their
  named night and algorithm version; raw observations remain immutable. Check the
  actual Android branch before assuming capture exists there.
- Browser accounts share one personal dataset. Cookie auth is independent of app
  keys: Secure, HttpOnly, host-only, SameSite=Strict, fixed one-day sessions.
  Login checks exact configured Origin; logout also checks the session CSRF token.
  Never put passwords, session tokens or Android keys in browser storage.
- Overview uses seven calendar days including today in the requested browser zone,
  independently of event-list pagination. Meditation minutes exclude Mindful Minutes;
  practice count/days include all practice kinds. Earliest daily alarm completion is
  the wake proxy; latest bedtime estimate is grouped by payload night. Sleep charts
  default to explicitly labelled demo times; demo observations are never uploaded.

Detailed contracts live in backend `docs/API.md`, `MEDITATION.md`, `CHARGING.md`,
`DASHBOARD.md` and the source/tests. Older phase records are historical evidence,
not a statement of current checkout or deployment state.

## Start and finish each agent session

1. Read `AGENTS.md`, this file and the relevant feature document. Inspect
   `git status --short`, `git branch -vv`, `git log -8 --oneline` and
   `git worktree list` in every affected repo before editing. Preserve unrelated work.
2. Establish the requested outcome and compare local code with relevant sibling
   contracts. Cached `origin/*` refs are not proof of current remote state; fetch
   when needed. Do not reset, switch or merge another agent's worktree casually.
3. Make a focused change and run the checks below. Use disposable databases/emulators;
   never use personal observations or the user's phone as automated test fixtures.
4. Update this handoff with the resulting commit/branch, exact commands and outcomes,
   blockers and the next concrete step. Separate implemented, tested, published,
   deployed and physically accepted states. Do not copy old test counts as new results.
5. Follow this repo's Git working agreement. Never force-push. Record actual blockers
   and leave unrelated files intact. A successful build is not deployment acceptance.

## Cross-repository continuation as of 18 November 2026

The next recorded integration review is real-device end-to-end acceptance (backend
Phase 6). Existing docs do not establish that it happened. With explicit device/server
authorisation, verify same-signature app upgrades, offline capture/restart/reconnect,
exactly one backend observation, browser login and live aggregates, and unchanged
alarm/timer delivery with sync disabled or failing. Use each app's physical checklist.
Charging additionally requires its Android implementation and compatible backend
image. Verify actual CI digests, deployed versions and installed signatures before
claiming this system is running the latest code.

## Documentation verification

This handoff was checked against local source, build configuration, CI workflows,
existing feature documents and Git refs. No application tests, live server checks
or physical-device acceptance were performed for this documentation-only change.

## Latest application update — 21 November 2026

Branch `main`, application version **0.13.0** (versionCode 16), based on `a734fa7`.
This update adds per-alarm and per-timer **Can be snoozed** controls. Legacy alarms retain
snooze; new/legacy timer presets without an explicit choice default to off. Existing active
snapshots keep explicit choices. Snoozed timers can open their dismissal screen immediately,
stay silent, retain barcode-stage progress, and cancel their resume event when dismissed.

Settings now includes a 0–60 minute timer cancellation protection window (default 1 minute,
zero disables it). New runs snapshot it; legacy running occurrences default to zero. Both
cancellation and preset deletion are rejected at/inside the window, including at expiration.
Timer controls refresh when crossing the boundary. Alarm scheduling, completion capture,
timer exclusion from Wellness observations, charging and server contracts are preserved.

Verified locally for this update:

- `testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain`: **293 tests,
  zero failures/errors**, debug/release assembly and lint successful (warnings only).
- `python3 scripts/check_scanner_apk.py`: passed.
- New pure/Robolectric checks cover upgrade defaults, both editor checkboxes, alarm-editor
  recreation, Settings persistence, protected cancellation/deletion, snapshot isolation,
  immediate snoozed-timer dismissal, unchanged barcode/maths checks and no timer Wellness event.

The local command used Android Studio's JDK 25 and `/tmp/barcode-gradle`; the generated
Gradle daemon criteria were temporarily set aside and restored afterward to avoid the known
local runtime-only JDK selection issue. GitHub continues to use its installed JDK 25.
Physical phone/server acceptance was not performed. Next acceptance step: upgrade with the
same signing key, verify a 10-minute timer protected for its last 2 minutes, then explicitly
allow snooze and complete normal/barcode/maths dismissal before the snooze deadline.

CI follow-up for 0.13.0: implementation commit `261eb32` passed locally but its first hosted
run could not download Robolectric Android runtimes from `repo1.maven.org` (HTTP not-found),
affecting existing charging/history tests as well as the new tests before they executed.
The test task now explicitly uses Maven Central's canonical `repo.maven.apache.org` endpoint;
no test is disabled and no runtime application dependency is changed.
