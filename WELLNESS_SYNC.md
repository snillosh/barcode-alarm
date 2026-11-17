# Wellness Sync (0.11.0)

Open **Settings & diagnostics → Wellness Sync**. This feature is optional and is
disabled by default. Alarm definitions, active occurrence state and timer storage
remain in their existing local SharedPreferences/JSON stores. No Room migration,
account, Google Play Services dependency or network requirement for alarms is added.
WorkManager maintains its own internal scheduling database.

## Setup

1. Deploy the independently tested wellness-backend image and provision a
   `barcode-alarm` key with `alarm:write`, `alarm:read-own` and `connection:test`.
   The server's repository documents local key provisioning; credentials are never
   created through a public administrative endpoint.
2. Open Wellness Sync. Set the HTTPS server URL (default:
   `https://wellness-server.bsalter.online`) and paste the key. Leave background
   sync disabled initially and tap **Save connection**.
3. Tap **Test saved connection**. It runs asynchronously when Android permits
   connected work. A successful result verifies application identity, write
   permission and supported event schema. An unavailable server does not affect alarms.
4. Enable background synchronisation and save. **Sync now / retry pending** queues
   an immediate attempt and explicitly retries retained rejected records.

Changes take effect when saved, not while typing. A blank key field preserves the
saved key; clearing uses a separate confirmation. Key replacement/clear and server
changes invalidate in-flight acknowledgement state. An already-sent request can
finish on the server, but the old configuration cannot mark records synced after
it has changed. Retrying the original stable event ID handles that race safely.

The URL must use HTTPS, without credentials, query, fragment, encoded path or dot
segments. Ordinary optional base paths and custom HTTPS ports are supported.
The app uses standard platform certificate/hostname validation and never follows
credential-bearing redirects. HTTP is disabled in both debug and release builds.
Keep existing Caddy/DNS/HTTPS configuration under the operator's control.

## Local observations and delivery

Only **genuine future alarm occurrence completions** generate schema-1
`alarm.occurrence.completed` events. Intermediate first/second scan waits,
snoozes, verification delays, timers and definition deletion do not generate these
events. A verified alarm completes only after successful final verification or its
existing authorised re-dismissal path. No alarm rules or barcode/maths challenges
are changed. Completion does not establish the user's exact waking time.

Records contain stable random event/installation IDs, local occurrence and
alarm-definition IDs, actual completion UTC instant, available scheduled instant,
and zone/offset context. They exclude barcode values/names, labels, sounds and
monotonic boot-relative clocks. Fixed-offset zones retain their offset without
inventing an IANA name. Old completed alarms were not retained by earlier app
versions, so they cannot be reconstructed or backfilled. History captured by this
version while sync is disabled uploads later using the same saved IDs.

Completion and a recovery journal are committed together in the existing
`occurrences` preference file. Android registrations/notifications are reconciled
before the record becomes uploadable. A crash before settlement leaves a durable
marker; app/boot restoration or the worker finishes reconciliation before upload.
A scheduling failure rolls back both active state and the unpublished record.
Journal snapshots are temporary and local; only the restricted event payload is sent.
All occurrence/history read-modify-write operations share a process-wide lock.
The network never runs while holding that lock or on the dismissal/UI thread.
Even optional WorkManager initialization after dismissal is queued off that thread.

Worker requests require network availability, use unique drain/test jobs and a
unique 15-minute recovery sweep, and have exponential backoff. A process-wide
network lock prevents overlapping periodic/manual workers. Pending data is
acknowledged only after the expected 201/200 response identifies the same event ID.
Ten records per pass keeps bursts small; remaining records retry later. WorkManager
and local state survive process recreation/reboot, subject to Android scheduling
and first unlock. A crash between local commit and enqueue is recovered by the
periodic sweep or next app opening. Background delivery is eventual, not an exact timer.

Network errors, 5xx and malformed acknowledgements retain records for retry. 429
honours numeric or HTTP-date Retry-After. 401/403 pause until settings/manual action;
400/409/413/415/422 retain and flag the record rather than discarding it. Redirects
and missing endpoints pause with a server-URL message. Status never displays raw
server/error text. Manual retry retains the event ID and cannot overwrite a
conflicting server observation. Accepted IDs remain idempotent on repeated upload.

Acknowledgements are scoped to the normalized server URL. A different destination
receives retained history independently; key rotation on the same server does not
create another namespace. The latest 1,000 acknowledged records for the current
server are retained, along with all pending/rejected records, up to 10,000 total
records. Pending records are never silently pruned. At capacity or on storage
failure, recording may fail and a local history warning is retained; existing
alarm dismissal still takes priority. Inspect that warning rather than assuming
all observations were captured. Clearing app data/uninstalling removes unsynced
local observations; a Docker volume/server backup cannot recover them.

## Credential security

The saved key is AES-GCM encrypted with a random IV using an Android Keystore key.
The encryption key is non-exportable; no plaintext API key is stored in preferences,
BuildConfig, Gradle properties, Worker Data or logs. Backup/device-transfer rules
exclude the credential preferences, and existing `allowBackup=false` is preserved.
An unreadable Keystore credential requires replacement; there is no plaintext fallback.

The key entry is masked, excluded from view-state restoration/autofill and IME
personalized learning. The settings window blocks screenshots; saved credentials
are never read back into the input. Neither raw exceptions nor server response
bodies are passed to app diagnostic logging. The app still works if GrapheneOS
network permission is denied or no credential/server exists.

## Verification and remaining device checks

The automated suite includes existing alarm/timer/scanner rules and new tests for
capture failure, reconciliation rollback, interrupted settlement, store recreation,
idempotent recovery, deletion exclusion, stable identities/time-zone JSON, offline
retry, lost responses, invalid keys, disabled sync, mid-upload settings changes,
record rejection, Retry-After, unexpected acknowledgements, unique constrained work,
cancellation and secure credential-entry flags. Robolectric checks use API 28;
these do not replace tests on the target Pixel/GrapheneOS device.

Before end-to-end acceptance (Phase 6), use a real installed build and trusted HTTPS
backend to check:

- Existing saved alarms/barcodes/timers survive an update with the same signing key.
- Barcode, two-scan, maths, verification and snooze behave as before with airplane
  mode, missing/invalid credentials, disabled sync and an unavailable server.
- Complete an alarm offline, restart the app and reboot/unlock, then reconnect;
  confirm exactly one server observation and original completion time/ID.
- Exercise rejection/manual retry, changed server, key rotation/clear and long outages.
- Verify Android Keystore storage, TLS rejection, backup exclusion and absence of
  credentials in physical-device diagnostics/crash reporting.
- Observe WorkManager behaviour under battery restrictions; sync may be delayed,
  while alarm delivery continues through its existing AlarmManager/service path.

No physical device was updated or home-server networking configured during this phase.

Local Phase 5 verification on 17 November 2026 passed **247 tests** (no failures,
errors or skips), `lintDebug`, `assembleDebug`, `assembleRelease`, and scanner DEX
checks on both APKs. The release manifest was inspected for cleartext prohibition,
backup rules, application configuration and removal of eager WorkManager initialization.
The release APK is unsigned; CI supplies the existing installable debug artifact.
