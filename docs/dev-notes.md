# Developer notes

Things that are useful when working on SlimBoard but do not belong on the front page.

## Project shape

- The keyboard is one Canvas-drawn `View` (`ui/keyboard/KeyboardView`). Keys are data from JSON layouts in
  `assets/layouts/`, not child views. Popups draw into a transparent headroom strip at the top of the view;
  the service tells the system where the real keyboard starts in `onComputeInsets`.
- `ui/InputViewContainer` stacks the toolbar, the panels (clipboard, emoji, editing) and the keyboard, and
  handles one-handed mode.
- `SlimBoardService` owns everything that talks to the app being typed into: composing words, suggestions,
  autocorrect and its revert, shortcuts, undo history, clipboard capture and image paste, emoji search.
- `text/Dictionary` is a byte trie built on the phone from `assets/dict/en.txt` and cached under
  `files/dict/`. The cache name includes a hash of the asset, so a regenerated list invalidates it.
- Only the settings screen uses Compose. Nothing from Compose is loaded while typing.

## Testing on a phone over adb

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable app.slimboard.debug/app.slimboard.SlimBoardService
adb shell ime set app.slimboard.debug/app.slimboard.SlimBoardService
adb shell dumpsys input_method | findstr mCurId
```

- Reinstalling makes Android fall back to the system keyboard; re-select after every install.
- Do not `am force-stop` the package right after selecting it. One UI treats the dead IME as gone and quietly
  switches back to Samsung Keyboard, so your test types into the wrong keyboard.
- `adb shell uiautomator dump --windows /sdcard/w.xml` includes the IME window, which is how to check the
  TalkBack node names without turning TalkBack on.
- Release builds install as `app.slimboard` with the id `app.slimboard/.SlimBoardService`.

## Regenerating assets

```bash
powershell -File tools/gen-emoji.ps1 -Version 17.0
powershell -File tools/gen-dict.ps1 -Max 70000
```

Both download their sources into `%TEMP%` on first run.

## Releasing

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, then:

```bash
./gradlew assembleRelease
git tag -a vX.Y.Z -m "SlimBoard X.Y.Z"
git push origin vX.Y.Z
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk --title "SlimBoard X.Y.Z" --notes-file <notes>
```

Every release must be signed with the same key (`keystore/slimboard-release.jks`, gitignored) or phones will
refuse it as an update. Back that file and `keystore.properties` up.

## Windows build quirks seen on the original development machine

- Java 17 implements NIO pipes on Windows with Unix-domain sockets in `%TEMP%`. On one machine the Temp folder
  rejected them ("Unable to establish loopback connection") and every Gradle daemon died. Fix: a user
  environment variable `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\jtmp` (the folder must exist). Every JVM
  then prints a one-line "Picked up JAVA_TOOL_OPTIONS" notice, which is harmless. Android Studio's bundled JDK
  is not affected.
- On the same machine Java alone could not reach GitHub's release CDN, where Gradle distributions live, while
  PowerShell and curl could. Workaround: download the Gradle zip with PowerShell and seed
  `%USERPROFILE%\.gradle\wrapper\dists\gradle-<ver>-bin\<hash>\` with the zip, the extracted folder and an empty
  `gradle-<ver>-bin.zip.ok` marker.
- `winget` needed `--source winget` because the Microsoft Store source failed a certificate check on that network.
- The warning "SDK XML versions up to 3 but ... version 4" only means the command-line tools are newer than the
  Android Gradle Plugin. It is harmless.
