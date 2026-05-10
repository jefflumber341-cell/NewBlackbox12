# Release Notes - NewBlackbox

## Version: Latest Build (2026-01-31)

---

### New Features

#### Camera Image / Video Injection — actually wired
Image and video injection (Settings → Camera Injection) now reaches cloned
apps. Previously the `CameraInjection` and `Camera2Injection` singletons
existed but no execution path ever invoked them, because:

- `CameraProxy`'s `@ProxyMethod` map was dead — `Camera`'s `setPreviewCallback*`
  entry-points are routed straight to native code without going through any
  `InvocationHandler`, so the framework's reflective hook layer never saw the
  call.
- `Camera2Injection.renderCurrentFrameInto()` had no caller.

**Fix:**
- New `CameraInjectionHook` patches each live `Camera` instance's
  package-private `mEventHandler` (a plain `Handler`, not native). Every
  `CAMERA_MSG_PREVIEW_FRAME` (`msg.what == 16`) gets its NV21 payload
  rewritten in-flight from `CameraInjection.getNv21Frame(w, h)` before the
  app's preview callback runs — covering `setPreviewCallback`,
  `setPreviewCallbackWithBuffer` and `setOneShotPreviewCallback` at once.
- New `Camera2InjectionHook` runs a low-frequency (~30 fps) renderer that
  pushes the current bitmap into registered Camera2 output `Surface`s via
  the existing `Camera2Injection.renderCurrentFrameInto()` API.
- Both installers are wired into the cloned-app process from
  `AppInstrumentation.callApplicationOnCreate(...)`. Idempotent and silent
  when injection is disabled.
- `CameraProxy.{SetPreviewCallback,SetPreviewCallbackWithBuffer,
  SetOneShotPreviewCallback}` now also call `CameraInjectionHook.attach(...)`
  so any reflective dispatch path that did reach them still installs the
  per-instance patch.

**Files Changed / Added:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/CameraInjectionHook.java` (new)
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/Camera2InjectionHook.java` (new)
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/CameraProxy.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java`

**Tech-stack / Permissions impact:** none. No new gradle dependency, no new
`AndroidManifest.xml` permission, no DI/state-management change (architecture
remains plain Java singletons under `Bcore`, identical to the existing
`MediaRecorderProxy`/`CameraProxy` style). The Settings UI (`SettingFragment`,
`BlackBoxLoader`) is untouched and the unidirectional path
*UI → BlackBoxLoader → CameraInjection on disk → cloned-process pickup* is
preserved.

---

#### VPN Network Mode Toggle
Added a new setting to choose between VPN and normal network mode for sandboxed apps.

- **Location:** Settings → Others → Use VPN Network
- **Default:** OFF (normal network mode)
- When enabled, traffic is routed through BlackBox's VPN service
- Requires app restart to take effect

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/BlackBoxLoader.kt`
- `app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt`
- `app/src/main/res/xml/setting.xml`
- `app/src/main/res/values/strings.xml`
- `Bcore/src/main/java/top/niunaijun/blackbox/app/configuration/ClientConfiguration.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java`

#### Device Information Logging
Added comprehensive device info header in logcat for easier debugging:
- Android version, SDK level, security patch
- Device manufacturer, brand, model, hardware
- Supported CPU/ABIs (32-bit and 64-bit)
- Memory info (heap usage)
- App version and package info
- Build fingerprint and timestamps

---

### Bug Fixes

#### VPN Permission Fix
**Problem:** VPN service failed to establish interface (`builder.establish()` returned null).

**Root Cause:** Android requires `VpnService.prepare()` to be called from an Activity before VPN can be established.

**Solution:** Added VPN permission request to `MainActivity.kt` on app launch.

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt`

---

#### Android 10 Black Screen Fix
**Problem:** Apps would show a black screen and timeout on Android 10 (API 29).

**Root Cause:** 
- `BRAttributionSource.getRealClass()` returns `null` on Android < 31
- `SystemProviderStub.invoke()` crashed calling `.getName()` on null class
- `ClassInvocationStub.injectHook()` crashed when `getWho()` returned null

**Solution:**
- Added null checks in `SystemProviderStub.java` for API version checks
- Added null check in `ClassInvocationStub.java` to skip hooks when services don't exist

**Files Changed:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/ClassInvocationStub.java`

---

### Removed Features

#### Xposed Framework Support
- Removed `BXposedManagerService` and related AIDL interfaces
- Removed "Install Xposed Module" UI and Settings entries
- Cleaned up Xposed-related flags and package checks

---

### Stability Improvements

#### Anti-Detection Native Hook Stability
- Removed `LOGD` calls from critical native hooks to prevent infinite recursion
- Fixed syntax errors in hook implementations
- Hooks now silently return `ENOENT` for blocked paths

---

### Known Issues

#### Oppo/ColorOS Thermal Stats Error
On Oppo/ColorOS devices, you may see errors like:
```
OppoThermalStats: PackageManager$NameNotFoundException: top.niunaijun.blackboxa:p0
```
**This is harmless** - it's an Oppo system bug where their thermal management incorrectly uses process names (with `:p0` suffix) instead of package names. The app works normally.

---

### Compatibility

| Android Version | Status |
|-----------------|--------|
| Android 10 (Q)  | ✅ Fixed |
| Android 11 (R)  | ✅ Supported |
| Android 12 (S)  | ✅ Supported |
| Android 13 (T)  | ✅ Supported |
| Android 14 (U)  | ✅ Supported |
| Android 15+     | ✅ Supported |
