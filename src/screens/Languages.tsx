import React, {useCallback, useEffect, useState} from 'react';
import {Alert, ScrollView, Text, TouchableOpacity, View} from 'react-native';
import {NEW_LAYOUT} from '../dsl';
import {LayoutInfo, Native} from '../native';
import {Btn, C, Card, Toggle} from '../ui';
import LayoutEditor from './LayoutEditor';

export default function Languages({cfg, update}: any) {
  const [list, setList] = useState<LayoutInfo[]>([]);
  const [editing, setEditing] = useState<any>(null);
  const load = useCallback(() => { Native.getLayouts().then(setList).catch(() => {}); }, []);
  useEffect(load, [load]);

  if (editing) return <LayoutEditor initial={editing} cfg={cfg} update={update} onDone={() => { setEditing(null); load(); }} />;

  const enabled: string[] = cfg.enabledLayouts;
  const items = list.filter(l => l.id !== 'numeric');
  const ordered = [...enabled.map(id => items.find(l => l.id === id)).filter(Boolean) as LayoutInfo[], ...items.filter(l => !enabled.includes(l.id))];

  const toggle = (id: string) => {
    if (enabled.includes(id)) { if (enabled.length > 1) update({enabledLayouts: enabled.filter(x => x !== id)}); }
    else update({enabledLayouts: [...enabled, id]});
  };
  const move = (id: string, d: number) => {
    const i = enabled.indexOf(id), j = i + d;
    if (i < 0 || j < 0 || j >= enabled.length) return;
    const n = [...enabled]; [n[i], n[j]] = [n[j], n[i]]; update({enabledLayouts: n});
  };
  const remove = (l: LayoutInfo) =>
    Alert.alert(l.builtin ? 'Reset to default?' : 'Delete language?', l.name, [
      {text: 'Cancel', style: 'cancel'},
      {text: l.builtin ? 'Reset' : 'Delete', style: 'destructive', onPress: async () => {
        await Native.deleteLayout(l.id);
        if (!l.builtin) update({enabledLayouts: enabled.filter(x => x !== l.id)});
        load();
      }},
    ]);

  return (
    <ScrollView>
      <Card title="Languages & layouts">
        <Text style={{color: C.sub, marginBottom: 6}}>Enabled layouts are cycled with the 🌐 key, in this order.</Text>
        {ordered.map(l => {
          const on = enabled.includes(l.id);
          return (
            <View key={l.id} style={{borderTopWidth: 1, borderColor: C.line, paddingVertical: 8}}>
              <Toggle label={l.name} hint={`${l.id} · ${l.layout.mode === 'translit' ? 'transliteration' : 'direct'}${l.builtin ? ' · built-in' : ' · custom'}${l.overridden ? ' (modified)' : ''}`} value={on} onChange={() => toggle(l.id)} />
              <View style={{flexDirection: 'row'}}>
                {on && <TouchableOpacity onPress={() => move(l.id, -1)}><Text style={{color: C.sub, padding: 8}}>↑</Text></TouchableOpacity>}
                {on && <TouchableOpacity onPress={() => move(l.id, 1)}><Text style={{color: C.sub, padding: 8}}>↓</Text></TouchableOpacity>}
                <TouchableOpacity onPress={() => setEditing(l.layout)}><Text style={{color: C.accent, padding: 8}}>Edit</Text></TouchableOpacity>
                <TouchableOpacity onPress={() => setEditing({...l.layout, id: l.id + '_copy', name: l.name + ' copy'})}><Text style={{color: C.accent, padding: 8}}>Duplicate</Text></TouchableOpacity>
                {(!l.builtin || l.overridden) && <TouchableOpacity onPress={() => remove(l)}><Text style={{color: C.danger, padding: 8}}>{l.builtin ? 'Reset' : 'Delete'}</Text></TouchableOpacity>}
              </View>
            </View>
          );
        })}
      </Card>
      <View style={{margin: 12}}>
        <Btn title="＋ Add custom language" onPress={() => setEditing(NEW_LAYOUT)} />
      </View>
    </ScrollView>
  );
}
