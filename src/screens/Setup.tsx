import React, {useCallback, useEffect, useState} from 'react';
import {AppState, ScrollView, Text} from 'react-native';
import {Native} from '../native';
import {Btn, C, Card, Field} from '../ui';

export default function Setup() {
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
    </ScrollView>
  );
}
