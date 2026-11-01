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
suggestions (choose where they appear: app decides / moderate / everywhere), auto-correct (+undo on backspace), next-word, learned words · auto-capitalise, double-space period ·
text shortcuts · **full emoji set** (1,900+ RGI emoji incl. ZWJ sequences and flags, 9 categories, recents,
search, skin-tone long-press, emoticons/kaomoji, **Emoji Kitchen** mixes) · **clipboard history with text and images** (pins; images are
inserted into apps that accept them, otherwise copied back to the system clipboard) · voice typing ·
one-handed mode · themes (light/dark/AMOLED/custom) · height/font/radius/spacing · haptics/sound ·
input-type aware (Typing → *Show suggestions in*: **App decides** hides suggestions wherever an app asks, **Moderate** (default) also shows them in search bars and opted-out fields, **Everywhere** shows them in every text field except passwords and number/phone; Enter action icon).

**Emoji Kitchen.** Emoji panel → the chef-hat button next to the emoticon button. Tap two emoji and the mashup sticker appears; tap it to send
(inserted as an image where the app accepts it, otherwise copied to the clipboard). Stickers are fetched from `gstatic.com` the first time a pair is
used and cached on the device, so this is the one feature that goes online. The release folders it probes are listed in `native/assets/emoji/kitchen_dates.txt`.

GIF search / stickers are not included (they need a third-party GIF service and API key).

## Plugins (JavaScript)
Drop a single `.js` file into the app's **Plugins** tab (or install from a URL) and it can add toolbar buttons and layout keys,
rewrite words as you commit them, add suggestions, react to typing, open panels, call HTTP APIs and keep settings.
The keyboard embeds QuickJS (`app.cash.quickjs`) and runs all plugins on one background thread; the Kotlin side is
`native/kotlin/PluginHost.kt` (+ `PluginPanel.kt`), the JS runtime is `native/assets/plugin_prelude.js`.
See **`plugins/README.md`** for the API and the nine sample plugins (camel-case button, case tools, text expander, auto-pair,
snippets, clean paste, online suggestions…). `node scripts/test_plugins.js` tests the runtime and samples without a device
(it also runs in CI before the build).

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


## Bangla (from Avro Keyboard + BengaliDictionary)
- 180k-word Bangla dictionary with frequencies; sound-alike matching (ই/ঈ, ন/ণ, শ/ষ/স, ত/ট, hasanta-optional…) so `prem` suggests প্রেম
- Avro autocorrect: 2,200 loanwords typed in roman convert correctly (`academy` → অ্যাকাডেমি)
- Suffix handling (Avro suffix table) and Avro phonetic extras: `Z` (্য), `x`, `rr`, `oo`, `ee`
- New fixed layouts: Probhat, Jatiya (National)
- Regenerate data with the scripts in /scripts if you change the source dictionaries

## English dictionaries (large, fast, de-duplicated)
* `native/assets/dict/en.bin` - ~421k words: the curated seed + a hand-ranked list of the ~1,750 most common words + every word of
  *english-words* (duplicates removed). `en_urban.bin` - ~19k *Urban Dictionary* words that are not already in `en.bin`.
  Rebuild with `python3 scripts/build_dict_bin.py --seed scripts/data/en_seed.txt --top scripts/data/en_top.txt --english <english-words dir> --urban <urban-dictionary-word-list dir> --out native/assets/dict` (checked in CI by `scripts/test_dict_bin.py`).
* The `.bin` files are memory-mapped (no parsing, no heap), completion for 1-3 letter prefixes comes from a precomputed table, and typo
  candidates come from a pruned walk over the sorted words, so lookups take about the same time whether the list holds 75k or 5M words.
* **Settings -> Typing -> Urban Dictionary words** (off by default): slang is completed, offered as a fix and no longer auto-corrected.
* Autocorrect never changes a common word; a *rare* list word or an Urban word is only changed for an obvious slip (swapped letters, or a long
  word one edit from a core word). `native/assets/dict/en_next.txt` holds curated next-word suggestions.
* `en_anime.bin` (~26k words from *animanga-wordlist*; switch **Anime & manga words**, off by default): only clean words - a-z (one inner apostrophe), 3-12 letters, no accents/mojibake, no letter 3x in a row, must contain a vowel, seen in at least 3 titles, not a probable misspelling, and never a word already in `en.bin` or `en_urban.bin`. Add `--anime <dir>` to the build command.
