import React from 'react';
import {ScrollView, Text, View} from 'react-native';
import {Alert, TouchableOpacity} from 'react-native';
import {EMOJI_GROUPS, FONTS, PRESETS, TOP_TOOLS} from '../config';
import {Native} from '../native';
import {Btn, Card, Chip, Field, Segmented, Stepper, Toggle} from '../ui';

const COLOR_KEYS: [string, string][] = [
  ['bg', 'Background'], ['key', 'Key'], ['keyText', 'Key text'], ['special', 'Special key'], ['accent', 'Accent'], ['hint', 'Hint text'],
];

export default function Appearance({cfg, update}: any) {
  const colors = cfg.theme === 'custom' ? cfg.colors : PRESETS[cfg.theme] || cfg.colors;
  const pick = (t: string) => update(t === 'custom' ? {theme: t, colors: {...colors}} : {theme: t});
  const r = cfg.keyRadius;

  return (
    <ScrollView>
      <Card title="Preview">
        <View style={{backgroundColor: colors.bg, padding: 8, borderRadius: 10}}>
          {['q w e r t y', 'a s d f g h', 'z x c v b'].map((row, i) => (
            <View key={i} style={{flexDirection: 'row', justifyContent: 'center'}}>
              {row.split(' ').map((k, j) => (
                <View key={j} style={{
                  width: 40, height: 40 * cfg.heightScale / 100, margin: cfg.keyGap / 2, borderRadius: r,
                  backgroundColor: colors.key, alignItems: 'center', justifyContent: 'center',
                }}>
                  <Text style={{color: colors.keyText, fontSize: 16 * cfg.fontScale / 100}}>{k}</Text>
                </View>
              ))}
            </View>
          ))}
          <View style={{height: 34, margin: 3, borderRadius: r, backgroundColor: colors.accent}} />
        </View>
      </Card>

      <Card title="Theme">
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {['light', 'dark', 'amoled', 'custom'].map(t => <Chip key={t} label={t} on={cfg.theme === t} onPress={() => pick(t)} />)}
        </View>
        {cfg.theme === 'custom' && COLOR_KEYS.map(([k, l]) => (
          <Field key={k} label={`${l} (#RRGGBB)`} value={cfg.colors[k]} onChangeText={(v: string) => update({colors: {...cfg.colors, [k]: v}})} />
        ))}
      </Card>

      <Card title="Size & shape">
        <Stepper label="Keyboard height" value={cfg.heightScale} min={70} max={140} step={5} unit="%" onChange={(v: number) => update({heightScale: v})} />
        <Stepper label="Key corner radius" value={cfg.keyRadius} min={0} max={24} unit="dp" onChange={(v: number) => update({keyRadius: v})} />
        <Stepper label="Key spacing" value={cfg.keyGap} min={0} max={10} unit="dp" onChange={(v: number) => update({keyGap: v})} />
        <Stepper label="Bottom padding" value={cfg.bottomPad} min={0} max={48} step={2} unit="dp" onChange={(v: number) => update({bottomPad: v})} />
        <Text style={{color: '#9aa0a6', marginTop: 6}}>One-handed mode</Text>
        <Segmented options={[['off', 'Off'], ['left', 'Left'], ['right', 'Right']]} value={cfg.oneHanded} onChange={(v: any) => update({oneHanded: v})} />
      </Card>

      <Card title="Font">
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {FONTS.map(([k, l]) => <Chip key={k} label={l} on={cfg.fontFamily === k} onPress={() => update({fontFamily: k})} />)}
        </View>
        {cfg.fontFamily === 'custom' && (
          <>
            <Text style={{color: '#9aa0a6', marginVertical: 4}}>{cfg.customFontName ? `Current: ${cfg.customFontName}` : 'No font file chosen yet'}</Text>
            <Btn title="Choose font file (.ttf / .otf)" onPress={() => Native.pickFont().then(n => { if (n) update({customFontName: n}); }).catch((e: any) => Alert.alert('Font', String(e?.message || e)))} />
          </>
        )}
        <Toggle label="Bold keys" value={cfg.fontBold} onChange={(v: boolean) => update({fontBold: v})} />
        <Stepper label="Key font size" value={cfg.fontScale} min={70} max={170} step={5} unit="%" onChange={(v: number) => update({fontScale: v})} />
      </Card>

      <Card title="Top buttons">
        <Text style={{color: '#9aa0a6', marginBottom: 4}}>Order, hide or show the buttons above the keyboard.</Text>
        {(cfg.topTools || []).filter((k: string) => k !== 'abc').map((k: string, i: number, a: string[]) => {
          const mv = (d: number) => { const n = [...a]; const j = i + d; if (j < 0 || j >= n.length) return; [n[i], n[j]] = [n[j], n[i]]; update({topTools: n}); };
          const B = ({t, f}: any) => <TouchableOpacity onPress={f} style={{paddingHorizontal: 14, paddingVertical: 6}}><Text style={{color: '#8ab4f8', fontSize: 18}}>{t}</Text></TouchableOpacity>;
          return (
            <View key={k} style={{flexDirection: 'row', alignItems: 'center'}}>
              <Text style={{color: '#e8eaed', flex: 1}}>{(TOP_TOOLS.find(t => t[0] === k) || [k, k])[1]}</Text>
              <B t="▲" f={() => mv(-1)} /><B t="▼" f={() => mv(1)} />
              <B t="✕" f={() => a.length > 1 && update({topTools: a.filter((x: string) => x !== k)})} />
            </View>
          );
        })}
        <View style={{flexDirection: 'row', flexWrap: 'wrap', marginTop: 6}}>
          {TOP_TOOLS.filter(t => !(cfg.topTools || []).includes(t[0])).map(t => <Chip key={t[0]} label={'+ ' + t[1]} on={false} onPress={() => update({topTools: [...(cfg.topTools || []).filter((k: string) => k !== 'abc'), t[0]]})} />)}
        </View>
        <Stepper label="Button size" value={cfg.topIconSize} min={30} max={80} step={5} unit="%" onChange={(v: number) => update({topIconSize: v})} />
      </Card>

      <Card title="Emoji menu">
        <Stepper label="Columns" value={cfg.emojiCols} min={4} max={12} onChange={(v: number) => update({emojiCols: v})} />
        <Stepper label="Emoji size" value={cfg.emojiSize} min={60} max={150} step={5} unit="%" onChange={(v: number) => update({emojiSize: v})} />
        <Stepper label="Emoji panel height" value={cfg.emojiHeight} min={60} max={160} step={5} unit="%" onChange={(v: number) => update({emojiHeight: v})} />
        <Toggle label="Section titles" value={cfg.emojiHeaders} onChange={(v: boolean) => update({emojiHeaders: v})} />
        <Toggle label="Recent emoji section" value={cfg.emojiRecents} onChange={(v: boolean) => update({emojiRecents: v})} />
        <Text style={{color: '#9aa0a6', marginTop: 6}}>Categories shown</Text>
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {EMOJI_GROUPS.map((g, i) => {
            const hidden = (cfg.emojiHidden || []).includes(String(i));
            return <Chip key={g} label={g} on={!hidden} onPress={() => update({emojiHidden: hidden ? cfg.emojiHidden.filter((x: string) => x !== String(i)) : [...(cfg.emojiHidden || []), String(i)]})} />;
          })}
        </View>
      </Card>

      <Card title="Keys">
        <Toggle label="Number row at top" hint="Digits above the letters, like Gboard (on by default)" value={cfg.numberRow} onChange={(v: boolean) => update({numberRow: v})} />
        <Toggle label="Show long-press hints" value={cfg.showHints} onChange={(v: boolean) => update({showHints: v})} />
        <Toggle label="Key pop-up preview" value={cfg.popupPreview} onChange={(v: boolean) => update({popupPreview: v})} />
      </Card>
    </ScrollView>
  );
}
