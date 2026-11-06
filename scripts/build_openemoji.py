#!/usr/bin/env python3
"""Sync the OpenEmoji font and its custom-emoji list into the keyboard.

    python3 scripts/build_openemoji.py ../OpenEmoji          # repo root of the OpenEmoji project

Copies   <repo>/fonts/OpenEmoji.ttf     -> native/assets/fonts/OpenEmoji.ttf
Writes   native/assets/emoji/openemoji.txt   (one line per active custom emoji from <repo>/registry/emoji.json)
         columns:  emoji <TAB> name <TAB> search keywords <TAB> U+code point <TAB> category

Build the font first (see OpenEmoji/docs/BUILD.md, `tools/build_openemoji_ttf.py`).
The emoji panel shows these in an "OpenEmoji" section only while Appearance -> Emoji font is set to OpenEmoji.
"""
import json, os, re, shutil, sys

here = os.path.dirname(os.path.abspath(__file__))
root = os.path.dirname(here)
repo = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, '..', 'OpenEmoji'))

font = os.path.join(repo, 'fonts', 'OpenEmoji.ttf')
if not os.path.exists(font):
    sys.exit('missing %s - build the font first' % font)
os.makedirs(os.path.join(root, 'native', 'assets', 'fonts'), exist_ok=True)
shutil.copyfile(font, os.path.join(root, 'native', 'assets', 'fonts', 'OpenEmoji.ttf'))

reg = json.load(open(os.path.join(repo, 'registry', 'emoji.json'), encoding='utf8'))
cats = {c['id']: c['name'] for c in json.load(open(os.path.join(repo, 'registry', 'categories.json'), encoding='utf8'))}
lines = []
for e in reg:
    if e.get('status', 'active') != 'active':
        continue
    cp = int(e['codepoint'].replace('U+', ''), 16)
    words = []
    for t in [e['name']] + e.get('aliases', []) + [cats.get(e.get('category', ''), '')]:
        for w in re.split(r'[^a-z0-9]+', t.lower()):
            if len(w) >= 2 and w not in words:
                words.append(w)
    words += ['openemoji', 'custom']
    lines.append('\t'.join([chr(cp), e['name'], ' '.join(words), e['codepoint'], e.get('category', '')]))
out = os.path.join(root, 'native', 'assets', 'emoji', 'openemoji.txt')
open(out, 'w', encoding='utf8').write('\n'.join(lines) + '\n')
print('font copied, %d custom emoji -> %s' % (len(lines), out))
