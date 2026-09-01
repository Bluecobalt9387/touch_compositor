# Touch Compositor

Android multi-touch composition and macro playback without Accessibility `dispatchGesture()`.

The app pairs with the device's Wireless ADB service, starts a small `app_process` runtime as Android `shell`, reads physical touch input from evdev, composes physical and macro pointers, then injects one unified `MotionEvent` stream back into Display 0.

## Modules

- `app` — Android UI, floating overlay, macro/profile editing and runtime lifecycle.
- `core-adb` — Wireless ADB pairing, mDNS discovery and persistent transport.
- `core-input` — touch frames, recording/playback, routing and coordinate model.
- `shell-helper` — shell-side evdev reader, compositor, trigger routing and input injection.
- `hidden-api-stubs` — compile-time Android hidden API stubs.
- `native-tools` — native evdev helper sources and build script.

## Input model

All runtime geometry uses Display 0 logical-screen coordinates.

- Physical evdev coordinates are normalized from the device's real axis min/max range.
- Rotation is applied once when converting raw touch to current screen coordinates.
- Recordings persist normalized full-screen coordinates and replay into the current display geometry.
- Macro touch and physical touch share the same compositor, so macro input does not need to cancel the user's active pointers.
- Trigger regions are handled by the shell runtime rather than by re-injecting touches into the overlay window.

## Build

Requirements: JDK 17+ and Android SDK 35.

```bash
./gradlew test
./gradlew assembleDebug
```

Release builds produced by Gradle are unsigned unless a signing configuration is supplied separately:

```bash
./gradlew assembleRelease
```

Local SDK paths belong in `local.properties` and are not committed.

## Runtime

On first use, enable Android Developer Options and Wireless debugging, then pair from the app using the system pairing code. The app persists its own ADB identity and can reconnect through mDNS discovery on later launches.

The shell helper exposes only the typed touch-runtime protocol used by the app; `core-adb` deliberately does not provide an arbitrary shell-command API.

## Debugging coordinates

The debug build includes `CoordinateProbeActivity`, a simple full-screen touch target for checking injected Display 0 coordinates on real devices.
