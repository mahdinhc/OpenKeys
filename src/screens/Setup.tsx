import React, {useCallback, useEffect, useState} from 'react';
import {Alert, AppState, ScrollView, Text, ToastAndroid, TouchableOpacity} from 'react-native';
import {APP_VERSION} from '../config';
import {Native} from '../native';
import {Btn, C, Card, Field} from '../ui';

export default function Setup({cfg, update}: any) {
  const taps = React.useRef({n: 0, t: 0});
  const tapVersion = () => {
    const now = Date.now(); const r = taps.current;
    r.n = now - r.t > 2000 ? 1 : r.n + 1; r.t = now;
    if (r.n >= 10) {
      r.n = 0; const on = !cfg.devMode; update({devMode: on, ...(on ? {} : {devLog: false})});
      ToastAndroid.show(on ? 'Developer options enabled' : 'Developer options disabled', ToastAndroid.SHORT);
    } else if (r.n >= 4) ToastAndroid.show(`${10 - r.n} taps to ${cfg.devMode ? 'disable' : 'enable'} developer options`, ToastAndroid.SHORT);
  };
  const [st, setSt] = useState({enabled: false, selected: false});
  const refresh = useCallback(() => { Native.status().then(setSt).catch(() => {}); }, []);
  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', a => a === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  return (
    <ScrollView>
      <Card title="Step 1 · Enable">
        <Text style={{color: C.sub, marginBottom: 6}}>Turn on “OpenKeys Keyboard” in Android’s on-screen keyboard list.</Text>
        <Btn title={st.enabled ? '✓ Enabled' : 'Open keyboard settings'} kind={st.enabled ? 'ghost' : undefined} onPress={() => Native.openImeSettings()} />
      </Card>
      <Card title="Step 2 · Select">
        <Text style={{color: C.sub, marginBottom: 6}}>Make it your active keyboard.</Text>
        <Btn title={st.selected ? '✓ Active' : 'Choose keyboard'} kind={st.selected ? 'ghost' : undefined} onPress={() => Native.showPicker()} />
      </Card>
      <Card title="Try it">
        <Field placeholder="Type here… (long-press keys, swipe on space to move the cursor)" multiline />
      </Card>
      <Card title="Tips">
        <Text style={{color: C.sub, lineHeight: 20}}>
          • Hold the space bar and slide to switch language · slide on it to move the cursor{'\n'}
          • Double-tap shift for caps lock · long-press keys for accents and symbols{'\n'}
          • Top bar: tools, emoji (with search and skin tones), clipboard (text and images), voice{'\n'}
          • Phonetic Bengali: type “ami banglay gan gai” · use ` to build conjuncts (k`t = ক্ত)
        </Text>
      </Card>
      <TouchableOpacity activeOpacity={1} onPress={tapVersion} style={{padding: 20, alignItems: 'center'}}>
        <Text style={{color: C.sub}}>OpenKeys version {APP_VERSION}</Text>
      </TouchableOpacity>
    </ScrollView>
  );
}
