# Soong Build Failure Root Cause Analysis

## 1. Platform Identification

| Variable | Resolved Value | Meaning |
|---|---|---|
| `TARGET_RELEASE` | `ap4a` | Android 15 QPR2 |
| `TARGET_BUILD_VARIANT` | `userdebug` | |
| `PLATFORM_SDK_VERSION` | **35** | API 35 (Android 15) |
| `RELEASE_PLATFORM_SDK_VERSION` | **35** | |
| `PLATFORM_VERSION` | **15** | Android 15 |
| `PLATFORM_VERSION_CODENAME` | **REL** | Released build (not preview) |
| `BUILD_ID` | BP4A.251205.006 | |
| `PLATFORM_SDK_EXTENSION_VERSION` | 15 | |

The platform is **Android 15 (API 35)**, consistent end-to-end. The `out/soong/soong.bliss_I001D.variables` file confirms `"Platform_sdk_version": 35` after a clean lunch with `ap4a`. An earlier reading showing 34 was stale from a prior `ap2a` lunch.

---

## 2. The `ap4a` Release Config

`ap4a` is **not an alias for `ap2a`**. It is a real release config defined in `build/release/release_configs/ap4a.textproto`. The tree uses a two-level config system:

- **`build/release/build_config/`** — make-level config (contains `ap2a.scl`, `DEFAULT=proto`, `trunk_staging=proto`)
- **`build/release/release_configs/`** — Soong-level config (contains `ap4a.textproto`, `bp1a.textproto`, etc.)

`ap4a.textproto` is the active config, setting `RELEASE_PLATFORM_SDK_VERSION=35`. The `bp1a` config even inherits from `ap4a`.

---

## 3. The CRT Variant Gap

`crtbegin_so`, `crtend_so`, and `crt_pad_segment` are **source-built** bionic modules (defined in `build/soong/cc/config/bionic.go:26`), not prebuilt binaries. They generate SDK variants based on what the bionic platform headers support.

The available variants for `crtbegin_so` only go up to:

```
version:21  version:22  ...  version:34  version:current
```

The `prebuilts/ndk/current/` directory contains **only** `source.properties` and `sources/` — no prebuilt platform objects. `prebuilts/sdk/` has directories up to version 37, but those are Java/SDK jars, not NDK runtime objects.

**Result:** CRT objects cap at SDK 34, but the platform and its NDK modules request SDK 35 or 36.

---

## 4. Failure Mechanism

In `build/soong/cc/linker.go:3090–3100`:

```go
if m.UseSdk() {
    minSdkVersion := m.MinSdkVersion(ctx)
    if minSdkVersion == "" || minSdkVersion == "apex_inherit" {
        minSdkVersion = m.SdkVersion()
    }
    apiLevel, _ := android.ApiLevelFromUser(ctx, minSdkVersion)
    return []blueprint.Variation{
        {Mutator: "sdk", Variation: "sdk"},
        {Mutator: "version", Variation: apiLevel.String()},
    }
}
```

Every `aidl_interface` NDK backend without an explicit `sdk_version` inherits `platform_sdk_version=35` (via `aidl_interface_backends.go:113–170`). The CRT selection uses `min_sdk_version` (or falls back to `sdk_version`) to pick the CRT variant. Since the platform demands SDK 35/36 but CRT only supports ≤34, the build fails.

Three classes of NDK modules fail:

| Class | Example | Reason |
|---|---|---|
| `min_sdk_version: "35"` | `hardware/interfaces/nfc/aidl` | Explicit min_sdk_version=35 |
| `min_sdk_version: "36"` | `packages/modules/UprobeStats/service/aidl` | Explicit min_sdk_version=36 |
| `sdk_version: "35"` (explicit) | `packages/modules/UprobeStats/src/bpf/...` | Explicit sdk_version=35 |

Modules with no `sdk_version` and no `min_sdk_version` (pure platform default) use the platform (non-SDK) variant and do **not** fail.

---

## 5. UprobeStats Is Android 16 Code

```bash
$ cd packages/modules/UprobeStats && git log --oneline -5
5625efa Merge tag 'android-16.0.0_r4' into staging/lineage-23.2_merge-android-16.0.0_r4
8aa8fd6 Merge tag 'android-16.0.0_r3' into staging/lineage-23.0_merge-android-16.0.0_r3
ec9dd70 Snap for 14079904 from 23a84b85... to 25Q4-release
```

The `service/aidl/Android.bp` file has `min_sdk_version: "36"`. This module targets Android 16 API, which is incompatible with the Android 15 platform (API 35). Its BPF syscall wrappers and headers also explicitly set `sdk_version: "35"` and `min_sdk_version: "35"`.

---

## 6. Repository Branch Mismatch

