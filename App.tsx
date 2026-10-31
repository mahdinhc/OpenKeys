import React, {useState} from 'react';
import {SafeAreaView, StatusBar, Text, TouchableOpacity, View} from 'react-native';
import {useConfig} from './src/useConfig';
import {C} from './src/ui';
import Setup from './src/screens/Setup';
import Languages from './src/screens/Languages';
import Appearance from './src/screens/Appearance';
import Typing from './src/screens/Typing';

const TABS = [
  ['setup', 'Setup', '⌨'],
  ['lang', 'Languages', '🌐'],
  ['look', 'Appearance', '🎨'],
  ['type', 'Typing', '✍'],
] as const;

export default function App() {
  const {cfg, update} = useConfig();
  const [tab, setTab] = useState<string>('setup');

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: C.bg}}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />
      <Text style={{color: C.text, fontSize: 22, fontWeight: '700', padding: 16, paddingBottom: 4}}>OpenKeys</Text>
      <View style={{flex: 1}}>
        {cfg && tab === 'setup' && <Setup />}
        {cfg && tab === 'lang' && <Languages cfg={cfg} update={update} />}
        {cfg && tab === 'look' && <Appearance cfg={cfg} update={update} />}
        {cfg && tab === 'type' && <Typing cfg={cfg} update={update} />}
      </View>
      <View style={{flexDirection: 'row', borderTopWidth: 1, borderColor: C.line, backgroundColor: C.card}}>
        {TABS.map(([k, label, icon]) => (
          <TouchableOpacity key={k} style={{flex: 1, alignItems: 'center', paddingVertical: 8}} onPress={() => setTab(k)}>
            <Text style={{fontSize: 18}}>{icon}</Text>
            <Text style={{color: tab === k ? C.accent : C.sub, fontSize: 11}}>{label}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </SafeAreaView>
  );
}
