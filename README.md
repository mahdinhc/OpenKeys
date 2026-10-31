# OpenKeys — advanced Android keyboard (React Native + native IME core)

**Architecture.** An Android keyboard must be an `InputMethodService`, which React Native cannot host reliably
(latency, memory, no JS runtime in the IME process). So:

* `native/kotlin/` — the keyboard itself (Kotlin, canvas-drawn, multi-touch, low latency).
* `App.tsx` + `src/` — the **React Native** app: setup, languages & layout editor, themes, typing options, backup.
* Both share settings through `SharedPreferences` and `filesDir/layouts/*.json`; the keyboard live-reloads on change.

## Build
Push to GitHub: `.github/workflows/android.yml` runs `scripts/bootstrap.sh` (generates the RN 0.76 Android scaffold,
copies `native/` into it, patches the manifest) and builds a release APK → **Actions → run → Artifacts**.
Tag `v1.0.0` to also attach the APK to a GitHub Release. Locally: `bash scripts/bootstrap.sh && cd android && ./gradlew assembleRelease`.
(The APK is signed with the debug key; add your own `signingConfig` for Play Store.)

## Features
Gboard-style layout · shift / caps-lock · number row · symbols layers · long-press alternates · swipe typing ·
swipe on space to move cursor · suggestions, auto-correct (+undo on backspace), next-word, learned words ·
auto-capitalise, double-space period · text shortcuts · emoji panel + recents · clipboard history with pins ·
voice typing · one-handed mode · themes (light/dark/AMOLED/custom colours) · height/font/radius/spacing ·
haptics/sound · input-type aware (numeric pad, no suggestions in passwords/email/URL, Enter action label).

## Languages
Built-in: English (QWERTY), Bengali Phonetic (Avro-style), Bengali Bijoy-style. Add any language in **Languages → Add**.

Layout DSL (one row per line): `a` · `a|à|á` long-press · `ক=\u0995\u09CD` label→any output · `\u{1F600}` ·
`{shift} {del} {enter} {space:4} {sym} {sym2} {abc} {lang} {emoji} {mic} {pad:0.5} {layer=name}`.
Mode `translit` adds JSON rules (`consonants`, `vowels` [independent, sign], `marks`, `joiner`, `virama`) for any Indic-style script.
Dictionaries: drop `native/assets/dict/<code>.txt` (one word per line, most common first, or `word<TAB>freq`).
