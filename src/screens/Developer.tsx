import React, {useCallback, useEffect, useState} from 'react';
import {Alert, ScrollView, Share, Text, TouchableOpacity, View} from 'react-native';
import {Native} from '../native';
import {Btn, C, Card, Field, Toggle} from '../ui';

type Index = Record<string, Record<string, number>>;
const fmt = (t: number) => { const d = new Date(t); const p = (n: number) => String(n).padStart(2, '0'); return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };
const err = (e: any) => Alert.alert('Error', String(e?.message || e));

export default function Developer({cfg, update}: any) {
  const [hasPw, setHasPw] = useState<boolean | null>(null);
  const [pw, setPw] = useState('');          // password typed
  const [session, setSession] = useState(''); // verified password (memory only)
  const [oldPw, setOldPw] = useState(''); const [newPw, setNewPw] = useState('');
  const [index, setIndex] = useState<Index>({});
  const [open, setOpen] = useState<string | null>(null);   // "date" expanded
  const [entries, setEntries] = useState<{key: string; items: any[]} | null>(null);
  const [imp, setImp] = useState(''); const [impPw, setImpPw] = useState('');

  useEffect(() => { Native.logHasPassword().then(setHasPw).catch(() => setHasPw(false)); }, []);
  useEffect(() => () => setSession(''), []);

  const refresh = useCallback((p: string) => Native.logIndex(p).then(setIndex).catch(err), []);

  const unlock = async () => {
    if (await Native.logVerify(pw)) { setSession(pw); setPw(''); refresh(pw); } else Alert.alert('Wrong password');
  };
  const savePw = () => Native.logSetPassword(oldPw, newPw)
    .then(() => { setHasPw(true); setSession(newPw); setOldPw(''); setNewPw(''); refresh(newPw); Alert.alert('Password saved'); }).catch(err);
  const lock = () => { setSession(''); setIndex({}); setEntries(null); setOpen(null); };

  const showHour = (d: string, h: string) => Native.logRead(session, d, h).then(items => setEntries({key: `${d} ${h}:00`, items})).catch(err);
  const doExport = () => Native.logExport(session).then(j => Share.share({message: j})).catch(err);
  const doImport = () => Native.logImport(imp, impPw).then(n => { Alert.alert('Imported', `${n} new entries added.`); setImp(''); setImpPw(''); refresh(session); }).catch(err);
  const doClear = () => Alert.alert('Delete all logged typing?', 'This cannot be undone.', [
    {text: 'Cancel', style: 'cancel'},
    {text: 'Delete', style: 'destructive', onPress: () => Native.logClear(session).then(() => { setIndex({}); setEntries(null); }).catch(err)},
  ]);

  if (hasPw === null) return null;
  const total = (d: string) => Object.values(index[d]).reduce((a, b) => a + b, 0);

  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <Card title="Typing log">
        <Text style={{color: C.sub, marginBottom: 6}}>
          Stores everything typed (except password fields), encrypted on this device and organised by date and hour. A password is required to enable, view, export or delete it.
        </Text>
        <Toggle label="Record typing" hint={hasPw ? undefined : 'Set a password first'} value={cfg.devLog && hasPw}
          onChange={(v: boolean) => { if (v && !hasPw) { Alert.alert('Set a password first'); return; } update({devLog: v}); }} />
      </Card>

      <Card title={hasPw ? 'Change password' : 'Set password'}>
        {hasPw && <Field label="Current password" value={oldPw} onChangeText={setOldPw} secureTextEntry />}
        <Field label="New password (min 4 characters)" value={newPw} onChangeText={setNewPw} secureTextEntry />
        <Btn title="Save password" onPress={savePw} />
        <Text style={{color: C.sub, fontSize: 12, marginTop: 4}}>There is no recovery. If you forget it, the log can’t be read.</Text>
      </Card>

      {hasPw && !session && (
        <Card title="Unlock">
          <Field label="Password" value={pw} onChangeText={setPw} secureTextEntry onSubmitEditing={unlock} />
          <Btn title="Unlock log" onPress={unlock} />
        </Card>
      )}

      {hasPw && !!session && (
        <>
          <Card title="Logged typing">
            {Object.keys(index).length === 0 && <Text style={{color: C.sub}}>Nothing recorded yet.</Text>}
            {Object.keys(index).map(d => (
              <View key={d}>
                <TouchableOpacity onPress={() => setOpen(open === d ? null : d)} style={{flexDirection: 'row', paddingVertical: 10}}>
                  <Text style={{color: C.text, flex: 1, fontWeight: '600'}}>{open === d ? '▾' : '▸'} {d}</Text>
                  <Text style={{color: C.sub}}>{total(d)} entries</Text>
                </TouchableOpacity>
                {open === d && Object.keys(index[d]).map(h => (
                  <TouchableOpacity key={h} onPress={() => showHour(d, h)} style={{flexDirection: 'row', paddingVertical: 8, paddingLeft: 18}}>
                    <Text style={{color: C.accent, flex: 1}}>{h}:00 – {h}:59</Text>
                    <Text style={{color: C.sub}}>{index[d][h]}</Text>
                  </TouchableOpacity>
                ))}
              </View>
            ))}
            <Btn kind="ghost" title="Refresh" onPress={() => refresh(session)} />
          </Card>

          {entries && (
            <Card title={entries.key}>
              {entries.items.map((e, i) => (
                <View key={i} style={{paddingVertical: 5, borderBottomWidth: 1, borderColor: C.line}}>
                  <Text style={{color: C.sub, fontSize: 11}}>{fmt(e.t)} · {e.app || 'unknown app'}</Text>
                  <Text style={{color: C.text}} selectable>{e.s}</Text>
                </View>
              ))}
            </Card>
          )}

          <Card title="Export / import">
            <Btn title="Export (encrypted with your password)" onPress={doExport} />
            <Field label="Paste an exported log to import" value={imp} onChangeText={setImp} multiline mono />
            <Field label="Password the export was made with" value={impPw} onChangeText={setImpPw} secureTextEntry />
            <Btn kind="ghost" title="Import" onPress={doImport} />
          </Card>

          <Card>
            <Btn kind="danger" title="Delete all logged typing" onPress={doClear} />
            <Btn kind="ghost" title="Lock" onPress={lock} />
          </Card>
        </>
      )}
      <View style={{height: 24}} />
    </ScrollView>
  );
}
