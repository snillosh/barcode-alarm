# Build a Minimal Barcode Alarm App for GrapheneOS

## Project overview

Build a small, dependable Android alarm clock application in **Kotlin**, designed primarily for a **Google Pixel 7 Pro running GrapheneOS**.

The purpose is simple: when an alarm goes off, I must physically get out of bed and scan a previously registered barcode or QR code to dismiss it.

For example, I might register the barcode on my shampoo bottle in the bathroom. When the alarm rings, I must walk to the bathroom and scan that exact barcode.

If the barcode is unavailable, I must complete a five-minute maths challenge instead.

This is a personal application, not a commercial product.

**The overriding priority is reliability, followed by simplicity.**

Do not add unnecessary features, complicated architecture, accounts, analytics, internet access or visual embellishments.

Build a genuinely minimal MVP that we can expand later.

---

## 1. Technology and architecture

- Use Kotlin throughout.
- Build a native Android application.
- Use Android SDK APIs directly where practical.
- Use Gradle for building.
- Use a simple, maintainable project structure.
- Use minimal dependencies.
- Do not require Google Play Services.
- The app must function completely offline.
- Do not request internet permission.
- Prefer a maintained offline barcode-scanning library such as ZXing.
- Use simple Android UI components. Avoid unnecessary frameworks and architectural layers.
- Store alarm settings locally using straightforward Android storage APIs.
- Follow current Android requirements for the target SDK and installed OS version.

Avoid experimental APIs, unnecessary services and complex background processing.

## 2. Alarm creation and scheduling

Allow the user to:

- Create an alarm.
- Choose its time.
- Register a specific barcode or QR code for that alarm.
- Enable or disable the alarm.
- Delete the alarm.
- Select a system alarm sound.

### One-shot alarms only

There must be **no repeating alarms** in this MVP.

Each alarm is a one-shot alarm.

When enabled, it should ring at the next occurrence of its configured time.

For example:

- If it is 10 PM and I enable a 7 AM alarm, it should ring at 7 AM the following morning.
- If it is 6 AM and I enable a 7 AM alarm, it should ring at 7 AM that morning.
- If the configured time has already passed today, schedule it for tomorrow.

After an alarm is successfully dismissed, it must automatically become disabled.

To use that alarm again, I must manually enable it. Enabling it schedules the next occurrence of its configured time.

The alarm definition and its registered barcode must remain saved after dismissal.

### Scheduling implementation

- Use Android `AlarmManager.setAlarmClock()` for genuine alarm-clock scheduling.
- Use appropriate explicit `PendingIntent` objects.
- Schedule alarms using the device's local time zone.
- Persist the alarm configuration before scheduling it.
- Prevent duplicate scheduling.
- Correctly cancel scheduled alarms when disabled or deleted.
- When an alarm fires, consume that one scheduled occurrence.
- Never automatically schedule another occurrence after dismissal.
- Handle changes to the configured alarm time correctly.
- Handle daylight-saving transitions sensibly.
- Integrate with Android's system next-alarm indicator where supported.

Do not use WorkManager, polling, background loops or ordinary timers to trigger alarms.

## 3. Alarm ringing behaviour

When an alarm fires:

1. Start playing the selected system alarm sound.
2. Loop the sound continuously.
3. Vibrate the device.
4. Display a full-screen alarm interface.
5. Show the alarm over the lock screen.
6. Allow the user to open the camera to scan the registered barcode.
7. Provide an emergency maths challenge as an alternative.

Use Android's appropriate alarm audio attributes and system alarm volume.

Use the Android system ringtone picker to select from installed alarm sounds.

Do not implement custom audio-file selection.

If the selected system sound becomes unavailable, fall back to the system default alarm sound.

Use the appropriate Android notification, full-screen intent, lock-screen and foreground-service mechanisms for the installed OS version.

Do not depend on the app's main activity already being open.

The alarm must continue sounding until an authorised dismissal method succeeds, subject to Android's unavoidable system-level limitations.

There must be no normal dismiss button and no snooze button.

## 4. Barcode and QR-code dismissal

Each alarm must have its **own individually registered barcode or QR code**.

### Registration

When creating or editing an alarm:

