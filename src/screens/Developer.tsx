import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Alert, ScrollView, Share, Text, TouchableOpacity, View} from 'react-native';
import {Native} from '../native';
import {Btn, C, Card, Field, Segmented, Toggle} from '../ui';

type Index = Record<string, Record<string, number>>;
const fmt = (t: number) => { const d = new Date(t); const p = (n: number) => String(n).padStart(2, '0'); return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };
const err = (e: any) => Alert.alert('Error', String(e?.message || e));

const SECTIONS: [string, string][] = [['debug', 'Debug'], ['keys', 'Key log'], ['emoji', 'Emoji'], ['search', 'Search']];

/** Developer options, one section at a time: Debug log · Key log (encrypted) · Emoji browser · Search tester. */
export default function Developer({cfg, update}: any) {
  const [section, setSection] = useState<string>('debug');
  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <View style={{marginHorizontal: 12, marginTop: 12}}>
        <Segmented options={SECTIONS} value={section} onChange={setSection} />
      </View>
      {section === 'debug' && <DebugLog cfg={cfg} update={update} />}
      {section === 'keys' && <KeyLogs cfg={cfg} update={update} />}
      {section === 'emoji' && <EmojiBrowser cfg={cfg} />}
      {section === 'search' && <SearchTester cfg={cfg} />}
      <View style={{height: 24}} />
    </ScrollView>
  );
}

const Chip2 = ({label, on, onPress}: any) => (
  <TouchableOpacity onPress={onPress} style={{paddingHorizontal: 12, paddingVertical: 6, borderRadius: 16, marginRight: 6, marginBottom: 6, backgroundColor: on ? C.accent : C.line}}>
    <Text style={{color: on ? '#0f1115' : C.text, fontWeight: '600', fontSize: 13}}>{label}</Text>
  </TouchableOpacity>
);
const Hint = ({children}: any) => <Text style={{color: C.sub, fontSize: 12, marginTop: 4, marginBottom: 4}}>{children}</Text>;
const Row = ({children}: any) => <View style={{flexDirection: 'row', flexWrap: 'wrap', marginTop: 4}}>{children}</View>;
const FACE: Record<string, string | undefined> = {system: undefined, noto: 'NotoColorEmoji', colr: 'Noto-COLRv1'};

/* ------------------------------------------------------------------ emoji browser */

type Em = [number, string, string, string, boolean]; // group, emoji, keywords, unicode version, drawable
const vnum = (v: string) => parseFloat(v) || 0;
const GROUPS = ['Smileys', 'People', 'Animals', 'Food', 'Travel', 'Activities', 'Objects', 'Symbols', 'Flags'];

