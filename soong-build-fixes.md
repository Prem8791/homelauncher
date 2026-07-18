# Soong Build Fixes for HomeLauncher

**Target:** `bliss_I001D-ap4a-userdebug` (Android 15 / API 35)
**ROM root:** `/home/premanandal1978/android/waterlily`
**Last updated:** 2026-07-18

## Root Cause

The ROM tree has Android 16 (bp2a) source code merged in but targets Android 15 (ap4a) build config. This causes toolchain version mismatches:
- Java 21 features (switch pattern matching) compiled with `-source 17`
- Kotlin 2.0 annotations (`@ConsistentCopyVisibility`) with language version 1.9
- Kotlin 2.0 APIs in `external/kotlinx.serialization` with language version 1.9

## Changes Applied

### 1. Enable Java 21 target for ap4a release

**File:** `build/release/flag_values/ap4a/RELEASE_TARGET_JAVA_21.textproto`
**Status:** Created
**Content:**
```
name: "RELEASE_TARGET_JAVA_21"
value {
  bool_value: true
}
```
**Purpose:** Sets Java 21 as the default source/target version for `ap4a` builds.
Without this, `-source 17` is used, which fails on switch pattern matching in
`DevicePolicyManager.java`, `ThemeSettings.java`, and other Android 16 code.
**Verified:** JDK 21 prebuilt exists at `prebuilts/jdk/jdk21/`.

### 2. Enable Kotlin 2.0 language version for ap4a release

**File:** `build/release/flag_values/ap4a/RELEASE_KOTLIN_LANG_VERSION.textproto`
**Status:** Created
**Content:**
```
name: "RELEASE_KOTLIN_LANG_VERSION"
value: {
  string_value: "2"
}
```
**Purpose:** Sets Kotlin language version to 2.0 for all modules.
Without this, `-language-version 1.9` is used, which fails on:
- `@ConsistentCopyVisibility` in `tools/metalava/metalava-model/SourceFile.kt`
- `@ConsistentCopyVisibility` in `tools/metalava/metalava/ApiVersion.kt`
- Kotlin 2.0 APIs in `external/kotlinx.serialization/` (overload resolution ambiguity)
**Verified:** Kotlin compiler is v2.2.0 (`external/kotlinc/`), so language version 2 is supported.

### 3. Kotlin default language version in Soong

**File:** `build/soong/java/base.go`
**Status:** Already applied in session `ses_08c6`
**Change (line 1407):** `kotlin_default_lang_version := "1.9"` → `kotlin_default_lang_version := "2"`
**Purpose:** Fallback default when `RELEASE_KOTLIN_LANG_VERSION` is not set.
**Note:** Soong Go source changes require recompilation of the `soong_build` binary
(either `rm -rf out/soong` or `rm -rf out/soong out/host/linux-x86/bin/soong_build`).

### 4. Fix NDK CRT version in UprobeStats

**File:** `packages/modules/UprobeStats/service/aidl/Android.bp`
**Status:** Already applied in session `ses_08c6`
**Change (line 30):** `min_sdk_version: "36"` → `min_sdk_version: "current"`
**Purpose:** Android 16 prebuilt reference was incompatible with API 35 build.
NDK CRT objects (crtbegin_so, crtend_so) only have SDK variants up to 35 in ap4a.

### 5. Fix NDK CRT version in automotive power HAL

**File:** `frameworks/hardware/interfaces/automotive/power/aidl/Android.bp`
**Status:** Already applied in session `ses_08c6`
**Change (lines 33, 70, 109, 118, 126):** `min_sdk_version: "36"` → `"35"` (NDK/rust targets)
**Purpose:** Same as above — `"36"` CRT variants don't exist in ap4a.

### 6. Fix treble sepolicy tests for 202404

**File:** `system/sepolicy/treble_sepolicy_tests_for_release/Android.bp`
**Status:** Already applied in session `ses_08c6`
**Change:** Added `select` blocks to skip 202404 test modules when
`PLATFORM_SEPOLICY_VERSION` matches `"202404"`.
**Purpose:** Prevents duplicate/broken test module definitions from Android 16 merge.

### 7. Remove incompatible CrashRecovery prebuilt

**File:** `prebuilts/module_sdk/CrashRecovery/current/Android.bp`
**Status:** Deleted in session `ses_08c6`
**Purpose:** Android 16 prebuilt CrashRecovery module was incompatible with
API 35. The module wasn't needed for HomeLauncher build.

### 8. BoardConfig.mk fixes

**File:** `device/asus/I001D/BoardConfig.mk`
**Status:** Already applied in session `ses_08c6`
**Additions:**
```
BOARD_GENFS_LABELS_VERSION := 202504
BUILD_BROKEN_MISSING_REQUIRED_MODULES := true
```
**Purpose:**
- `BOARD_GENFS_LABELS_VERSION` — sets genfs labels to match merged sepolicy
- `BUILD_BROKEN_MISSING_REQUIRED_MODULES` — suppresses errors from missing
  required modules introduced by the Android 16 merge

## Build Commands

### First build (full clean bootstrap):
```bash
cd ~/android/waterlily
rm -rf out/soong
source build/envsetup.sh
lunch bliss_I001D-ap4a-userdebug
m HomeLauncher 2>&1 | tee build.log
```

### Incremental builds after fixes:
```bash
cd ~/android/waterlily
source build/envsetup.sh
lunch bliss_I001D-ap4a-userdebug
m HomeLauncher 2>&1 | tee build.log
```

### To continue past failures and capture all errors:
```bash
m -k HomeLauncher 2>&1 | tee build.log
## then: grep -E 'error:|FAILED:' build.log | sort -u
```

### To download and install after successful build:
```bash
# Download from VM:
gcloud compute scp --project customrom-501702 --zone us-south1-a \
  premanandal1978@instance-20260710-230647:/home/premanandal1978/android/waterlily/out/target/product/I001D/system/priv-app/HomeLauncher/HomeLauncher.apk \
  ./HomeLauncher-system.apk

# Install on device:
adb install -r ./HomeLauncher-system.apk
adb shell am force-stop com.home.launcher
adb shell am start -n com.home.launcher/.MainActivity
```

## Troubleshooting

### Out/.lock contention (stale lock from aborted build)
```bash
rm -f out/.lock
```

### Soong binary needs recompilation (Go source changes)
```bash
rm -rf out/soong
```

### Check which Java version build is using
```bash
grep -r '\-source\|JAVA_VERSION\|java_version' out/soong/build.ninja | head -5
```

### Check which Kotlin language version build is using
```bash
grep 'language-version' out/soong/build.ninja | head -5
```

## VM Instance Details

- **Instance:** `instance-20260710-230647`
- **Project:** `customrom-501702`
- **Zone:** `us-south1-a`
- **User:** `premanandal1978`
- **ROM root:** `/home/premanandal1978/android/waterlily`
- **HomeLauncher path:** `/home/premanandal1978/android/waterlily/packages/apps/HomeLauncher`
