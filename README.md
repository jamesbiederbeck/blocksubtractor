# Block Subtractor

A fullscreen portrait block breaker. Shared look: see `DESIGN.md`. Drag to move the paddle, release to launch the ball, clear all bricks to advance a level (numbered bricks take that many hits, counting down on each hit) (the ball gets faster). Losing a ball costs math: by default 1,1,1 then 2,3,4… problems must be solved before the next ball (the minimum, when growth starts, growth per ball and maximum are set in the parents menu); best score is kept in SharedPreferences. No permissions, no network.

Built with Make — no Gradle, no IDE. Package `design.antics.blocksubtractor`,
minSdk 24 / targetSdk 33.

## Dependencies

```
sudo apt install \
  google-android-platform-33-installer \
  google-android-build-tools-34.0.0-installer \
  openjdk-17-jdk \
  apksigner \
  zipalign
```

`adb` comes from `../platform-tools/adb`.

## Build

```bash
make keystore              # one-time: generate debug.keystore
make                       # build signed APK (aligned.apk)
make install               # build + adb install
make run                   # install + launch
make logcat                # tail this app's log
make clean
```

`DEVICE` auto-detects the first device `adb` sees. Override with
`make run DEVICE=<ip>:<port>` or by exporting `ANDROID_DEVICE`.

## Parents menu

Hamburger icon top-left, behind a 4-digit PIN (default **1234**, changeable in
the menu; a speed bump, stored in plain SharedPreferences). Opening it pauses
the ball. Options: toggle **problem packs**, reset the best score, change the PIN.

Each problem is drawn from a random enabled pack (default: Partners within 10 +
Mixed within 20). The 23 packs are organised by strategy rather than just bigger
numbers: tiny add/subtract, one/two more or less, partners, make ten, doubles and
near doubles, teen numbers, crossing ten, mixed within 20 (with missing-number
blanks such as `8 + ? = 14`), tens and ones, two-digit with/without regrouping,
within 1000, then times tables (x2, x5/x10, x3, x4, x6/x9, x7/x8) and mixed
multiply/divide. All numbers, including blanks and results, are positive (>= 1).
Packs live in `Packs.java`; `make`-free JVM check: generate many problems per
pack and verify the equation holds and every value is >= 1.
