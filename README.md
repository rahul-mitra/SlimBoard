# SlimBoard

A lightweight, private Android keyboard. Personal project. See [PLAN.md](PLAN.md) for the full plan.

## Requirements

- JDK 17 (installed: Microsoft OpenJDK 17 at `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`)
- Android SDK with platform 35, build-tools 35.0.0, platform-tools (installed at `%LOCALAPPDATA%\Android\Sdk`)
- `ANDROID_HOME` set (done, user level), or a `local.properties` file with `sdk.dir=...` (also present, gitignored)
- Android Studio (installed) for the profiler, layout inspector and logcat. It uses the same SDK folder.

### Machine-specific notes (this Windows laptop)

- **Java NIO pipes fail in the user's Temp folder.** Java 17 implements `Pipe`/`Selector` on Windows with Unix-domain
  sockets in `%TEMP%`, and this user's Temp folder rejects them ("Unable to establish loopback connection").
  Gradle needs them for its daemon. Fix in place: user environment variable
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\jtmp` (the folder must exist). Every JVM prints a one-line
  "Picked up JAVA_TOOL_OPTIONS" notice; that is expected. Android Studio's bundled JDK 25 is not affected.
- **Java cannot reach GitHub's release CDN** (`release-assets.githubusercontent.com`, TCP connect times out) even
  though curl and PowerShell can. Gradle distributions redirect there, so the wrapper cannot download new Gradle
  versions by itself. Workaround used for 8.11.1: download the zip with PowerShell and seed
  `%USERPROFILE%\.gradle\wrapper\dists\gradle-<ver>-bin\<hash>\` with the zip, the extracted folder and an
  empty `gradle-<ver>-bin.zip.ok` marker. Dependency downloads from Google and Maven Central work normally.
- **winget** needs `--source winget`; the Microsoft Store source fails with a certificate error on this network.
- The warning "SDK XML versions up to 3 but ... version 4" is harmless: the command-line tools are newer than AGP 8.10.

## Build

```bash
./gradlew assembleDebug
```

Release build (minified, R8). Signs with `keystore.properties` if present, otherwise the debug key:

```bash
./gradlew assembleRelease
```

**Back up `keystore/slimboard-release.jks` and `keystore.properties` somewhere safe.** Both are gitignored. Losing
them means a new signing key, and every phone would need an uninstall/reinstall and re-selecting the keyboard.

Debug builds use the package id `app.slimboard.debug`, release builds `app.slimboard`, so both can be installed
side by side without signing conflicts.

## Install and enable on a phone

1. Enable USB debugging on the phone, connect it, accept the prompt.
2. Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

3. Open the SlimBoard app on the phone. Tap "Open keyboard settings" and enable SlimBoard.
4. Tap "Choose keyboard" and pick SlimBoard. The test field on the same screen should now show it.

Or do steps 3 and 4 over adb (debug build id shown; release is `app.slimboard/app.slimboard.SlimBoardService`):

```bash
adb shell ime enable app.slimboard.debug/app.slimboard.SlimBoardService
adb shell ime set app.slimboard.debug/app.slimboard.SlimBoardService
```

**Reinstalling the APK makes Android fall back to the system keyboard.** Re-select SlimBoard after every
install. Never `am force-stop` the package right after selecting it: Samsung's One UI treats the dead IME as
gone and silently switches back to Samsung Keyboard. Verify which keyboard is really active with:

```bash
adb shell dumpsys input_method | findstr mCurId
```

## Regenerating the emoji list

`app/src/main/assets/emoji/emoji.txt` is generated from Unicode's `emoji-test.txt` (Unicode data files
license). To update for a new Unicode version:

```bash
powershell -File tools/gen-emoji.ps1 -Version 17.0
```

## Baseline measurements

After the keyboard is showing on a connected phone:

```bash
powershell -File tools/baseline.ps1
```

Prints APK size, show-to-first-draw timings from logcat, and the process's memory footprint.
Record the numbers in `docs/baseline.md` per device so later phases have something to compare against.
