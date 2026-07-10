# Android 16 (Bliss Waterlily) Reverse-Engineering & Migration Plan

## Context

- Device: ASUS ROG Phone II (I001D)
- Current: Android 14 Bliss (AP2A.240905.003)
- Target: Android 16 Bliss Waterlily (BP4A.251205.006)
- Official OTA analyzed: `Bliss-v19.6-I001D-OFFICIAL-vanilla-20260616.zip` (see `findings.android16.md`)
- Source blocker: six private `StudioKeys-Dumps` repos — maintainers refuse access
- Manufacturer never released past Android 11; all custom ROM work is community-maintained

## Strategy

Reverse-engineer the OTA to reconstruct missing source trees, leveraging the existing Android 14 device trees as the starting base.

## Phase A — Study VM Artifacts (before teardown)

1. Inventory critical reference files on VM that aren't in this repo
2. Pull OTA partition extracts, config diffs, VINTF manifests, fstab, `.rc` files, SELinux policy
3. Document file-by-file correspondence between A14 and A16 service/init/VINTF files

## Phase B — Save & Clean

1. Commit findings + plan + updated AGENTS.md to git
2. Add `.gcloud-ssh/` to `.gitignore` (exposed SSH keys)
3. Push to GitHub (only if user opts in)
4. Clean VM / reconstruct new instance with expanded disk + swap
5. Set up fresh Waterlily checkout

## Phase C — A16 Bring-Up

### 1. Device Trees (`device/asus/I001D`, `device/asus/sm8150-common`)
- Copy A14 source as base
- Bump VINTF manifest 8.0 → 9.0, target level 4 → 6
- Swap HIDL services for AIDL equivalents (documented in findings)
- Update SELinux 202404 → 202504
- Update first-stage fstab + encryption config
- Replace `AsusParts` with `ROGParts`
- Import overlays, permissions, sysconfig from OTA

### 2. `hardware/asus` (new A16 dependency)
- Not in A14 tree; infer from VINTF + service declarations in OTA
- Write equivalent source (~few hundred lines C++/HAL glue)

### 3. Kernel (`kernel/asus/I001D`)
- Base: public [OpenELA 4.14.357](https://github.com/OpenELA-LTS/kernel-lts)
- Apply extracted `.config` from OTA (812-line diff already documented)
- Port ASUS-specific patches from A14 kernel to new base
- Clang 21 toolchain (included with Waterlily manifest)

### 4. Vendor Blobs (`vendor/asus/*`)
- ~1,535 files already identical to A14 → use as-is
- ~801 changed files → extract from OTA vendor image
- Removed: `br_netfilter.ko`, `lcd.ko`, `msm-geni-ir.ko`

### 5. Build & Verify
- Full `blissify I001D` build
- Compare output against official OTA: kernel config, partition manifests, OTA metadata
- Clean-flash test on device (encryption format changed, no in-place upgrade)

## Timeline

Estimated **1–2 weeks** of focused work for a booting build.

## References

- OTA: `Bliss-v19.6-I001D-OFFICIAL-vanilla-20260616.zip`
- Manifest: `https://github.com/BlissRoms/stable_releases/tree/waterlily`
- AOSP tag: `android-16.0.0_r4`
- Full analysis: `findings.android16.md`
