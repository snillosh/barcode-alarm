# Bedtime charging — 0.12.0

## Setup

After installing an update signed with the same key, open Barcode Alarm once. The app offers to enable nightly bedtime charging. Enable it and use the settings screen to grant Alarms & reminders if needed. Android may also ask for notification permission. No additional app, API key, server port or account is needed. Existing Wellness Sync controls whether observations are uploaded.

Settings → Bedtime charging allows disabling tracking and shows scheduling/capture status and pending uploads. Tracking is separate from sync: disabling sync retains local observations for later upload; disabling tracking stops capture and the nightly notification, while keeping saved history.

The foreground service is automatically scheduled for **20:00–03:00 in the phone's current local time**. Its low-importance notification has no sound or vibration. The screen can remain closed. This service does not play alarm audio, use a continuous wake lock, poll the battery or hold the screen awake. Runtime power broadcasts record changes; exact one-shot boundary alarms start/finish tracking. Default WorkManager jobs deliver the stored queue over the existing HTTPS/Keystore path.

## Data and estimate

The night is named for the date its 20:00 window starts. Capture includes both power connection and disconnection (not whether the battery is actively charging, so reaching full charge does not count as unplugging). Events include receipt time, time zone/offset, window boundaries, battery percentage when available and charger type when available. No observation is invented from the initial battery snapshot. Charging has a separate private SQLite store/queue; the existing alarm completion transaction and saved alarms are unchanged.

At 03:00, version-1 estimation chooses the last observed new connection that remains connected. It emits `sleep.bedtime.estimated` with the connection event ID, estimated instant, window and algorithm version. It emits nothing if already connected at window start without a later connection, unplugged at the end, or continuity cannot be proved. These are estimates, not measured sleep onset. All raw observations remain available on the backend for later recalculation.

A process/service restart, reboot, manual clock change, time-zone change, late start or late finalization conservatively suppresses the estimate. New observations can still be captured. Start/end callbacks have a ten-second scheduling tolerance; Android delivery is not guaranteed to the millisecond. Daylight-saving boundaries follow the original window's local zone. There is no historical charging backfill and no estimate for a partially observed first night.

## Background limitations

Install alone cannot launch an Android app: open it once and enable tracking. Force-stop prevents automatic work until the app is opened again. User-stop, exact-alarm revocation, battery restrictions and OS process termination can interrupt tracking. Boot/package/time changes restore scheduling, with the affected night treated conservatively. After reboot unlock once. Physical Pixel/GrapheneOS acceptance is still required; automated tests cannot prove vendor background delivery behaviour.

## Backend rollout and branches

This feature is on `feature/phone-charging` in both repositories. Deploy the matching backend contract before enabling uploads from this APK. No database migration is required; existing `alarm:write`, `alarm:read-own` and `connection:test` permissions cover these Barcode Alarm observations. The API validates each new payload and advertises all three new schema-1 event types. Existing alarm and meditation contracts remain supported.

Charging events retain stable IDs across retries. Per-server acknowledgements/rejections are durable. Rejected observations stay local; “Sync now / retry pending” retries them. No credential enters the charging database. Existing cleartext/TLS/redirect/backup protections remain in force. No live deployment, main-branch merge or phone installation is performed by this branch.

## Validation

Run `./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug`, plus `python3 scripts/check_scanner_apk.py`. Tests cover overnight/DST boundaries, last connection, already charging, disconnected end, duplicates, restart/clock/delayed-callback suppression, durable local history, per-server acknowledgements, offline/duplicate delivery, actual service receiver registration, silent foreground notification and unique/cancelled boundary alarms. Existing alarm regressions remain included.

Device acceptance: enable tracking before 20:00; close the activity; confirm the silent notification begins; connect/disconnect/reconnect; confirm observations reach PostgreSQL and the final estimate references the last connection. Also test an unplugged end, already-connected start, offline/reconnect, service stop/reboot, DST/time-zone changes and normal alarm ringing while tracking. Use a dedicated test build/emulator for changing clock/window boundaries; do not change a real alarm phone's clock merely to accelerate testing.
