#!/usr/bin/env python3
"""Build / sync the OpenEmoji font and its custom-emoji list into the keyboard.

    python3 scripts/build_openemoji.py ../OpenEmoji            # repo root of the OpenEmoji project
    python3 scripts/build_openemoji.py ../OpenEmoji --version 1.100

Font   native/assets/fonts/OpenEmoji.ttf
         * if <repo>/fonts/OpenEmoji.ttf exists (a full build made in the OpenEmoji repo) it is copied as is;
         * otherwise it is built here: the bundled Noto Color Emoji (native/assets/fonts/NotoColorEmoji.ttf,
           CBDT, Unicode 18) + every custom emoji of <repo>/registry/emoji.json (artwork from
           <repo>/assets/custom/png/128), added as Private Use Area glyphs U+F0000... Only fontTools is needed.
           The result is named "OpenEmoji" and keeps Noto's metrics, so standard emoji look exactly like Noto.

List   native/assets/emoji/openemoji.txt  (active custom emoji, SORTED BY CATEGORY then code point)
         columns: emoji <TAB> name <TAB> search keywords <TAB> U+code point <TAB> category <TAB> suggestion words
         suggestion words = "word:rank ..." from the name and aliases only (no category words), rank 1 = the whole
         name/alias is that single word, rank 2 = one word of a longer name/alias. The keyboard uses them to offer the
         emoji in the suggestion bar while Appearance -> Emoji font is OpenEmoji.

The emoji panel shows these in the "OpenEmoji" section only while Appearance -> Emoji font is set to OpenEmoji.
"""
import argparse, copy, json, os, re, shutil, sys
from pathlib import Path

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument('repo', nargs='?', default=os.path.join(root, '..', 'OpenEmoji'), help='OpenEmoji repo root')
ap.add_argument('--version', default='1.100', help='font version MAJOR.MINOR (default 1.100)')
ap.add_argument('--no-font', action='store_true', help='only regenerate openemoji.txt')
args = ap.parse_args()
repo = os.path.abspath(args.repo)

fonts_dir = os.path.join(root, 'native', 'assets', 'fonts')
out_font = os.path.join(fonts_dir, 'OpenEmoji.ttf')

# words that say nothing on their own, so they never trigger a suggestion (only used for multi-word names)
GENERIC = {'with', 'to', 'in', 'face', 'fruit', 'fruits', 'leaf', 'hole', 'planet', 'the', 'of', 'and', 'sea', 'red', 'white', 'blue',
           'green', 'giant', 'tree', 'head', 'little', 'big', 'tea', 'coffee'}


