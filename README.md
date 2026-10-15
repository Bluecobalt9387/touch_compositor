# Touch Compositor

Android multi-touch composition and macro playback without Accessibility `dispatchGesture()`.

The app binds a Shizuku UserService running as Android `shell` (or root). That service reads physical touch input from evdev, composes physical and macro pointers, then injects one unified `MotionEvent` stream back into Display 0. Wireless debugging setup belongs to Shizuku.

## Modules

- `app` — Android UI, floating overlay, macro/profile editing and runtime lifecycle.
- `core-adb` — legacy source retained for reference and existing local changes; excluded from the build and APK.
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

## Overlay input routing

Hold-repeat starts on physical DOWN without a long-press recognition delay, and stops on UP/CANCEL. Trigger editing selects or moves a trigger; it never starts a macro.

All tool overlay windows render with `FLAG_NOT_TOUCHABLE`. The shell runtime assigns each physical contact to a UI window or the underlying application at DOWN. UI contacts are delivered directly to that window's View hierarchy and excluded from application injection and recording. Macro/playback contacts never enter UI hit testing, including when their coordinates overlap the trigger that started them. Contacts keep their original destination when dragged outside a region; removing a UI window cancels its gesture and consumes the rest of that contact until lift. Each UI window accepts one gesture at a time; additional fingers on that window are consumed without generating duplicate clicks.

Window geometry and stacking order are published together. New windows remain transparent until their route is acknowledged; overlapping windows share an opacity budget to keep Android's touch-through restriction from blocking the underlying stream. A lost runtime connection stops macros and hides the tool windows until routing is restored. The scope is this tool's overlays, not other apps' or system windows.

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

1. Install Shizuku v13 or newer and start it using its own setup flow.
2. Open Touch Compositor and choose **授权并启动**, then grant its Shizuku request.
3. Grant the separate overlay permission and start the floating tools.

The app no longer pairs with ADB, stores an ADB key, discovers mDNS ports, or keeps an ADB transport alive. USB ADB is only needed for development/testing. On non-rooted devices, restart Shizuku after reboot. Device-specific shell/SELinux restrictions still apply to evdev and input injection.

A process-scoped, non-daemon UserService is shared by the activity and overlay. Shizuku owns privileged process startup and lifetime; closing an activity does not tear down an active overlay. The service has a small typed Binder interface for startup/status and retains the existing localhost touch protocol. The localhost protocol is not authenticated; it should be migrated to Binder or an authenticated channel before use with untrusted local apps.

Service startup binds the control port before grabbing input, and failures close the engine. Each launch extracts a private executable inode under `/data/local/tmp`, removing it on normal shutdown. The native helper's parent-death signal releases the hardware grab if the service is killed. Runtime restart waits for the old engine to release input. A per-UserService UUID is verified in the runtime greeting so restarting Shizuku or updating the APK cannot silently reuse another service instance.

The legacy `core-adb` directory is intentionally retained because it contained uncommitted fixes during migration. It is not included in Gradle settings or application dependencies. Existing macro/profile data is not migrated or deleted.

API reference: [Shizuku UserService guide](https://github.com/RikkaApps/Shizuku-API#userservice).

## Debugging coordinates

The debug build includes `CoordinateProbeActivity`, a simple full-screen touch target for checking injected Display 0 coordinates on real devices.
