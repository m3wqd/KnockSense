# KnockSense 2.0 — hackathon MVP

Flutter UI + Kotlin microphone foreground service + LAN FastAPI/NumPy detector.

## Arch Linux: backend
```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```
Check `http://LAPTOP_IP:8000/api/health` from the phone browser. Find IP via `hostname -I` or `ip -4 addr`.

## Flutter Android project creation (Flutter SDK required)
```bash
flutter create --org com.example --project-name knocksense --platforms android mobile_app
cd mobile_app
flutter pub add permission_handler
```
Copy `mobile/lib/main.dart` over `mobile_app/lib/main.dart`.
Copy the `mobile/android/app/src/main/AndroidManifest.xml` over the generated manifest.
Copy both Kotlin files from `mobile/android/app/src/main/kotlin/com/example/knocksense/` to the same path in `mobile_app`.

**Check the generated `android/app/build.gradle.kts`:** `namespace = "com.example.knocksense"`, `applicationId = "com.example.knocksense"`, `minSdk = 26` or higher, and Flutter-provided compile/target SDK values. Match this package name to `package com.example.knocksense` in both Kotlin files. If you generated different IDs, update Kotlin package and AndroidManifest activity/service names accordingly.

Connect a real Android phone and enable USB debugging:
```bash
adb devices
flutter doctor
flutter run
# or: fvm flutter run
```
`flutter build apk --release` creates `build/app/outputs/flutter-apk/app-release.apk`.

## Usage
1. Connect laptop and phone to the same LAN and enter `http://LAPTOP_IP:8000`.
2. Grant microphone and notifications permission; start monitoring with the app visible.
3. Lock screen. Android foreground service should continue capturing audio, but OS battery restrictions/network outages may interrupt it.
4. Knock three times within ~1.5 seconds and test alert behavior.
5. Calibration: press 'Калибровать дверь' and knock **3 times during each of three 2-second samples**. UI status shows accepted samples. Missed series require retrying. Profiles persist only in server RAM until restart.

## Limitations
- Experimental heuristics; NOT a guaranteed assistive/safety device.
- Experimental fire tone detection cannot replace certified fire alarms or life-safety devices.
- Audio WAV chunks are sent over unencrypted LAN HTTP for demo; don't expose port 8000 to the public internet. Production requires TLS, authentication, encryption and explicit consent.
- App status/event counters persist only while Flutter screen process runs. Notifications still work without visible Flutter screen while service survives.
- Only one HTTP request at a time; while the server is slow, some windows may be discarded. Android process kill can terminate the service.
- No camera flash in this code. Vibration and notification are implemented.
- Backend has no persistent DB; calibration resets on server restart.
- Service intentionally does not restart itself when killed because modern Android restricts background microphone starts.
