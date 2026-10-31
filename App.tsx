import React, {useState} from 'react';
import {SafeAreaView, StatusBar, Text, TouchableOpacity, View} from 'react-native';
import {useConfig} from './src/useConfig';
import {Icon} from './src/Icon';
import {C} from './src/ui';
import Setup from './src/screens/Setup';
import Languages from './src/screens/Languages';
import Appearance from './src/screens/Appearance';
import Typing from './src/screens/Typing';
import Piano from './src/screens/Piano';
import Developer from './src/screens/Developer';

const BASE_TABS = [
  ['setup', 'Setup', 'keyboard'],
  ['lang', 'Languages', 'translate'],
  ['look', 'Appearance', 'palette'],
  ['type', 'Typing', 'edit'],
  ['piano', 'Piano', 'piano'],
] as const;
const DEV_TAB = ['dev', 'Developer', 'settings'] as const;

export default function App() {
  const {cfg, update} = useConfig();
  const [tab, setTab] = useState<string>('setup');
  const TABS: readonly (readonly [string, string, string])[] = cfg?.devMode ? [...BASE_TABS, DEV_TAB] : BASE_TABS;
  React.useEffect(() => { if (cfg && !cfg.devMode && tab === 'dev') setTab('setup'); }, [cfg, tab]);

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: C.bg}}>
      <StatusBar barStyle="light-content" backgroundColor={C.bg} />
      <View style={{flexDirection: 'row', alignItems: 'center', padding: 16, paddingBottom: 4}}>
        <View style={{width: 34, height: 34, borderRadius: 10, backgroundColor: '#1A73E8', alignItems: 'center', justifyContent: 'center', marginRight: 10}}>
          <Icon name="keyboard" size={22} color="#fff" />
        </View>
        <Text style={{color: C.text, fontSize: 22, fontWeight: '700'}}>OpenKeys</Text>
      </View>
      <View style={{flex: 1}}>
        {cfg && tab === 'setup' && <Setup cfg={cfg} update={update} />}
        {cfg && tab === 'lang' && <Languages cfg={cfg} update={update} />}
        {cfg && tab === 'look' && <Appearance cfg={cfg} update={update} />}
        {cfg && tab === 'type' && <Typing cfg={cfg} update={update} />}
        {cfg && tab === 'piano' && <Piano cfg={cfg} update={update} />}
        {cfg && cfg.devMode && tab === 'dev' && <Developer cfg={cfg} update={update} />}
      </View>
      <View style={{flexDirection: 'row', borderTopWidth: 1, borderColor: C.line, backgroundColor: C.card}}>
        {TABS.map(([k, label, icon]) => (
          <TouchableOpacity key={k} style={{flex: 1, alignItems: 'center', paddingVertical: 8}} onPress={() => setTab(k)}>
            <Icon name={icon} size={22} color={tab === k ? C.accent : C.sub} />
            <Text style={{color: tab === k ? C.accent : C.sub, fontSize: 11}}>{label}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </SafeAreaView>
  );
}
