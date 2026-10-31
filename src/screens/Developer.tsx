import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Alert, ScrollView, Share, Text, TouchableOpacity, View} from 'react-native';
import {Native} from '../native';
import {Btn, C, Card, Field, Segmented, Toggle} from '../ui';

type Index = Record<string, Record<string, number>>;
const fmt = (t: number) => { const d = new Date(t); const p = (n: number) => String(n).padStart(2, '0'); return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };
const err = (e: any) => Alert.alert('Error', String(e?.message || e));

/** Developer options are split into two separate sections: the debug/crash log and the (encrypted) key log. */
export default function Developer({cfg, update}: any) {
  const [section, setSection] = useState<string>('debug');
  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <View style={{marginHorizontal: 12, marginTop: 12}}>
        <Segmented options={[['debug', 'Debug log'], ['keys', 'Key logs']]} value={section} onChange={setSection} />
      </View>
      {section === 'debug' ? <DebugLog cfg={cfg} update={update} /> : <KeyLogs cfg={cfg} update={update} />}
      <View style={{height: 24}} />
    </ScrollView>
  );
}

/* ------------------------------------------------------------------ debug / crash log */

const lineColor = (l: string) =>
  /^\S+ \S+ [EF]\//.test(l) ? C.danger : /^(\s+at |\s+\.\.\.|Caused by)/.test(l) ? C.sub : /\bSession\b/.test(l) ? C.accent : C.text;

function DebugLog({cfg, update}: any) {
  const [text, setText] = useState('');
  const [loading, setLoading] = useState(true);
  const box = useRef<ScrollView>(null);
  const on = !!cfg.devDebugLog;

  const load = useCallback(() => {
    setLoading(true);
    Native.debugLogRead(30000).then(setText).catch(err).then(() => setLoading(false));
  }, []);
  useEffect(() => { load(); }, [load]);

  const addTest = () => Native.debugLogWrite('test entry from the Developer screen')
    .then(() => setTimeout(load, 200)).catch(err);
  const share = () => { if (text) Share.share({message: text}).catch(() => {}); };
  const clear = () => Alert.alert('Clear the debug log?', 'This cannot be undone.', [
    {text: 'Cancel', style: 'cancel'},
    {text: 'Clear', style: 'destructive', onPress: () => Native.debugLogClear().then(() => setText('')).catch(err)},
  ]);

  const lines = text.split('\n');
  return (
    <>
      <Card title="Debug log">
        <Text style={{color: C.sub, marginBottom: 6}}>
          Records keyboard start-up steps, errors and crash stack traces so a crash can be tracked down. It never records what you type — that is the separate “Key logs” section.
        </Text>
        <Toggle label="Enable debug log" value={on}
          hint={on ? 'Logging. Reopen the keyboard (switch away and back), then press Refresh below.' : 'Off — nothing is recorded.'}
          onChange={(v: boolean) => update({devDebugLog: v})} />
      </Card>

      <Card title="Log">
        {!on && <Text style={{color: C.sub, marginBottom: 6}}>Debug log is off. Existing entries are shown below; turn it on to record new ones.</Text>}
        <View style={{height: 380, borderRadius: 8, borderWidth: 1, borderColor: C.line, backgroundColor: '#0f1115'}}>
          <ScrollView ref={box} nestedScrollEnabled style={{padding: 8}}
            onContentSizeChange={() => box.current?.scrollToEnd({animated: false})}>
            {text.length === 0
              ? <Text style={{color: C.sub}}>{loading ? 'Loading…' : 'Nothing recorded yet.'}</Text>
              : <Text selectable style={{fontFamily: 'monospace', fontSize: 11}}>
                  {lines.map((l, i) => <Text key={i} style={{color: lineColor(l)}}>{l}{i < lines.length - 1 ? '\n' : ''}</Text>)}
                </Text>}
          </ScrollView>
        </View>
        <Text style={{color: C.sub, fontSize: 12, marginTop: 6}}>Newest entries are at the bottom. Red = errors / crashes.</Text>
        <Btn title="Refresh" onPress={load} />
        <Btn kind="ghost" title="Share log" onPress={share} />
        <Btn kind="ghost" title="Add test entry" onPress={addTest} />
        <Btn kind="danger" title="Clear log" onPress={clear} />
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------ key logs (encrypted typing log) */

function KeyLogs({cfg, update}: any) {
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
    <>
      <Card title="Key log">
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
    </>
  );
}