# ----------------------------------------------------------------------------------------------- font
def build_font():
    from fontTools.ttLib import TTFont
    from fontTools.misc.timeTools import epoch_diff, timestampNow
    from fontTools.ttLib.tables.BitmapGlyphMetrics import SmallGlyphMetrics

    prebuilt = os.path.join(repo, 'fonts', 'OpenEmoji.ttf')
    if os.path.exists(prebuilt):
        os.makedirs(fonts_dir, exist_ok=True)
        shutil.copyfile(prebuilt, out_font)
        print('font copied from', prebuilt)
        return

    sys.path.insert(0, os.path.join(repo, 'tools'))
    import build_custom_font as bcf                      # the OpenEmoji repo's own registry / PNG reader
    entries = bcf.load_entries(include_deprecated=True)  # a retired code point keeps its glyph (never reused)
    images = bcf.collect_images(entries, Path(repo) / 'assets' / 'custom' / 'png' / '128')

    font = TTFont(os.path.join(fonts_dir, 'NotoColorEmoji.ttf'))
    order = font.getGlyphOrder()
    cmap12 = next(t for t in font['cmap'].tables if t.format == 12)
    for cp, *_ in images:
        if cp in cmap12.cmap:
            sys.exit('U+%X is already in the Noto base font' % cp)

    # a template glyph for the metrics (Noto uses the same values for every emoji)
    tmpl = font.getBestCmap()[0x1F600]
    vm, hm = tuple(font['vmtx'][tmpl]), tuple(font['hmtx'][tmpl])

    strike = font['CBLC'].strikes[0]
    sub = strike.indexSubTables[-1]
    if sub.indexFormat != 1 or sub.imageFormat != 17 or sub.lastGlyphIndex != len(order) - 1:
        sys.exit('unexpected CBLC layout - the last index subtable must be format 1/17 and end at the last glyph')
    sdata = font['CBDT'].strikeData[0]
    proto = sdata[tmpl]

    for cp, _g, data, w, h, _nm in images:
        name = 'u%X' % cp
        order.append(name)
        font['hmtx'].metrics[name] = hm
        font['vmtx'].metrics[name] = vm
        cmap12.cmap[cp] = name
        bm = copy.copy(proto)                    # shallow: a deep copy would drag the whole font along
        bm.metrics = SmallGlyphMetrics()
        bm.metrics.height, bm.metrics.width = h, w
        bm.metrics.BearingX, bm.metrics.BearingY = (bcf.BITMAP_ADVANCE - w) // 2, bcf.BEARING_Y
        bm.metrics.Advance = bcf.BITMAP_ADVANCE
        bm.imageData = data
        sdata[name] = bm
        sub.names.append(name)
    font.setGlyphOrder(order)
    sub.lastGlyphIndex = len(order) - 1
    strike.bitmapSizeTable.endGlyphIndex = len(order) - 1
    font['maxp'].numGlyphs = len(order)

    # naming: same records the previous OpenEmoji.ttf used
    major, minor = args.version.split('.')
    ver = '%d.%03d' % (int(major), int(minor))
    nm = font['name']
    rec = {1: 'OpenEmoji', 2: 'Regular', 3: '%s;OpenEmoji;OpenEmoji-Regular' % ver, 4: 'OpenEmoji',
           5: 'Version %s;OpenEmoji' % ver, 6: 'OpenEmoji-Regular',
           7: 'OpenEmoji is a Noto Color Emoji derivative. Noto Color Emoji is a trademark of Google LLC.',
           16: 'OpenEmoji', 17: 'Regular'}
    for nid, text in rec.items():
        nm.setName(text, nid, 3, 1, 0x409)
    font['head'].fontRevision = float(ver)
    epoch = os.environ.get('SOURCE_DATE_EPOCH')
    font['head'].modified = int(epoch) - epoch_diff if epoch else timestampNow()
    font['OS/2'].recalcUnicodeRanges(font)

    os.makedirs(fonts_dir, exist_ok=True)
    font.save(out_font)
    chk = TTFont(out_font)
    cm = chk.getBestCmap()
    assert all(cp in cm for cp, *_ in images)
    print('font built: %s  (%.1f MB, %d glyphs, +%d custom emoji, version %s)' % (
        out_font, os.path.getsize(out_font) / 1048576, len(chk.getGlyphOrder()), len(images), ver))


# ------------------------------------------------------------------------------------------------ list
def words_of(t):
    return [w for w in re.split(r'[^a-z0-9]+', t.lower()) if len(w) >= 2]


def build_list():
    reg = json.load(open(os.path.join(repo, 'registry', 'emoji.json'), encoding='utf8'))
    cat_list = json.load(open(os.path.join(repo, 'registry', 'categories.json'), encoding='utf8'))
    cats = {c['id']: c['name'] for c in cat_list}
    rank = {c['id']: i for i, c in enumerate(cat_list)}
    rows = []
    for e in reg:
        if e.get('status', 'active') != 'active':
            continue
        cp = int(e['codepoint'].replace('U+', ''), 16)
        words = []
        for t in [e['name']] + e.get('aliases', []) + [cats.get(e.get('category', ''), '')]:
            for w in words_of(t):
                if w not in words:
                    words.append(w)
        words += ['openemoji', 'custom']
        sug = {}
        for t in [e['name']] + e.get('aliases', []):
            ws = words_of(t)
            if len(ws) == 1 and re.fullmatch(r'[a-z0-9]{2,}', t.strip().lower()):
                sug[ws[0]] = 1                       # the whole name/alias is one plain word (">o wink" is not)
            else:
                for w in ws:
                    if w not in GENERIC and w not in sug:
                        sug[w] = 2
        rows.append((rank.get(e.get('category', ''), 99), cp, '\t'.join([
            chr(cp), e['name'], ' '.join(words), e['codepoint'], e.get('category', ''),
            ' '.join('%s:%d' % kv for kv in sug.items())])))
    rows.sort(key=lambda r: (r[0], r[1]))            # category order of the registry, then code point
    out = os.path.join(root, 'native', 'assets', 'emoji', 'openemoji.txt')
    open(out, 'w', encoding='utf8').write('\n'.join(r[2] for r in rows) + '\n')
    print('%d custom emoji (sorted by category) -> %s' % (len(rows), out))


if not args.no_font:
    build_font()
build_list()
