#!/usr/bin/env python3
"""
Builds the compact, memory-mappable English dictionaries used by the keyboard:

    native/assets/dict/en.bin         core words (curated seed) + every word from english-words
    native/assets/dict/en_urban.bin   Urban Dictionary words that are NOT already in en.bin (opt-in in Settings)

Usage (from the repo root):
    python3 scripts/build_dict_bin.py \
        --seed scripts/data/en_seed.txt --top scripts/data/en_top.txt \
        --english path/to/english-words-master \
        --urban path/to/urban-dictionary-word-list-master \
        --out native/assets/dict

Duplicates are removed everywhere (case-insensitive, one entry per word; seed beats english-words beats urban).

File format "OKD1" (little endian) - read through mmap, nothing is parsed at start-up:
    0   magic 'OKD1'          4   version (1)        8   n words
    12  offOffsets  (n+1) x u32 byte offsets into the blob
    16  offFreq     n x u32 frequency
    20  offBlob     UTF-8 words, sorted bytewise, no separators
    24  blobLen
    28  offPrefKeys  nPref x u32   (sorted)   key = len<<24 | b0<<16 | b1<<8 | b2
    32  nPref
    36  offPrefStart (nPref+1) x u32 -> into the prefix word list
    40  offPrefWords u32 word indices, TOP_K best words for every 1..3 byte prefix
    44  offFallback  u32 count + that many word indices (most common words, for next-word fallback)
    48  commonMin    words with freq >= this are 'core' (the rest are rare: valid, but weak evidence)
    52  totalLen     56  crc32 of everything after the 64-byte header      60  reserved
"""
import argparse, collections, glob, os, re, struct, sys, zlib

TOP_K = 16
HEADER = 64
COMMON_MIN = 20            # seed/derived words are >= 21; every extra word is below this
ALPHA = re.compile(r"[a-z]+(?:'[a-z]+)?$")


def align4(b):
    return b + (-len(b)) % 4


def write_bin(path, items, common_min, fallback_n=64):
    """items: dict word -> freq. Writes the OKD1 file."""
    words = sorted(items, key=lambda w: w.encode('utf8'))
    enc = [w.encode('utf8') for w in words]
    n = len(words)
    offs, pos = [], 0
    for e in enc:
        offs.append(pos); pos += len(e)
    offs.append(pos)
    blob = b''.join(enc)
    freq = [min(int(items[w]), 0x7FFFFFFF) for w in words]

    # best words for every 1..3 byte prefix
    best = collections.defaultdict(list)
    order = sorted(range(n), key=lambda i: (-freq[i], i))
    for i in order:
        e = enc[i]
        for L in (1, 2, 3):
            if len(e) >= L:
                lst = best[e[:L]]
                if len(lst) < TOP_K:
                    lst.append(i)
    def key_of(p):
        b = list(p) + [0] * (3 - len(p))
        return (len(p) << 24) | (b[0] << 16) | (b[1] << 8) | b[2]
    keys = sorted(best, key=key_of)
    pk = [key_of(p) for p in keys]
    ps, pw = [], []
    for p in keys:
        ps.append(len(pw)); pw.extend(best[p])
    ps.append(len(pw))
    fb = order[:fallback_n]

    base = HEADER
    body = bytearray()
    def put(data):
        off = base + len(body); body.extend(data)
        body.extend(b'\0' * ((-len(body)) % 4)); return off
    off_offsets = put(struct.pack('<%dI' % (n + 1), *offs))
    off_freq = put(struct.pack('<%dI' % n, *freq))
    off_blob = put(blob)
    off_pk = put(struct.pack('<%dI' % len(pk), *pk))
    off_ps = put(struct.pack('<%dI' % len(ps), *ps))
    off_pw = put(struct.pack('<%dI' % len(pw), *pw))
    off_fb = put(struct.pack('<I%dI' % len(fb), len(fb), *fb))
    total = base + len(body)
    crc = zlib.crc32(bytes(body)) & 0xFFFFFFFF
    head = struct.pack('<4s14I', b'OKD1', 1, n, off_offsets, off_freq, off_blob, len(blob),
                       off_pk, len(pk), off_ps, off_pw, off_fb, common_min, total, crc)
    head += b'\0' * (HEADER - len(head))
    with open(path, 'wb') as f:
        f.write(head); f.write(body)
    return n, total


def load_seed(path):
    seed = {}
    for ln in open(path, encoding='utf8'):
        p = ln.rstrip('\n').split('\t')
        if not p[0].strip(): continue
        w = p[0].strip().lower()
        f = int(p[1]) if len(p) > 1 and p[1].strip().isdigit() else 0
        if w not in seed or f > seed[w]: seed[w] = f
    return seed


def load_top(path):
    """Hand-ordered common words (most frequent first); already-ranked seed words keep their rank."""
    out = []
    if os.path.exists(path):
        for ln in open(path, encoding='utf8'):
            if ln.startswith('#'): continue
            out.extend(w.lower() for w in ln.split())
    return out


def load_english(folder):
    out = set()
    for name in ('words_alpha.txt', 'words.txt'):
        p = os.path.join(folder, name)
        if not os.path.exists(p): continue
        for ln in open(p, encoding='utf8', errors='replace'):
            w = ln.strip().lower()
            if ALPHA.match(w): out.add(w)
    p = os.path.join(folder, 'uk-us-dict.txt')          # UK / US spelling pairs
    if os.path.exists(p):
        for ln in list(open(p, encoding='utf8'))[1:]:
            for w in ln.split():
                w = w.lower()
                if ALPHA.match(w): out.add(w)
    return out


