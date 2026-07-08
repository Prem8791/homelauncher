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
