# Build, Sign & Install

## Build
```
.\gradlew assembleDebug
```

## AOSP / Soong Kotlin Compatibility Checks

When changing Kotlin source that may later be built inside Android 14 AOSP/Soong:

- Do not rely on Gradle-only Kotlin inference for Android platform generic APIs.
- Use explicit generic types for every `findViewById` call, for example `findViewById<TextView>(R.id.title)` or `itemView.findViewById<ImageView>(R.id.icon)`.
- After edits, scan for untyped view lookups:
```
rg --pcre2 "findViewById\((?!<)" app hidden-api-probe
```
- Treat any match as a source compatibility issue unless it is Java code or is otherwise proven safe for the AOSP Kotlin compiler.
- Prefer type-safe calls over unsafe casts. Do not change application behavior while fixing Soong/Kotlin compatibility issues.
- Run a clean local build after compatibility fixes:
```
.\gradlew clean assembleDebug
```

## Sign with platform keys
```
apksigner sign --key platform.pk8 --cert platform.x509.pem --out app\build\outputs\apk\debug\app-platform-signed.apk app\build\outputs\apk\debug\app-debug.apk
```
Keys are in the project root: `platform.pk8` and `platform.x509.pem`.

## Fresh install (uninstall first)
```
adb uninstall com.home.launcher
adb install app\build\outputs\apk\debug\app-platform-signed.apk
```

## Incremental install (replace)
```
adb install -r app\build\outputs\apk\debug\app-platform-signed.apk
```

## Restart (after install)
```
adb shell am force-stop com.home.launcher && adb shell am start -n com.home.launcher/.MainActivity
```

## Combined quick cycle (incremental)
```
.\gradlew assembleDebug && apksigner sign --key platform.pk8 --cert platform.x509.pem --out app\build\outputs\apk\debug\app-platform-signed.apk app\build\outputs\apk\debug\app-debug.apk && adb install -r app\build\outputs\apk\debug\app-platform-signed.apk && adb shell am force-stop com.home.launcher && adb shell am start -n com.home.launcher/.MainActivity
```

## ADB path
`C:\Users\Steno\AppData\Local\Android\Sdk\platform-tools\adb.exe`

## Apksigner path
`C:\Users\Steno\AppData\Local\Android\Sdk\build-tools\36.1.0\apksigner.bat`

# Change Propagation Workflow

Before starting any new code or resource change, ask the user which propagation path to use:

- Local only: edit and verify in `D:\AndroidProjects\home`.
- Local + VM: edit locally, verify, then sync the changed source files to the cloud VM AOSP tree.
- Local + VM + device: edit locally, sync to VM, build `HomeLauncher` with Soong, download the platform-signed APK, install it with `adb install -r`, and restart the launcher without flashing.
- GitHub too: after the user explicitly approves commit/push, commit only the intended files and push to `origin/main`.

Do not assume every change should be pushed to GitHub or installed on the device. Ask first.

## Current VM / No-Flash Test Path

Cloud VM:
```
project: customrom-501702
zone: us-south1-b
instance: instance-20260707-045005
user: premanandal1978
ROM root: /home/premanandal1978/android/bliss-I001D
HomeLauncher path: /home/premanandal1978/android/bliss-I001D/packages/apps/HomeLauncher
```

Local `gcloud` path:
```
C:\Users\home\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd
```

Recommended no-flash loop for platform/AOSP-sensitive launcher changes:

1. Edit files locally.
2. Run the Kotlin compatibility scan:
```
rg --pcre2 "findViewById\((?!<)" app hidden-api-probe
```
If `rg` is unavailable, use an equivalent PowerShell `Select-String` scan.
3. Run a local build:
```
.\gradlew assembleDebug
```
For compatibility-sensitive Kotlin changes, run:
```
.\gradlew clean assembleDebug
```
4. Sync only the changed source/resource files to the matching paths under:
```
/home/premanandal1978/android/bliss-I001D/packages/apps/HomeLauncher
```
5. On the VM, build the AOSP platform-signed module:
```
cd ~/android/bliss-I001D
source build/envsetup.sh
lunch bliss_I001D-ap2a-userdebug
m HomeLauncher
```
6. Download the rebuilt platform APK:
```
gcloud compute scp --project customrom-501702 --zone us-south1-b premanandal1978@instance-20260707-045005:/home/premanandal1978/android/bliss-I001D/out/target/product/I001D/system/priv-app/HomeLauncher/HomeLauncher.apk .\HomeLauncher-system.apk
```
7. Install over the existing system app without flashing:
```
adb install -r .\HomeLauncher-system.apk
adb shell am force-stop com.home.launcher
adb shell am start -n com.home.launcher/.MainActivity
```
8. Check for immediate crashes:
```
adb logcat -d -t 250 | findstr /i "AndroidRuntime FATAL HomeLauncher"
```

## GitHub Commit / Push Policy

Only commit and push when the user explicitly asks.

Before committing:

- Review `git status --short`.
- Exclude unrelated/generated files unless the user explicitly wants them.
- Prefer committing only intentional launcher source/resource changes.
- Verify VM status separately if the VM tree was synced.

GitHub remote:
```
origin https://github.com/Prem8791/homelauncher.git
```