- Provide a button to scan a barcode using the camera.
- Accept ordinary retail product barcodes and QR codes.
- Support at least EAN-13, UPC-A, UPC-E, Code 128 and QR codes.
- Store the scanned value and its format locally.
- Display a simple confirmation that the code was registered.
- Allow the registered code to be replaced while the alarm is not ringing.
- Do not require printing or generating QR codes.

Use an offline barcode-scanning implementation.

### Dismissal

When an alarm is ringing:

- Show a large **Scan Barcode** button.
- Open the camera scanner.
- Compare the scanned barcode against the registered barcode for that specific alarm.
- Only accept a matching code.
- Handle equivalent UPC-A/EAN-13 representations consistently where appropriate.
- Reject incorrect codes without dismissing the alarm.

When the correct barcode is scanned:

1. Stop the alarm sound immediately.
2. Stop vibration.
3. Mark the alarm occurrence as dismissed.
4. Disable the alarm.
5. Close the ringing screen.
6. Return to the normal app interface.

Do not delete the saved alarm or its barcode.

## 5. Emergency dismissal: five-minute maths challenge

If the barcode is unavailable or the camera cannot scan it, the user must be able to dismiss the alarm using an emergency maths challenge.

Provide an **Emergency Maths Challenge** button on the ringing screen.

### Challenge rules

The user must satisfy BOTH conditions:

- At least five minutes must have elapsed since starting the challenge.
- At least 15 arithmetic questions must have been answered correctly.

Use basic arithmetic:

- Addition
- Subtraction
- Multiplication
- Division with whole-number answers

Prefer simple multiple-choice questions with four possible answers to minimise UI complexity.

Questions should require some thought but not be excessively difficult.

Generate questions randomly and avoid trivial repetition.

Incorrect answers do not count towards the 15 correct answers.

If the user finishes 15 questions before five minutes have elapsed, display the remaining time.

The alarm cannot be dismissed until both requirements have been satisfied.

### Sound during maths challenge

While the maths challenge is actively displayed:

- Continue playing the alarm sound.
- Reduce the app's alarm playback volume to approximately **20% of its normal playback level**.
- Do not permanently modify the Android system alarm volume.
- Do not change the volume of other applications.

If the user leaves the maths challenge without dismissing the alarm, restore normal alarm playback volume.

### Challenge persistence

- Starting the challenge records a monotonic start time.
- Rotating the screen, backgrounding the app or recreating the activity must not reset progress.
- The user must not be able to bypass the five-minute timer by reopening the challenge.
- Preserve the number of correctly answered questions during the active alarm.
- Allow switching back to barcode scanning without losing challenge progress.

Once the challenge is completed:

1. Stop the alarm sound and vibration.
2. Mark the alarm as dismissed.
3. Disable the alarm.
4. Close the alarm interface.

Do not add more complicated puzzles or gamification.

Normal Android device controls must remain functional. Do not attempt to trap the user in the application or bypass system security.

## 6. GrapheneOS compatibility and alarm reliability

Reliability is the most important part of this project.

The application must use supported Android mechanisms rather than attempting to circumvent GrapheneOS restrictions.

Implement the necessary handling for:

- Exact alarm scheduling permissions or capabilities.
- Alarm notifications.
- Full-screen alarm notifications and relevant permissions.
- Lock-screen display.
- Camera access.
- Alarm audio and vibration.
- Battery background restrictions.
- Process termination and activity recreation.
- Changes to permissions after alarms have been scheduled.

### Battery settings

I can manually configure GrapheneOS to allow unrestricted background battery usage.

Provide a simple settings shortcut or instructions explaining how to configure this.

Do not implement hacks to bypass Android battery management.

### Reboot behaviour

If restoring enabled alarms after a device restart is straightforward using standard Android APIs, implement it.

For example, use the appropriate boot broadcast receiver and reschedule alarms from persisted configuration.

If supporting alarms before the first device unlock requires complex or fragile workarounds, do not implement those workarounds in the MVP.

Prefer a clear documented limitation over complicated unreliable code.

Do not silently change the alarm's intended scheduled time after reboot.

### Reliability requirements

- Use `AlarmManager.setAlarmClock()` rather than approximate background scheduling.
- Handle missing permissions gracefully.
- Warn the user if an alarm cannot be scheduled.
- Never display an alarm as successfully enabled if scheduling has failed.
- Avoid duplicated alarm triggers.
- Ensure audio playback and alarm presentation are independent of the ordinary app screen lifecycle.
- Follow current Android restrictions for foreground services and full-screen intents.
- Ensure the alarm is properly stopped and cleaned up after dismissal.
- Handle unavailable system alarm sounds using a fallback.
- Handle camera failure by keeping the maths dismissal option accessible.

