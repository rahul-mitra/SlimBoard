# SlimBoard

A small, fast Android keyboard that keeps everything on your phone.

I built it because Gboard and Samsung Keyboard had grown into 100 MB apps full of things I never use,
and I wanted the handful of features I actually rely on in something that starts instantly and asks for
nothing. SlimBoard is 1.6 MB, has no permissions at all, and never touches the network.

## What it does

- **Typing that feels right**: QWERTY with two symbol pages, a number pad and a phone pad. Long-press for
  accents and symbols. Optional number row. Swipe the spacebar to move the cursor, swipe backspace to delete
  a word, double-space for a period. Auto-capitalisation that follows the app's own rules.
- **Suggestions and autocorrect**: a 70,000-word English dictionary, corrections that understand swapped
  letters, and a personal dictionary that learns words you type twice. Autocorrect is off until you turn it
  on, and one backspace undoes any correction.
- **Clipboard history**: text and images, pin what you want to keep, everything else auto-clears after a time
  you choose. Copied text shows up as a chip for a minute so you can paste it with one tap.
- **Emoji**: categories, recents, skin tones, and search by name straight from the keyboard.
- **Text editing panel**: cursor keys, selection, select all, cut, copy, paste, home, end, undo and redo.
- **One-handed mode**, text shortcuts that expand when you type them, a per-app "don't learn here" list,
  and backup and restore of your settings and learned words to a file.
- **Looks**: light, dark, follows-system, or Material You colours from your wallpaper. Adjustable height and
  bottom padding. Works with TalkBack.

Not included, on purpose: voice typing, GIFs, stickers, translation, AI rewriting, cloud sync. Those are the
reasons the big keyboards are big.

## Install

1. Download the latest `SlimBoard-x.y.z.apk` from the [Releases](https://github.com/rahul-mitra/SlimBoard/releases) page onto your phone.
2. Open the downloaded file. Android will ask you to allow installs from that app (your browser or file manager) the first time; allow it and tap Install.
3. Open SlimBoard. It shows two buttons: **Open keyboard settings** to enable SlimBoard, and **Choose keyboard** to switch to it.

That's all. No developer mode, no USB, no computer needed.

Requires Android 8.0 or newer. Tested on a Galaxy S24 Ultra running Android 16.

## Privacy

There is no `INTERNET` permission in the manifest, so the app cannot send anything anywhere. Learned words,
shortcuts and clipboard history are stored in the app's private folder and are gone when you uninstall.
Password fields never show suggestions and never learn. Fields that ask for no personalised learning, such as
Chrome's incognito mode, are honoured, and you can switch learning off per app or globally.

## Building it yourself

You need JDK 17 and the Android SDK (platform 35). Android Studio gives you both; open the project and run it,
or from a terminal:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Copy it to your phone and open it, exactly as above. If you
have a phone connected with USB debugging, `adb install -r <apk>` saves the copying step, and
`adb shell ime set app.slimboard.debug/app.slimboard.SlimBoardService` selects it without touching the settings
screen. Debug builds install as a separate app (`app.slimboard.debug`) so they sit next to a release install.

Release builds (`./gradlew assembleRelease`) are minified and signed with `keystore.properties` if present,
otherwise with the debug key.

The emoji list and the English dictionary are generated assets. `tools/gen-emoji.ps1` builds the emoji list
from Unicode's `emoji-test.txt`; `tools/gen-dict.ps1` builds the word list from Peter Norvig's unigram counts
filtered by the dwyl english-words list. Both are already checked in; rerun them only to update.

More detail for contributors, including a few Windows-specific build quirks, is in [docs/dev-notes.md](docs/dev-notes.md).
Performance measurements over time are in [docs/baseline.md](docs/baseline.md).

## License

Personal project, source available for anyone curious. Emoji data © Unicode, Inc., used under the
[Unicode License](https://www.unicode.org/license.txt).
