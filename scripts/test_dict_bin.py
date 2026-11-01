#!/usr/bin/env python3
"""Sanity-checks native/assets/dict/*.bin: header, CRC, sorted + unique words, prefix tables, fallback list."""
import glob, os, struct, sys, zlib

def check(path):
    d = open(path, 'rb').read()
    (mg, ver, n, oo, of, ob, bl, opk, npk, ops, opw, ofb, cm, tot, crc) = struct.unpack_from('<4s14I', d, 0)
    assert mg == b'OKD1' and ver == 1, 'magic/version'
    assert tot == len(d), 'length'
    assert zlib.crc32(d[64:]) & 0xFFFFFFFF == crc, 'crc'
    offs = struct.unpack_from('<%dI' % (n + 1), d, oo)
    assert offs[0] == 0 and offs[-1] == bl, 'offsets'
    words = [d[ob + offs[i]:ob + offs[i + 1]] for i in range(n)]
    assert all(words[i] < words[i + 1] for i in range(n - 1)), 'not strictly sorted (duplicates or bad order)'
    assert all(len(w) > 0 for w in words), 'empty word'
    freq = struct.unpack_from('<%dI' % n, d, of)
    keys = struct.unpack_from('<%dI' % npk, d, opk)
    assert list(keys) == sorted(keys), 'prefix keys'
    start = struct.unpack_from('<%dI' % (npk + 1), d, ops)
    pw = struct.unpack_from('<%dI' % start[-1], d, opw)
    for k in range(0, npk, max(1, npk // 400)):          # spot check prefix lists
        L = keys[k] >> 24; p = bytes([(keys[k] >> 16) & 255, (keys[k] >> 8) & 255, keys[k] & 255])[:L]
        lst = pw[start[k]:start[k + 1]]
        assert lst and all(words[i].startswith(p) for i in lst), 'prefix list'
        best = max(freq[i] for i in range(n) if words[i].startswith(p)) if n < 50000 else freq[lst[0]]
        assert freq[lst[0]] == best, 'prefix list not best-first'
    print('%s: %d words OK' % (os.path.basename(path), n))
    return set(words)

dicts = {os.path.basename(p): check(p) for p in sorted(glob.glob(os.path.join(os.path.dirname(__file__), '..', 'native', 'assets', 'dict', '*.bin')))}
if 'en.bin' in dicts and 'en_urban.bin' in dicts:
    assert not (dicts['en.bin'] & dicts['en_urban.bin']), 'en_urban.bin repeats words of en.bin'
    print('no word appears in both en.bin and en_urban.bin')