Do not claim that any Android application can guarantee alarm delivery if the phone is powered off, force-stopped or otherwise prevented from executing by the operating system.

## 7. Minimal user interface

Keep the interface extremely simple.

### Home screen

Show a list of saved alarms.

Each alarm should display:

- Time
- Enabled or disabled status
- A simple enable/disable switch
- Edit option
- Delete option

Provide:

- Add Alarm button
- Test Alarm button
- Settings button

### Alarm editor

Include:

- Time picker
- Register Barcode button
- System Alarm Sound picker
- Save button

Do not include recurring schedules, custom audio files, sleep tracking or other advanced features.

### Ringing screen

Display:

- Alarm time
- Large Scan Barcode button
- Emergency Maths Challenge button

### Maths screen

Display:

- Current arithmetic question
- Four possible answers
- Number of correct answers
- Remaining five-minute countdown
- Return to barcode scanner option

### Settings and diagnostics

Provide a small screen showing:

- Exact alarm access
- Notification permission
- Full-screen alarm permission
- Camera permission
- Battery settings shortcut
- Test Alarm button

Keep the design functional rather than decorative.

## 8. Testing

Implement a small but meaningful automated test suite.

Prioritise tests for:

1. Scheduling the next occurrence of an alarm.
2. Scheduling tomorrow when today's configured time has passed.
3. Disabling an alarm after successful dismissal.
4. Ensuring one-shot alarms do not automatically repeat.
5. Barcode matching and mismatching.
6. Equivalent barcode representations where supported.
7. Five-minute maths challenge timing.
8. Requiring at least 15 correct answers.
9. Preventing dismissal when only one maths requirement has been satisfied.
10. Avoiding duplicate scheduled alarms.

Use Kotlin unit tests and Android instrumentation tests where appropriate.

Keep scheduling logic separate enough from Android framework calls to test it easily.

Do not create an excessively complicated testing framework.

### Manual reliability testing

Provide instructions for testing on the actual Pixel 7 Pro:

- Alarm while the screen is unlocked.
- Alarm while the screen is locked.
- Alarm after the phone has been idle.
- Alarm after the app process has been terminated by Android.
- Alarm after a reboot, if supported.
- Barcode dismissal.
- Maths dismissal.
- Missing permissions.
- System sound fallback.

Distinguish normal process termination from a user force-stop, which has different Android behaviour.

Include a simple local diagnostic log for scheduling, triggering, dismissal and playback errors.

## 9. Deliverables

Produce:

1. A complete Android Studio project.
2. Kotlin source code.
3. Gradle configuration.
4. Required Android manifest declarations.
5. Minimal user interface.
6. Barcode scanning implementation.
7. Alarm scheduling and playback implementation.
8. Maths challenge implementation.
9. Basic automated tests.
10. A README explaining how to build and install the application on GrapheneOS.
11. A debug APK if the build environment permits.

Keep the code readable and maintainable.

Avoid overengineering, unnecessary abstractions, excessive dependencies or premature optimisation.

## 10. Development instructions

Before writing code, briefly inspect the available environment and determine:

- Which Android SDK and build tools are available.
- Which Android API requirements apply.
- Which offline barcode-scanning library is most suitable.
- Which permissions and manifest declarations are necessary.

Then proceed with implementation.

**Do not stop after producing a plan or architecture document. Actually implement the MVP.**

Build the project, run the available tests and fix any compilation or test failures.

If the environment cannot build an APK, explain exactly what is missing and provide instructions for building it locally.

Do not claim that the app has been tested on GrapheneOS unless it has actually been tested on a GrapheneOS device.

At the end, provide a concise summary of:

- What has been implemented.
- Whether the APK builds.
- Which tests pass.
- Which features still require testing on the physical phone.
- Any known Android or GrapheneOS limitations.

## Final instruction

**Keep this application as small and dependable as possible.**

It should do three things exceptionally well:

1. Ring at the scheduled time.
2. Stop when the correct physical barcode is scanned.
3. Provide a five-minute, 15-question maths challenge when scanning is impossible.

Everything else is secondary.

Do not introduce extra features without a clear reliability or usability justification.

Implement the MVP now.