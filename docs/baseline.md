# Performance baseline

Numbers every later phase is compared against. Collected with `tools/baseline.ps1` plus `dumpsys meminfo`.
Debug build unless stated. Re-measure after each phase and append a row; never overwrite history.

## Samsung Galaxy S24 Ultra (SM-S928B)

Android 16, One UI 8.5. Display set to FHD+ 1080x2340 at 450 dpi (density 2.8125), 60 Hz active at time of test.

| Date       | Phase | Build   | APK     | onCreate | onCreateInputView | show → first draw | PSS total | Java heap | Native heap | Graphics |
|------------|-------|---------|---------|----------|-------------------|-------------------|-----------|-----------|-------------|----------|
| 2026-09-02 | 0     | debug   | 829 KB  | 21 ms    | 5 ms              | 19 ms (cold)      | 99.7 MB   | 9.8 MB    | 11.8 MB     | 50.6 MB  |
| 2026-09-02 | 0     | release | 30 KB   | –        | –                 | –                 | –         | –         | –           | –        |
| 2026-09-03 | 1     | debug   | 8.93 MB | –        | 1 ms              | 7–18 ms (warm)    | 138 MB *  | 14.8 MB * | 20.9 MB *   | 56.0 MB *|
| 2026-09-03 | 1     | release | 1.02 MB | –        | –                 | –                 | –         | –         | –           | –        |

\* Phase 1 memory was sampled with SlimBoard's own Compose settings activity in the foreground, so it includes
the settings UI, not just the keyboard. Re-measure with a third-party app focused before comparing to Phase 0.
Debug APK grew from Compose (settings screen only); release stays at 1 MB.

Notes:
- "Graphics" is EGL/GL memory for the input method window's surface buffers, allocated by the system for any
  keyboard on this display size. It is not under our control and is excluded from the 60 MB target in PLAN.md;
  the target applies to Java + native + code + private other, which is ~36 MB here in a debug build.
- The debug build carries no R8 and a debug runtime; release numbers should be lower. Measure release once the
  release variant is installed on a phone.
- Cold "show → first draw" is measured from `onStartInputView` to the end of the first `onDraw`.

## Poco M8

Not yet measured. This is the floor device: the PLAN.md targets (cold show < 150 ms) are judged here.

| Date | Phase | Build | APK | onCreate | onCreateInputView | show → first draw | PSS total | Java heap | Native heap | Graphics |
|------|-------|-------|-----|----------|-------------------|-------------------|-----------|-----------|-------------|----------|