def rare_freq(w):
    """Frequency for words that only exist in the big list: always < COMMON_MIN, shorter = likelier."""
    return max(1, min(COMMON_MIN - 1, int(round(60 / (len(w) ** 1.3)))))


LETTERS = "abcdefghijklmnopqrstuvwxyz"


def edits1(w):
    """Every string one deletion / insertion / substitution / adjacent swap away."""
    sp = [(w[:i], w[i:]) for i in range(len(w) + 1)]
    out = {a + b[1:] for a, b in sp if b}
    out |= {a + c + b for a, b in sp for c in LETTERS}
    out |= {a + c + b[1:] for a, b in sp if b for c in LETTERS}
    out |= {a + b[1] + b[0] + b[2:] for a, b in sp if len(b) > 1}
    out.discard(w)
    return out


def load_urban(folder, known, min_count, core=None, typo_below=10):
    """Single-word Urban Dictionary entries; frequency proxy = number of entry names that contain the word."""
    tok = collections.Counter(); single = set()
    tokrx = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
    for f in sorted(glob.glob(os.path.join(folder, 'data', '*.data'))):
        for ln in open(f, encoding='utf-8', errors='replace'):
            e = ln.strip()
            if not e: continue
            for t in {x.lower() for x in tokrx.findall(e)}: tok[t] += 1
            if tokrx.fullmatch(e): single.add(e.lower())
    out = {}
    for w in single:
        c = tok[w]
        if c < min_count or w in known: continue
        if not (3 <= len(w) <= 16) or not ALPHA.match(w): continue
        if re.search(r'(.)\1\1', w): continue                 # yessss, aaaah ...
        if not re.search(r'[aeiouy]', w) and len(w) > 4: continue   # keyboard mashes
        out[w] = min(c, 1_000_000)
    if core is not None:
        # Urban Dictionary is full of misspellings (comming, adress, goverment...). A rarely-used entry that sits one
        # edit away from a core English word is far more likely a typo than slang, so it is left out: that way the
        # typo is still auto-corrected. Popular entries (yeet, bruh, ...) are kept.
        drop = [w for w, c in out.items() if len(w) >= 5 and c < typo_below and not edits1(w).isdisjoint(core)]
        for w in drop: del out[w]
        print('urban: dropped %d probable misspellings, e.g. %s' % (len(drop), sorted(drop)[:12]))
    return out


def load_anime(folder, known, min_count=3, typo_below=10, core=None):
    """Words from anime / manga / character / studio titles (animanga-wordlist). Frequency = number of titles containing the word.
    Only clean words are kept: ASCII letters (one inner apostrophe), 3-12 letters, no letter 3x in a row, a vowel, no accents/mojibake."""
    tok = collections.Counter()
    rx = re.compile(r"[^\W\d_]+(?:['\u2019][^\W\d_]+)?", re.U)
    for f in glob.glob(os.path.join(folder, '*', '*.txt')):
        if os.sep + 'sites' + os.sep in f or 'pirated' in f: continue     # website lists, not titles
        for ln in open(f, encoding='utf-8', errors='replace'):
            for t in {x.lower().replace('\u2019', "'") for x in rx.findall(ln)}: tok[t] += 1
    out = {}
    for w, c in tok.items():
        if c < min_count or w in known: continue
        if not re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) or not (3 <= len(w) <= 12): continue
        if re.search(r'(.)\1\1', w) or not re.search(r'[aeiouy]', w): continue
        out[w] = min(c, 1_000_000)
    if core is not None:
        drop = [w for w, c in out.items() if len(w) >= 5 and c < typo_below and not edits1(w).isdisjoint(core)]
        for w in drop: del out[w]
        print('anime: dropped %d probable misspellings' % len(drop))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--seed', required=True); ap.add_argument('--top'); ap.add_argument('--english'); ap.add_argument('--urban'); ap.add_argument('--anime')
    ap.add_argument('--out', required=True); ap.add_argument('--urban-min', type=int, default=3)
    a = ap.parse_args()
    seed = load_seed(a.seed)
    main_words = dict(seed)
    if a.top:
        ranked = sorted((w for w, f in seed.items() if f >= 1_000_000), key=lambda w: -seed[w])
        have = set(ranked)
        for w in load_top(a.top):
            if ALPHA.match(w) and w not in have:
                have.add(w); ranked.append(w)
        for r, w in enumerate(ranked):
            main_words[w] = 2_000_000 - 100 * r          # same scheme as the seed's own top list
        print('top list: %d ranked words (seed had %d)' % (len(ranked), sum(1 for f in seed.values() if f >= 1_000_000)))
    if a.english:
        extra = load_english(a.english)
        added = 0
        for w in extra:
            if w not in main_words:
                main_words[w] = rare_freq(w); added += 1
        print('english-words: %d unique, %d new, %d duplicates of the seed' % (len(extra), added, len(extra) - added))
    n, size = write_bin(os.path.join(a.out, 'en.bin'), main_words, COMMON_MIN)
    print('en.bin: %d words, %d KB' % (n, size // 1024))
    if a.urban:
        core = {w for w, f in main_words.items() if f >= COMMON_MIN}
        u = load_urban(a.urban, set(main_words), a.urban_min, core)
        n, size = write_bin(os.path.join(a.out, 'en_urban.bin'), u, 0)
        print('en_urban.bin: %d words, %d KB' % (n, size // 1024))
    else:
        u = {}
    if a.anime:
        core = {w for w, f in main_words.items() if f >= COMMON_MIN}
        an = load_anime(a.anime, set(main_words) | set(u), core=core)      # never repeats a main or Urban word
        n, size = write_bin(os.path.join(a.out, 'en_anime.bin'), an, 0)
        print('en_anime.bin: %d words, %d KB' % (n, size // 1024))


if __name__ == '__main__':
    main()
