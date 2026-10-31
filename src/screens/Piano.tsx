import React, {useEffect, useState} from 'react';
import {Alert, ScrollView, Text, View} from 'react-native';
import Svg, {Polyline} from 'react-native-svg';
import {Native} from '../native';
import {Btn, C, Card, Chip, Field, Stepper, Toggle} from '../ui';

const DEMO = 'C4:300 D4:300 E4:300 C4:300 E4:300 C4:300 E4:600 _:200 [C4 E4 G4]:800 [F3 A3 C4]:400 [G3 B3 D4]:400 [C4 E4 G4 C5]:1000';
const WAVES: [string, string][] = [['sine', 'Sine'], ['triangle', 'Triangle'], ['saw', 'Saw'], ['square', 'Square']];

function Adsr({a, d, s, r}: {a: number; d: number; s: number; r: number}) {
  const W = 280, H = 70, hold = 300, tot = a + d + hold + r;
  const x = (t: number) => (t / tot) * W, y = (v: number) => H - v * H;
  const pts = [[0, 0], [a, 1], [a + d, s / 100], [a + d + hold, s / 100], [tot, 0]].map(([t, v]) => `${x(t)},${y(v)}`).join(' ');
  return <Svg width={W} height={H + 4} style={{alignSelf: 'center', marginVertical: 6}}><Polyline points={pts} fill="none" stroke={C.accent} strokeWidth={2.5} /></Svg>;
}

export default function Piano({cfg, update}: any) {
  const [text, setText] = useState(DEMO);
  const [speed, setSpeed] = useState(100);
  const [playing, setPlaying] = useState(false);
  useEffect(() => () => { Native.stopMelody().catch(() => {}); }, []);

  const play = () => {
    setPlaying(true);
    Native.playMelody(text, speed / 100).catch((e: any) => Alert.alert('Player', String(e?.message || e))).finally(() => setPlaying(false));
  };
  const stop = () => Native.stopMelody().finally(() => setPlaying(false));
  const N = (k: string, label: string, min: number, max: number, step = 1, unit = '') =>
    <Stepper label={label} value={cfg[k]} min={min} max={max} step={step} unit={unit} onChange={(v: number) => update({[k]: v})} />;
  const T = (k: string, label: string, hint?: string) => <Toggle label={label} hint={hint} value={cfg[k]} onChange={(v: boolean) => update({[k]: v})} />;

  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <Card title="Melody player">
        <Text style={{color: C.sub, marginBottom: 4}}>
          Play anything typed with the piano keyboard. Format: note:ms · chords in [ ] · _ = rest. Uses your sound settings below.
        </Text>
        <Field value={text} onChangeText={setText} multiline mono placeholder="C4:300 E4:300 [C4 E4 G4]:600" />
        <Stepper label="Speed" value={speed} min={25} max={400} step={25} unit="%" onChange={setSpeed} />
        {playing ? <Btn kind="danger" title="Stop" onPress={stop} /> : <Btn title="Play" onPress={play} />}
        <Btn kind="ghost" title="Load demo tune" onPress={() => setText(DEMO)} />
      </Card>

      <Card title="Synth">
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {WAVES.map(([k, l]) => <Chip key={k} label={l} on={cfg.pianoWave === k} onPress={() => update({pianoWave: k})} />)}
        </View>
        <Adsr a={cfg.pianoAttack} d={cfg.pianoDecay} s={cfg.pianoSustain} r={cfg.pianoRelease} />
        {N('pianoAttack', 'Attack', 1, 2000, 10, 'ms')}
        {N('pianoDecay', 'Decay', 10, 2000, 10, 'ms')}
        {N('pianoSustain', 'Sustain', 0, 100, 5, '%')}
        {N('pianoRelease', 'Release', 10, 3000, 10, 'ms')}
        {N('pianoGate', 'Gate time (0 = off)', 0, 3000, 50, 'ms')}
        {N('pianoVolume', 'Volume', 5, 100, 5, '%')}
        {N('pianoCutoff', 'Filter (low-pass)', 5, 100, 5, '%')}
        {N('pianoDetune', 'Detune / thickness', 0, 50, 2, ' ct')}
        {N('pianoVibDepth', 'Vibrato depth', 0, 100, 5, ' ct')}
        {N('pianoVibRate', 'Vibrato rate', 1, 12, 1, ' Hz')}
      </Card>

      <Card title="Piano layout">
        {N('pianoKeys', 'White keys', 7, 21)}
        {N('pianoOctave', 'Start octave', 0, 6)}
        {N('pianoHeight', 'Piano height', 60, 160, 5, '%')}
        {N('pianoBlackH', 'Black key height', 40, 80, 2, '%')}
        {T('pianoLabels', 'Show note names')}
        <Field label="White key colour (#RRGGBB)" value={cfg.pianoWhite} onChangeText={(v: string) => update({pianoWhite: v})} />
        <Field label="Black key colour" value={cfg.pianoBlack} onChangeText={(v: string) => update({pianoBlack: v})} />
        <Field label="Pressed key colour" value={cfg.pianoPress} onChangeText={(v: string) => update({pianoPress: v})} />
      </Card>

      <Card title="Typing">
        {T('pianoSound', 'Play sound')}
        {T('pianoRecord', 'Type melody as text', 'Notes appear in the text field when you lift your fingers')}
        {T('pianoTiming', 'Include timing and rests', 'Off = notes only (C4 E4 [C4 E4 G4])')}
        {N('pianoChordMs', 'Chord window', 20, 200, 10, 'ms')}
        {N('pianoQuant', 'Round durations to', 10, 200, 10, 'ms')}
      </Card>
      <View style={{height: 24}} />
    </ScrollView>
  );
}