function EmojiBrowser({cfg}: any) {
  const [font, setFont] = useState<string>(cfg.emojiFont || 'system'); // starts from Settings → Appearance → Emoji font
  const [all, setAll] = useState<Em[]>([]);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState('newer');      // newer | all | missing
  const [ver, setVer] = useState<string>('');     // "newer" = this version and up
  const [group, setGroup] = useState(-1);
  const [q, setQ] = useState('');
  const [limit, setLimit] = useState(300);

  useEffect(() => {
    setLoading(true);
    Native.devEmojiList(font).then(setAll).catch(err).then(() => setLoading(false));
  }, [font]);

  const versions = useMemo(() => {
    const m: Record<string, number> = {};
    all.forEach(e => { if (e[3]) m[e[3]] = (m[e[3]] || 0) + 1; });
    return Object.keys(m).sort((a, b) => vnum(b) - vnum(a)).map(v => [v, m[v]] as [string, number]);
  }, [all]);
  useEffect(() => { if (!ver && versions.length) setVer(versions[0][0]); }, [versions, ver]);

  const shown = useMemo(() => {
    const t = q.toLowerCase().trim();
    return all.filter(e =>
      (mode === 'all' || (mode === 'missing' ? !e[4] : vnum(e[3]) >= vnum(ver))) &&
      (group < 0 || e[0] === group) &&
      (!t || e[1] === t || e[2].toLowerCase().includes(t)));
  }, [all, mode, ver, group, q]);
  useEffect(() => setLimit(300), [mode, ver, group, q, font]);

  const missing = all.filter(e => !e[4]).length;
  return (
    <>
      <Card title="Emoji font">
        <Segmented options={[['system', 'System'], ['noto', 'Noto Color'], ['colr', 'Noto COLRv1']]} value={font} onChange={setFont} />
        <Hint>Starts from your Appearance setting ({cfg.emojiFont || 'system'}). Changing it here only affects this test view. Dimmed emoji can’t be drawn by the selected font.</Hint>
      </Card>

      <Card title="Show">
        <Segmented options={[['newer', 'Newer'], ['all', 'All'], ['missing', 'Missing']]} value={mode} onChange={setMode} />
        {mode === 'newer' && (
          <>
            <Hint>Emoji added in Unicode version … or later:</Hint>
            <Row>{versions.slice(0, 8).map(([v, n]) => <Chip2 key={v} label={`${v} (${n})`} on={ver === v} onPress={() => setVer(v)} />)}</Row>
          </>
        )}
        {mode === 'missing' && <Hint>{missing} emoji can’t be drawn with this font.</Hint>}
        <Hint>Group</Hint>
        <Row>
          <Chip2 label="All" on={group < 0} onPress={() => setGroup(-1)} />
          {GROUPS.map((g, i) => <Chip2 key={g} label={g} on={group === i} onPress={() => setGroup(i)} />)}
        </Row>
        <Field label="Filter by name / keyword" value={q} onChangeText={setQ} />
      </Card>

      <Card title={loading ? 'Loading…' : `${shown.length} emoji`}>
        <View style={{flexDirection: 'row', flexWrap: 'wrap'}}>
          {shown.slice(0, limit).map((e, i) => (
            <TouchableOpacity key={i} style={{width: '12.5%', alignItems: 'center', paddingVertical: 4, opacity: e[4] ? 1 : 0.25}}
              onPress={() => Alert.alert(e[1], `${e[2] || '(no keywords)'}\nUnicode ${e[3] || '?'} · ${GROUPS[e[0]]}\nU+${Array.from(e[1]).map(c => c.codePointAt(0)!.toString(16).toUpperCase()).join(' U+')}\n${e[4] ? 'Drawable' : 'Not drawable with this font'}`)}>
              <Text style={{fontSize: 26, color: C.text, fontFamily: FACE[font]}}>{e[1]}</Text>
            </TouchableOpacity>
          ))}
        </View>
        {shown.length > limit && <Btn kind="ghost" title={`Show more (${shown.length - limit} left)`} onPress={() => setLimit(l => l + 400)} />}
        <Hint>Tap an emoji for its keywords, code points and version.</Hint>
      </Card>
    </>
  );
}

/* ------------------------------------------------------------------ search tester */

const Chips = ({items}: {items?: string[]}) => !items || items.length === 0
  ? <Text style={{color: C.sub}}>—</Text>
  : <Row>{items.map((x, i) => <View key={i} style={{backgroundColor: C.line, borderRadius: 14, paddingHorizontal: 10, paddingVertical: 4, marginRight: 6, marginBottom: 6}}><Text style={{color: C.text, fontSize: 16}}>{x}</Text></View>)}</Row>;
const Lbl = ({children}: any) => <Text style={{color: C.sub, fontSize: 12, marginTop: 8}}>{children}</Text>;

