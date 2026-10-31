import React from 'react';
import {ScrollView, Text, View} from 'react-native';
import {PRESETS} from '../config';
import {Card, Chip, Field, Segmented, Stepper, Toggle} from '../ui';

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
        <Stepper label="Font size" value={cfg.fontScale} min={70} max={150} step={5} unit="%" onChange={(v: number) => update({fontScale: v})} />
        <Stepper label="Key corner radius" value={cfg.keyRadius} min={0} max={24} unit="dp" onChange={(v: number) => update({keyRadius: v})} />
        <Stepper label="Key spacing" value={cfg.keyGap} min={0} max={10} unit="dp" onChange={(v: number) => update({keyGap: v})} />
        <Stepper label="Bottom padding" value={cfg.bottomPad} min={0} max={48} step={2} unit="dp" onChange={(v: number) => update({bottomPad: v})} />
        <Text style={{color: '#9aa0a6', marginTop: 6}}>One-handed mode</Text>
        <Segmented options={[['off', 'Off'], ['left', 'Left'], ['right', 'Right']]} value={cfg.oneHanded} onChange={(v: any) => update({oneHanded: v})} />
      </Card>

      <Card title="Keys">
        <Toggle label="Number row at top" hint="Digits above the letters, like Gboard (on by default)" value={cfg.numberRow} onChange={(v: boolean) => update({numberRow: v})} />
        <Toggle label="Show long-press hints" value={cfg.showHints} onChange={(v: boolean) => update({showHints: v})} />
        <Toggle label="Key pop-up preview" value={cfg.popupPreview} onChange={(v: boolean) => update({popupPreview: v})} />
      </Card>
    </ScrollView>
  );
}
