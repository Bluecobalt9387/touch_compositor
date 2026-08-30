# touch_compositor

Android input automation research project focused on composable multi-touch rather than Accessibility `dispatchGesture()`.

## Modules

- `app`: UI, profiles, foreground service, overlay configuration.
- `core-adb`: Wireless ADB pairing/transport. Deliberately no arbitrary shell API.
- `core-input`: touch model, routing and compositor state.
- `shell-helper`: `app_process` helper intended to run as Android `shell` and own InputMonitor/InputManager access.

## Initial milestones

1. Validate `InputMonitor` under `uid=2000(shell)` on Huawei Android 12.
2. Implement typed App ↔ Shell Helper IPC.
3. Implement multi-pointer MotionEvent compositor and injection.
4. Add non-touchable overlay indicators and PASS/CONSUME regions.
5. Add DirectADB pairing/reconnect with mDNS discovery.

The automation layer comes after the input substrate is proven.
