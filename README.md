# thumb

A tiny Android remote for LG webOS TVs over Wi‑Fi. 21 KB, no dependencies, no ads, no tracking.

## Use

1. Install the APK (Android 5.0+).
2. Same Wi‑Fi as the TV → **Find** (or type the IP) → **Connect** → accept the prompt on the TV.
3. **Power on** needs "Turn on via Wi‑Fi" enabled on the TV (Settings › General › Devices) and one connection while the TV is on.

Phone volume buttons control the TV volume.

## Build

No Gradle. Needs JDK 17 and the Android SDK (build-tools 34, platform 34):

```sh
ANDROID_HOME=~/android-sdk ./build.sh   # -> lg-remote.apk
```

Talks to the TV's SSAP WebSocket API (port 3001 TLS, falling back to 3000), with a ~100-line WebSocket client in `MainActivity.java`.
