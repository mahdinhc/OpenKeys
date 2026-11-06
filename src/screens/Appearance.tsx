import React from 'react';
import {Image, ScrollView, Text, View} from 'react-native';
import {Alert, TouchableOpacity} from 'react-native';
import {BG_BLENDS, BG_FITS, EMOJI_GROUPS, FONTS, PRESETS, THEMES, TOP_TOOLS} from '../config';
import {Native} from '../native';
import KitchenCard from './KitchenCard';
import {Btn, Card, Chip, Field, Segmented, Stepper, Toggle} from '../ui';

const COLOR_KEYS: [string, string][] = [
  ['bg', 'Background'], ['key', 'Key'], ['keyText', 'Key text'], ['special', 'Special key'], ['accent', 'Accent'], ['hint', 'Hint text'],
];

export default function Appearance({cfg, update}: any) {
  const colors = cfg.theme === 'custom' ? cfg.colors : PRESETS[cfg.theme] || PRESETS.dark;
  const pick = (t: string) => update(t === 'custom' ? {theme: t, colors: {...colors}} : {theme: t});
  const r = cfg.keyRadius;

  return (
    <ScrollView>
      <Card title="Preview">
        <View style={{backgroundColor: colors.bg, padding: 8, borderRadius: 10, overflow: 'hidden'}}>
          {!!cfg.bgPath && (
            <Image source={{uri: 'file://' + cfg.bgPath}} resizeMode={cfg.bgFit === 'fit' ? 'contain' : cfg.bgFit === 'stretch' ? 'stretch' : 'cover'}
              blurRadius={Math.min(25, Math.round(cfg.bgBlur / 4))}
              style={{position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, opacity: cfg.bgOpacity / 100}} />
          )}
          {cfg.bgDim > 0 && <View style={{position: 'absolute', left: 0, right: 0, top: 0, bottom: 0, backgroundColor: '#000', opacity: cfg.bgDim / 100}} />}
          {['q w e r t y', 'a s d f g h', 'z x c v b'].map((row, i) => (
            <View key={i} style={{flexDirection: 'row', justifyContent: 'center'}}>
              {row.split(' ').map((k, j) => (
                <View key={j} style={{
                  width: 40, height: 40 * cfg.heightScale / 100, margin: cfg.keyGap / 2, borderRadius: r,
                  backgroundColor: colors.key, alignItems: 'center', justifyContent: 'center', opacity: cfg.keyAlpha / 100,
                  borderWidth: cfg.keyBorder, borderColor: colors.hint,
                }}>
                  <Text style={{color: colors.keyText, fontSize: 16 * cfg.fontScale / 100, fontWeight: cfg.fontBold ? '700' : '400', fontStyle: cfg.fontItalic ? 'italic' : 'normal',
                    fontFamily: ({serif: 'serif', mono: 'monospace', condensed: 'sans-serif-condensed', light: 'sans-serif-light', medium: 'sans-serif-medium'} as any)[cfg.fontFamily]}}>{k}</Text>
                </View>
              ))}
            </View>
          ))}
          <View style={{height: 34, margin: 3, borderRadius: r, backgroundColor: colors.accent}} />
        </View>
      </Card>

      <Card title="Theme">
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {THEMES.map(([t, l]) => <Chip key={t} label={l} on={cfg.theme === t} onPress={() => pick(t)} />)}
        </View>
        {cfg.theme === 'custom' && COLOR_KEYS.map(([k, l]) => (
          <Field key={k} label={`${l} (#RRGGBB)`} value={cfg.colors[k]} onChangeText={(v: string) => update({colors: {...cfg.colors, [k]: v}})} />
        ))}
      </Card>

      <Card title="Background image">
        <Text style={{color: '#9aa0a6', marginBottom: 4}}>{cfg.bgPath ? `Current: ${cfg.bgName || 'image'}` : 'No image — the theme color is used.'}</Text>
        <View style={{flexDirection: 'row'}}>
          <View style={{flex: 1, marginRight: 6}}>
            <Btn title={cfg.bgPath ? 'Change image' : 'Choose image'} onPress={() => Native.pickBackground().then(r => { if (r) update({bgPath: r.path, bgName: r.name, bgRev: r.rev}); }).catch((e: any) => Alert.alert('Background', String(e?.message || e)))} />
          </View>
          {!!cfg.bgPath && <View style={{flex: 1}}><Btn kind="ghost" title="Remove" onPress={() => Native.clearBackground().then(() => update({bgPath: '', bgName: '', bgRev: Date.now()}))} /></View>}
        </View>
        {!!cfg.bgPath && (
          <>
            <Text style={{color: '#9aa0a6', marginTop: 8}}>Fit</Text>
            <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
              {BG_FITS.map(([k, l]) => <Chip key={k} label={l} on={cfg.bgFit === k} onPress={() => update({bgFit: k})} />)}
            </View>
            <Text style={{color: '#9aa0a6', marginTop: 4}}>Blend with theme color</Text>
            <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
              {BG_BLENDS.map(([k, l]) => <Chip key={k} label={l} on={cfg.bgBlend === k} onPress={() => update({bgBlend: k})} />)}
            </View>
            <Stepper label="Opacity" value={cfg.bgOpacity} min={0} max={100} step={5} unit="%" onChange={(v: number) => update({bgOpacity: v})} />
            <Stepper label="Blur" value={cfg.bgBlur} min={0} max={100} step={5} onChange={(v: number) => update({bgBlur: v})} />
            <Stepper label="Grayscale" value={cfg.bgGray} min={0} max={100} step={10} unit="%" onChange={(v: number) => update({bgGray: v})} />
            <Stepper label="Sepia" value={cfg.bgSepia} min={0} max={100} step={10} unit="%" onChange={(v: number) => update({bgSepia: v})} />
            <Stepper label="Brightness" value={cfg.bgBright} min={0} max={250} step={10} unit="%" onChange={(v: number) => update({bgBright: v})} />
            <Stepper label="Contrast" value={cfg.bgContrast} min={0} max={250} step={10} unit="%" onChange={(v: number) => update({bgContrast: v})} />
            <Stepper label="Saturation" value={cfg.bgSat} min={0} max={300} step={10} unit="%" onChange={(v: number) => update({bgSat: v})} />
            <Stepper label="Hue shift" value={cfg.bgHue} min={-180} max={180} step={15} unit="°" onChange={(v: number) => update({bgHue: v})} />
            <Stepper label="Dim (darken)" value={cfg.bgDim} min={0} max={100} step={5} unit="%" onChange={(v: number) => update({bgDim: v})} />
            <Stepper label="Zoom" value={cfg.bgScale} min={100} max={400} step={10} unit="%" onChange={(v: number) => update({bgScale: v})} />
            <Field label="Tint color (#RRGGBB)" value={cfg.bgTint} onChangeText={(v: string) => update({bgTint: v})} />
            <Stepper label="Tint strength" value={cfg.bgTintAmt} min={0} max={100} step={5} unit="%" onChange={(v: number) => update({bgTintAmt: v})} />
            <Toggle label="Invert colors" value={cfg.bgInvert} onChange={(v: boolean) => update({bgInvert: v})} />
            <Toggle label="Mirror horizontally" value={cfg.bgFlipX} onChange={(v: boolean) => update({bgFlipX: v})} />
            <Stepper label="Panels opacity" value={cfg.bgPanels} min={0} max={100} step={5} unit="%" onChange={(v: number) => update({bgPanels: v})} />
            <Text style={{color: '#9aa0a6', fontSize: 12}}>Panels = emoji, clipboard, piano and plugin panels. 100% hides the image behind them.</Text>
          </>
        )}
      </Card>

      <Card title="Key style">
        <Stepper label="Key opacity" value={cfg.keyAlpha} min={0} max={100} step={5} unit="%" onChange={(v: number) => update({keyAlpha: v})} />
        <Stepper label="Key border" value={cfg.keyBorder} min={0} max={6} unit="dp" onChange={(v: number) => update({keyBorder: v})} />
        <Toggle label="Key shadow" value={cfg.keyShadow} onChange={(v: boolean) => update({keyShadow: v})} />
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
        <Toggle label="Italic keys" value={cfg.fontItalic} onChange={(v: boolean) => update({fontItalic: v})} />
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

      <Card title="Emoji font">
        <Segmented options={[['system', 'System'], ['noto', 'Noto'], ['colr', 'COLRv1'], ['openemoji', 'OpenEmoji']]} value={cfg.emojiFont || 'system'} onChange={(v: any) => update({emojiFont: v})} />
        <Text style={{color: '#9aa0a6', fontSize: 12, marginTop: 6}}>
          {(cfg.emojiFont || 'system') === 'system'
            ? 'Uses your phone\'s emoji font. Emoji newer than your Android version are hidden from the panel.'
            : cfg.emojiFont === 'noto'
            ? 'Bundled Noto Color Emoji with every Unicode 18 emoji, on any Android version.'
            : cfg.emojiFont === 'openemoji'
            ? 'Bundled OpenEmoji: Noto Color Emoji art plus OpenEmoji\'s own 68 emoji (fruit and vegetables, plants, animals, planets, extra faces and more), on any Android version. The emoji panel gets an extra OpenEmoji section, sorted by category, and they are also offered in the suggestion bar and emoji search (Typing → Emoji suggestions). Those custom emoji are private-use characters: apps only draw them if they use the OpenEmoji font, otherwise they show as an empty box.'
            : 'Bundled Noto COLRv1: vector, sharp at any size and smaller in memory. Needs Android 14+, otherwise Noto Color is used.'}
          {' '}This only changes how emoji look inside the keyboard. Other apps draw what you send with their own font, so brand-new emoji can look blank there until that app or Android supports them.
        </Text>
      </Card>

      <KitchenCard cfg={cfg} update={update} />

      <Card title="Emoji menu">
        <Stepper label="Columns" value={cfg.emojiCols} min={4} max={12} onChange={(v: number) => update({emojiCols: v})} />
        <Stepper label="Emoji size" value={cfg.emojiSize} min={60} max={150} step={5} unit="%" onChange={(v: number) => update({emojiSize: v})} />
        <Stepper label="Emoji panel height" value={cfg.emojiHeight} min={60} max={160} step={5} unit="%" onChange={(v: number) => update({emojiHeight: v})} />
        <Toggle label="Section titles" value={cfg.emojiHeaders} onChange={(v: boolean) => update({emojiHeaders: v})} />
        <Toggle label="Recent emoji section" value={cfg.emojiRecents} onChange={(v: boolean) => update({emojiRecents: v})} />
        <Text style={{color: '#9aa0a6', marginTop: 6}}>Categories shown</Text>
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {EMOJI_GROUPS.map((g, i) => {
            if (i === 9 && cfg.emojiFont !== 'openemoji') return null;   // OpenEmoji section only exists with the OpenEmoji font
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