function SearchTester({cfg}: any) {
  const [q, setQ] = useState(''); const [prev, setPrev] = useState(''); const [lang, setLang] = useState('en');
  const [emo, setEmo] = useState<{search: string[]; forWord: string[]} | null>(null);
  const [wd, setWd] = useState<any>(null);
  const [busy, setBusy] = useState(false);

  const run = () => {
    const t = q.trim(); if (!t) return;
    setBusy(true);
    Promise.all([Native.devSearchEmoji(t).then(setEmo), Native.devSearchWord(lang, t.split(/\s+/)[0], prev.trim()).then(setWd)])
      .catch(err).then(() => setBusy(false));
  };
  const face = FACE[cfg.emojiFont || 'system'];
  const emj = (a?: string[]) => !a || !a.length ? <Text style={{color: C.sub}}>—</Text>
    : <Row>{a.map((x, i) => <View key={i} style={{backgroundColor: C.line, borderRadius: 14, paddingHorizontal: 8, paddingVertical: 2, marginRight: 6, marginBottom: 6}}><Text style={{fontSize: 24, fontFamily: face, color: C.text}}>{x}</Text></View>)}</Row>;

  return (
    <>
      <Card title="Search tester">
        <Hint>Runs the keyboard’s real engines on a word, so you can see exactly what it would suggest. Emoji use the font chosen in Appearance.</Hint>
        <Field label="Search / word" value={q} onChangeText={setQ} onSubmitEditing={run} />
        <Field label="Previous word (optional, for next-word & context)" value={prev} onChangeText={setPrev} />
        <Segmented options={[['en', 'English'], ['bn', 'Bangla']]} value={lang} onChange={setLang} />
        <Btn title={busy ? 'Searching…' : 'Search all'} onPress={run} />
      </Card>

      {emo && (
        <Card title="Emoji">
          <Lbl>Emoji panel search</Lbl>{emj(emo.search)}
          <Lbl>Suggestion-bar emoji for this word</Lbl>{emj(emo.forWord)}
        </Card>
      )}
      {wd && (
        <Card title={`Dictionary (${lang})`}>
          <Lbl>Known word</Lbl><Text style={{color: wd.known ? C.accent : C.danger}}>{wd.known ? 'yes' : 'no'}</Text>
          <Lbl>Completions</Lbl><Chips items={wd.complete} />
          <Lbl>Auto-correct</Lbl><Chips items={wd.correct ? [wd.correct] : []} />
          <Lbl>Spelling fixes</Lbl><Chips items={wd.fixes} />
          <Lbl>Next-word predictions (after this word)</Lbl><Chips items={wd.next} />
          {lang === 'bn' && <><Lbl>Bangla roman auto-map</Lbl><Chips items={wd.auto ? [wd.auto] : []} /></>}
        </Card>
      )}
    </>
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
      <Card title="Debug log settings">
        <Text style={{color: C.sub, marginBottom: 6}}>
          Records keyboard start-up steps, errors and crash stack traces so a crash can be tracked down. It never records what you type — that is the separate “Key logs” section.
        </Text>
        <Toggle label="Enable debug log" value={on}
          hint={on ? 'Logging. Reopen the keyboard (switch away and back), then press Refresh below.' : 'Off — nothing is recorded.'}
          onChange={(v: boolean) => update({devDebugLog: v})} />
      </Card>

      <Card title="Log output">
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
      <Card title="Key log settings">
        <Text style={{color: C.sub, marginBottom: 6}}>
          Stores what is typed, swiped, spoken, pasted, picked from suggestions or inserted by emoji, clipboard and plugins, plus special keys, encrypted on this device and organised by date and hour. A password is required to enable, view, export or delete it.
        </Text>
        <Toggle label="Record typing" hint={hasPw ? undefined : 'Set a password first'} value={cfg.devLog && hasPw}
          onChange={(v: boolean) => { if (v && !hasPw) { Alert.alert('Set a password first'); return; } update({devLog: v}); }} />
        <Text style={{color: C.sub, fontSize: 12, marginTop: 8}}>Fields to record</Text>
        <Segmented options={[['skipPasswords', 'Skip password fields'], ['everything', 'Everything']]}
          value={cfg.keyLogScope || 'skipPasswords'} onChange={(v: any) => update({keyLogScope: v})} />
        {cfg.keyLogScope === 'everything' && (
          <Text style={{color: C.danger, fontSize: 12, marginTop: 4}}>
            Passwords, PINs and card numbers typed anywhere will be stored too (marked 🔒, still encrypted). Only use this on a device you control, and delete the log when you are done.
          </Text>
        )}
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
