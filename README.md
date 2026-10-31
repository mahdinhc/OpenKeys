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
Gboard-style layout (number row on by default, two symbol pages, numeric keypad for number/phone fields) ·
vector icons everywhere (no emoji characters in the UI) · **hold space and slide to switch language** ·
shift / caps-lock · long-press alternates · swipe typing · slide on space to move the cursor ·
suggestions, auto-correct (+undo on backspace), next-word, learned words · auto-capitalise, double-space period ·
text shortcuts · **full emoji set** (1,900+ RGI emoji incl. ZWJ sequences and flags, 9 categories, recents,
search, skin-tone long-press, emoticons/kaomoji) · **clipboard history with text and images** (pins; images are
inserted into apps that accept them, otherwise copied back to the system clipboard) · voice typing ·
one-handed mode · themes (light/dark/AMOLED/custom) · height/font/radius/spacing · haptics/sound ·
input-type aware (no suggestions in passwords/email/URL, Enter action icon).

GIF search / stickers are not included (they need a third-party GIF service and API key).

## Languages
Built-in: English (QWERTY), Bengali Phonetic (Avro-style), Bengali Bijoy-style. Add any language in **Languages → Add**.

Layout DSL (one row per line): `a` · `a|à|á` long-press · `ক=\u0995\u09CD` label→any output · `\u{1F600}` ·
`{shift} {del} {enter} {space:4} {sym} {sym2} {abc} {emoji} {mic} {pad:0.5} {layer=name~Label}` · `a@1.5` key width ·
`{stack=+;-;*;/:0.62:3}` tall multi-key column spanning 3 rows.
Mode `translit` adds JSON rules (`consonants`, `vowels` [independent, sign], `marks`, `joiner`, `virama`) for any Indic-style script.
Dictionaries: drop `native/assets/dict/<code>.txt` (one word per line, most common first, or `word<TAB>freq`).

## Developer options
Tap the version number on **Setup** 10 times to show the **Developer** tab. It has two separate sections:

* **Debug log** - switch on *Enable debug log* to record keyboard start-up steps, swallowed errors and uncaught-exception
  stack traces (written to `files/devlog/debug.log`, rotated at ~200 KB). View, share or clear it in the app. It never
  contains typed text. Turn it on *before* reproducing a crash, reopen the keyboard, then press *Refresh*.
  Also visible with `adb logcat -s OpenKeys*`.
* **Key logs** - the password-protected, encrypted typing log (date/hour organised, export/import).
