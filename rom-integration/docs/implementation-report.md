# Implementation Report: First-Boot Fixes

Commit: `fb0061b` — pushed to `origin/main`
Branch: `main` (tracks `origin/main`)

---

## Overview

Six files changed across two concerns:

1. **Diagnostic self-verification** — the app now fingerprints itself and its permission state at startup, and the reflection recents backend logs intermediate failure modes instead of silently returning empty.
2. **Denial storm suppression** — CPU polling backs off after repeated failures, thermal sysfs fallback removed (was the source of a pending second AVC denial), and timer lifecycle guards prevent duplicate registrations.

---

## File-by-File Changes

### 1. `Android.bp`

**What**: Added `version_code` and `version_name` to the Soong module.

```bp
version_code: 1,
version_name: "1.0.0",
```

**Why**: The AOSP build generates `BuildConfig` from these fields. Without them, `BuildConfig.VERSION_CODE` defaults to 1 and `VERSION_NAME` to `""`, making the runtime fingerprint log useless.

**Verification** (on VM):
```
adb logcat -d -s HomeLauncher
# Expect: versionCode=1 versionName=1.0.0
```

---

### 2. `MainActivity.kt`

**What**: Three additions in `onCreate()` + lifecycle guard in `onResume`/`onPause` + removed redundant filter.

#### a) Build fingerprint (lines 98-100)
```kotlin
Log.i("HomeLauncher", "versionCode=${BuildConfig.VERSION_CODE} versionName=${BuildConfig.VERSION_NAME}")
Log.i("HomeLauncher", "package=$packageName uid=${android.os.Process.myUid()}")
Log.i("HomeLauncher", "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
```

**Verification**:
```
adb logcat -d -s HomeLauncher
# Expect: versionCode=1 versionName=1.0.0
# Expect: package=com.home.launcher uid=...
# Expect: sdk=34 release=...
```

#### b) Permission self-check (lines 101-110, 128-137)
```kotlin
logPrivilegedPermission("REAL_GET_TASKS")
logPrivilegedPermission("MANAGE_ACTIVITY_TASKS")
// ... all 10 privileged permissions
```
Logs each as `GRANTED`, `DENIED`, or `UNKNOWN` with tag `PermCheck`.

**Verification**:
```
adb logcat -d -s PermCheck
# Expect every privileged permission to show GRANTED
```

#### c) Timer lifecycle guard (lines 69, 74, 84, 143-144, 148, 153-154)
- `onResume`: calls `handler.removeCallbacks(...)` before posting each timer.
- Runnable bodies check `pollingActive` flag before re-posting.
- `onPause`: sets `pollingActive = false` before stopping.

**Why**: Prevents duplicate timer accumulation if `onResume` fires while a previous timer chain is still active.

#### d) Removed redundant self-filter (line 260)
Removed `.filter { it.packageName != packageName }` from `refreshRecentTasks()`. The manifest's `android:excludeFromRecents="true"` already prevents the launcher from appearing in its own recents query.

---

### 3. `SystemStatsProvider.kt`

**What**: CPU failure backoff + thermal fallback removal.

#### a) CPU failure backoff (lines 29-33, 79-82, 119-125)
```kotlin
private var consecutiveCpuDenials: Int = 0
private const val CPU_FAILURE_BACKOFF = 10

fun shouldThrottleCpuPoll(): Boolean = consecutiveCpuDenials >= CPU_FAILURE_BACKOFF
```

When `getCpuUsage()` catches an exception (e.g. SELinux denial), `consecutiveCpuDenials` increments. After 10 consecutive failures, subsequent calls return -1 without attempting the read. A successful read resets the counter to 0.

**Verification** (on device without SELinux fix):
```
adb logcat -d -s SystemStats
# Expect after ~20s: "CPU read failed 10 times; throttling"
# CPU stat bar shows "⏸" instead of "⚙️ 0%"
```

#### b) Thermal fallback removed (lines 128-132)
Removed the `/sys/class/thermal/thermal_zone0/temp` fallback in `getTemperature()`. Now reads temperature exclusively from `BatteryManager.EXTRA_TEMPERATURE`, which works on all Android devices and does not trigger SELinux denials.

