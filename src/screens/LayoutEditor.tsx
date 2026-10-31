import React, {useMemo, useState} from 'react';
import {Alert, ScrollView, Text, View} from 'react-native';
import {parseRow} from '../dsl';
import {Native} from '../native';
import {Btn, C, Card, Chip, Field, Segmented, Toggle} from '../ui';

const LAYERS = ['base', 'shift', 'symbols', 'symbols2'];
const EXAMPLE_TRANSLIT = JSON.stringify({
  virama: '्', joiner: '`', autoDouble: false,
  consonants: {k: 'क', kh: 'ख', g: 'ग'},
  vowels: {a: ['अ', ''], aa: ['आ', 'ा'], i: ['इ', 'ि']},
  marks: {'^': 'ँ'},
}, null, 2);

function Preview({rows}: {rows: string[]}) {
  return (
    <View style={{backgroundColor: '#202124', padding: 6, borderRadius: 8, marginVertical: 6}}>
      {rows.filter(r => r.trim()).map((r, i) => (
        <View key={i} style={{flexDirection: 'row'}}>
          {parseRow(r).map((k, j) => k.pad
            ? <View key={j} style={{flex: k.weight}} />
            : (
              <View key={j} style={{flex: k.weight, height: 30, margin: 1.5, borderRadius: 5, backgroundColor: k.special ? '#2D2F31' : '#3C4043', alignItems: 'center', justifyContent: 'center'}}>
                <Text style={{color: '#E8EAED', fontSize: 12}} numberOfLines={1}>{k.label}</Text>
              </View>
            ))}
        </View>
      ))}
    </View>
  );
}

function UnicodeTool() {
  const [cps, setCps] = useState('0995 09CD 09B7');
  const [chars, setChars] = useState('');
  const fromCps = useMemo(() => {
    try {
      const list = cps.split(/[\s,]+/).filter(Boolean).map(h => parseInt(h.replace(/^U\+/i, ''), 16));
      return {text: String.fromCodePoint(...list), esc: list.map(n => '\\u{' + n.toString(16).toUpperCase() + '}').join('')};
    } catch { return {text: '?', esc: ''}; }
  }, [cps]);
  const fromChars = useMemo(
    () => Array.from(chars).map(c => '\\u{' + c.codePointAt(0)!.toString(16).toUpperCase() + '}').join(''), [chars]);
  return (
    <Card title="Unicode helper">
      <Field label="Code points → character" value={cps} onChangeText={setCps} mono />
      <Text selectable style={{color: C.text, fontSize: 22}}>{fromCps.text}</Text>
      <Text selectable style={{color: C.sub}}>{fromCps.esc}</Text>
      <Field label="Characters → escapes (paste any text)" value={chars} onChangeText={setChars} />
      <Text selectable style={{color: C.sub}}>{fromChars}</Text>
    </Card>
  );
}

export default function LayoutEditor({initial, cfg, update, onDone}: any) {
  const [id, setId] = useState<string>(initial.id);
  const [name, setName] = useState<string>(initial.name);
  const [locale, setLocale] = useState<string>(initial.locale || 'en');
  const [mode, setMode] = useState<string>(initial.mode || 'direct');
  const [dict, setDict] = useState<string>(initial.dict || 'none');
  const [autoCap, setAutoCap] = useState<boolean>(!!initial.autoCap);
  const [swipe, setSwipe] = useState<boolean>(!!initial.swipe);
  const [end, setEnd] = useState<string>(initial.sentenceEnd || '.');
  const [numRow, setNumRow] = useState<string>(initial.numberRow || '');
  const [layer, setLayer] = useState('base');
  const [layers, setLayers] = useState<Record<string, string>>(() => {
    const o: Record<string, string> = {};
    for (const k of LAYERS) o[k] = (initial.layers?.[k] || []).join('\n');
    return o;
  });
  const [translit, setTranslit] = useState<string>(initial.translit ? JSON.stringify(initial.translit, null, 2) : EXAMPLE_TRANSLIT);

  const save = async () => {
    try {
      if (!/^[a-z0-9_]{2,40}$/.test(id)) throw new Error('ID must be 2–40 characters: a-z, 0-9, underscore');
      const out: any = {id, name, locale, mode, dict, autoCap, swipe, sentenceEnd: end, layers: {}};
      for (const k of LAYERS) {
        const rows = layers[k].split('\n').map(r => r.trim()).filter(Boolean);
        if (rows.length) out.layers[k] = rows;
      }
      if (!out.layers.base) throw new Error('The base layer needs at least one row');
      if (numRow.trim()) out.numberRow = numRow.trim();
      if (mode === 'translit') out.translit = JSON.parse(translit);
      await Native.saveLayout(out);
      if (!cfg.enabledLayouts.includes(id)) update({enabledLayouts: [...cfg.enabledLayouts, id]});
      onDone();
    } catch (e: any) { Alert.alert('Cannot save', String(e?.message || e)); }
  };

  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <Card title="Language">
        <Field label="ID (unique, a-z 0-9 _)" value={id} onChangeText={setId} />
        <Field label="Display name" value={name} onChangeText={setName} />
        <Field label="Locale (used for voice typing, e.g. en, bn, hi)" value={locale} onChangeText={setLocale} />
        <Field label="Dictionary code (assets/dict/<code>.txt, or none)" value={dict} onChangeText={setDict} />
        <Field label="Sentence-end character (double-space)" value={end} onChangeText={setEnd} />
        <Text style={{color: C.sub, marginTop: 6}}>Input method</Text>
        <Segmented options={[['direct', 'Direct (each key = its output)'], ['translit', 'Transliteration']]} value={mode} onChange={setMode} />
        <Toggle label="Auto-capitalize" value={autoCap} onChange={setAutoCap} />
        <Toggle label="Swipe typing" value={swipe} onChange={setSwipe} />
      </Card>

      <Card title="Layers">
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {LAYERS.map(l => <Chip key={l} label={l} on={layer === l} onPress={() => setLayer(l)} />)}
        </View>
        <Preview rows={layers[layer].split('\n')} />
        <Field label={`One row per line · ${layer}`} multiline mono value={layers[layer]} onChangeText={(v: string) => setLayers({...layers, [layer]: v})} />
        <Field label="Number row (optional)" mono value={numRow} onChangeText={setNumRow} placeholder="১|1 ২|2 ৩|3 …" />
        <Text style={{color: C.sub, fontSize: 12, lineHeight: 18}}>
          a · a|à|á (long-press) · ক=\u0995\u09CD (label ক outputs ক্) · \u{'{1F600}'} any code point{'\n'}
          {'{shift} {del} {enter} {space:4} {sym} {sym2} {abc} {lang} {emoji} {mic} {pad:0.5} {layer=name}'}{'\n'}
          “shift” layer is optional: without it letters are upper-cased automatically.
        </Text>
      </Card>

      {mode === 'translit' && (
        <Card title="Transliteration rules (JSON)">
          <Field multiline mono value={translit} onChangeText={setTranslit} style={{minHeight: 220}} />
          <Text style={{color: C.sub, fontSize: 12}}>
            consonants: roman → letter · vowels: roman → [independent, sign] · marks: roman → mark · joiner builds conjuncts · longest match wins.
          </Text>
        </Card>
      )}

      <UnicodeTool />
      <View style={{margin: 12}}>
        <Btn title="Save language" onPress={save} />
        <Btn kind="ghost" title="Cancel" onPress={() => onDone()} />
      </View>
    </ScrollView>
  );
}
