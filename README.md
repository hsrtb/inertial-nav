# nav

Inertial-navigation project, v1: a high-fidelity Android sensor logger.
There is no companion desktop tooling yet — everything is driven over adb.

## Modules

- `:core` — plain Java library, zero Android dependencies. This is where
  the INS math (mechanization, filtering, calibration) will be hand-written
  in Java, independent of the app/UI layer.
- `:app` — Kotlin + Jetpack Compose Android app. Runs a foreground service
  (`RecordingService`) that logs raw IMU + GNSS samples to a CSV file.

## Workflow (adb only, no device attached during development)

Build the debug APK:

```
./gradlew :app:assembleDebug
```

Install it on a connected device:

```
./gradlew :app:installDebug
```

Tail the recorder's log output:

```
adb logcat -s NavRecorder
```

Pull recorded CSV sessions off the device:

```
adb pull /sdcard/Android/data/com.nibawr.nav/files/recordings/
```

## CSV format

See the header comment block written at the top of every recording file
(`# nav-recorder v1 ...`) for the exact column layout — it documents the
device, session start time, which sensors were registered, and the IMU/GPS
row formats inline.