**Verification**:
```
adb logcat -d | grep sysfs_thermal
# Expect: no sysfs_thermal AVC denials from com.home.launcher
```

---

### 4. `SystemStatsBar.kt`

**What**: Two changes to integrate the CPU backoff.

#### a) Throttled interval (line 32)
```kotlin
handler.postDelayed(refreshRunnable, if (shouldThrottle()) 10000 else 2000)
```
When the CPU provider signals throttle, the poll interval extends from 2s to 10s.

#### b) Throttled display (line 48)
```kotlin
val cpuText = if (stats.cpuPercent < 0) "⏸" else "⚙️ ${stats.cpuPercent}%"
```
Shows a pause icon instead of "⚙️ 0%" when throttled, making the state visible in the UI.

#### c) `shouldThrottle()` delegate (line 54)
Exposes `provider.shouldThrottleCpuPoll()` to the refresh runnable.

---

### 5. `ReflectionRecentTasksBackend.kt`

**What**: Rewrote `getRecentTasks()` to use `HiddenApiBridge.call()` and log every intermediate step.

**Before** — silent failure:
```kotlin
val raw = HiddenApiBridge.invoke(service, "getRecentTasks", maxNum, ...)
val taskList = unwrapParceledList(raw) ?: return emptyList<RecentTask>()
return parseRecentTasks(taskList)
```

**After** — self-diagnosing:
```kotlin
if (service == null) { Log.w(...); return emptyList() }
val result = HiddenApiBridge.call(service, "getRecentTasks", maxNum, ...)
if (!result.invoked) { Log.w(...); return emptyList() }
Log.d(TAG, "raw value type=${result.value?.javaClass?.name}")
val taskList = unwrapParceledList(result.value)
if (taskList == null) { Log.w(...); return emptyList() }
Log.d(TAG, "unwrapped list size=${taskList.size}")
return parseRecentTasks(taskList)
```

This distinguishes three distinct failure modes in the logcat:

| Log | Meaning |
|-----|---------|
| `iAtm not resolved` | `ActivityTaskManager.getService()` failed |
| `reflection call failed to invoke` | Method not found or hidden API blocked |
| `unwrapParceledList returned null` | Method returned unexpected type |
| `unwrapped list size=N` | Method succeeded, N raw tasks returned |

**Verification**:
```
adb logcat -d -s ReflectionTasksBackend
# Expect one of the above diagnostic lines
```

---

### 6. `rom-integration/docs/communication.md`

New file. Contains the original first-boot logcat analysis (SELinux proc_stat, reflection recents 0/0) plus the supplemental architecture gap analysis. The "Fixes Applied" table on lines 155-170 maps each finding to the commit that addresses it.

---

## Items Not Yet Implemented

| Gap | Reason | Tracking |
|-----|--------|----------|
| Background thread for I/O/reflection | Requires coroutines or thread-switch refactor beyond this pass | `communication.md` §5 |
| TaskOrganizer backend | Stub only; blocked on `mRecentsComponent` verification | `communication.md` Issue 2 |
| SELinux proc_stat rule integration | ROM-side work; patch exists but must be baked into OTA | `communication.md` Issue 1 |

---

## Logcat Quick-Reference

To verify all fixes from a single capture:

```sh
adb logcat -c && adb shell am start -n com.home.launcher/.MainActivity && sleep 10 && adb logcat -d \
  -s HomeLauncher PermCheck ReflectionTasksBackend SystemStats NotifDiag
```

Expected output pattern:

```
HomeLauncher: versionCode=1 versionName=1.0.0  package=com.home.launcher uid=...  sdk=34
PermCheck: android.permission.REAL_GET_TASKS -> GRANTED
PermCheck: android.permission.MANAGE_ACTIVITY_TASKS -> GRANTED
... (all 10 granted)
ReflectionTasksBackend: getRecentTasks: ... (invoked ok / list size=... or diagnostic)
SystemStats: CPU read failed 10 times; throttling  (if SELinux not yet fixed)
```