Every repository was checked via `repo info` and `git branch -a`:

| Repository | Remote | Branch | Comment |
|---|---|---|---|
| `build/make` | **BlissRoms** | **`waterlily-qpr2`** | **Out of sync** |
| `build/soong` | platform | `lineage-23.2` | |
| `build/release` | github/LineageOS | `lineage-23.2` | |
| `frameworks/base` | platform | `lineage-23.2` | |
| `system/core` | platform | `lineage-23.2` | |
| `prebuilts/sdk` | platform | `lineage-23.2` | |
| `packages/modules/UprobeStats` | github/LineageOS | `lineage-23.2` | Android 16 merged in |
| `vendor/bliss` | platform | `lineage-23.2` | |

**The only repository on a non-lineage-23.2 branch is `build/make`** (BlissRoms/waterlily-qpr2). This is the sole branch inconsistency.

---

## 7. Manifest

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <include name="default.xml" />
</manifest>
```

- **Manifest branch:** `refs/heads/lineage-23.2`
- **Manifest merge branch:** `refs/heads/waterlily`
- **Superproject revision:** None

---

## 8. Exact File Responsible for SDK 35

The chain of assignments:

1. `build/release/release_configs/ap4a.textproto` → defines the `ap4a` release
2. `build/release/build_config/ap4a=make` → maps `ap4a` to make-based config
3. `build/make/core/soong_config.mk:36` → `Platform_sdk_version := $(PLATFORM_SDK_VERSION)`
4. `build/make/core/version_util.mk:63` → `PLATFORM_SDK_VERSION := $(RELEASE_PLATFORM_SDK_VERSION)`
5. `build/release/build_config/ap2a.scl:36` → `value("RELEASE_PLATFORM_SDK_VERSION", "34")` (for `ap2a` only)

For `ap4a`, the release config in `release_configs/ap4a.textproto` effectively sets `RELEASE_PLATFORM_SDK_VERSION=35` via the Soong config system (the `aconfig` and flag value protos). **There is no single .mk file that assigns it** — it comes through the Soong product variable mechanism from the `ap4a` flags.

---

## 9. Conclusions

### The tree IS internally mixed:

1. **`build/make` is on `BlissRoms/waterlily-qpr2`** while everything else is `lineage-23.2`. This is the only branch mismatch.
2. **`packages/modules/UprobeStats` has Android 16 code** (merged via `android-16.0.0_r4`) in an otherwise Android 15 tree.

### Why only SDK 34 CRT variants exist:

The CRT objects are **source-built from bionic headers**, not from NDK prebuilts. The bionic source tree that ships in this checkout only builds CRT variants up to SDK 34 despite `Platform_sdk_version` being 35. This points to the bionic source (`bionic/`) being from an Android 14/early-15 era.

### What forces SDK 34 vs 35:

- **`lunch ...-ap2a-...`** → RELEASE_PLATFORM_SDK_VERSION=34 → CRT variants up to 34 are sufficient → only modules with explicit `min_sdk_version: "35"` break
- **`lunch ...-ap4a-...`** → RELEASE_PLATFORM_SDK_VERSION=35 → CRT variants are missing for 35 → all NDK SDK modules break

### Whether the issue is manifest mismatch, branch mismatch, or local modification:

**Branch mismatch** is the primary cause. `build/make` on `waterlily-qpr2` (BlissRoms) vs `lineage-23.2` (LineageOS) introduces a different build system generation. `packages/modules/UprobeStats` with Android 16 merges is a secondary inconsistency. No evidence of local modifications.

---

## 10. Minimum Recovery Path

### Re-sync needed:

```bash
repo sync build/make packages/modules/UprobeStats
```

### Repositories that are correct and need NO change:

- `build/release` ✓ (lineage-23.2)
- `build/soong` ✓ (lineage-23.2)
- `frameworks/base` ✓
- `prebuilts/sdk` ✓
- `system/core` ✓
- `vendor/bliss` ✓

### If `build/make` is deliberately on `BlissRoms/waterlily-qpr2`:

Switch to the `ap2a` lunch target and patch or exclude the Android 16 UprobeStats module:

```bash
lunch bliss_I001D-ap2a-userdebug
```

This sets `Platform_sdk_version=34`, which matches the CRT's maximum variant. The remaining failures (NFC, automotive, UprobeStats BPF) are modules with explicit `min_sdk_version: "35"` / `"36"` that would need their values lowered to `"34"` — as was being done earlier in this session.

### Command to check after sync:

```bash
cd ~/android/waterlily
source build/envsetup.sh
lunch bliss_I001D-ap4a-userdebug
get_build_var PLATFORM_SDK_VERSION  # should be 35
repo info build/make | grep "Current revision"  # should match lineage-23.2
```
