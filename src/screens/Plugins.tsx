import React, {useCallback, useEffect, useState} from 'react';
import {Alert, ScrollView, Text, View} from 'react-native';
import {Native} from '../native';
import {Btn, C, Card, Field, Segmented, Toggle} from '../ui';
import {PLUGIN_TEMPLATES} from '../pluginTemplates';

const BLANK = `// A plugin is one JS file. See plugins/README.md in the repo for the full API.
plugin({
  name: "My plugin",
  toolbar: [{
    icon: "⭐",
    run() {
      keys.toast(keys.selected() || "Hello from my plugin");
    },
  }],
});
`;

const idFrom = (s: string) =>
  (s.split('?')[0].split('/').pop() || 'plugin').replace(/\.js$/i, '').toLowerCase().replace(/[^a-z0-9_-]/g, '-').slice(0, 40) || 'plugin';
const descOf = (code: string) => (code.match(/description:\s*["'`]([^"'`]+)["'`]/) || [])[1] || '';
const nameOf = (code: string, fallback: string) => (code.match(/name:\s*["'`]([^"'`]+)["'`]/) || [])[1] || fallback;

export default function Plugins() {
  const [list, setList] = useState<any[]>([]);
  const [samples, setSamples] = useState<{id: string; code: string}[]>([]);
  const [edit, setEdit] = useState<{id: string; code: string; isNew: boolean} | null>(null);
  const [url, setUrl] = useState('');
  const [log, setLog] = useState<string | null>(null);
  const [tab, setTab] = useState<'mine' | 'library' | 'bootstrap'>('mine');
  const [open, setOpen] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setList(await Native.getPlugins());
    setSamples(await Native.getBundledPlugins());
  }, []);
  useEffect(() => { refresh().catch(() => {}); }, [refresh]);

  const fail = (e: any) => Alert.alert('Plugin error', String(e?.message || e));
  const run = (p: Promise<any>) => p.then(refresh).catch(fail);

  // The keyboard reloads plugins in the background and writes their metadata a moment later.
  const refreshSoon = () => { refresh().catch(() => {}); setTimeout(() => refresh().catch(() => {}), 700); };

  const installUrl = async () => {
    try {
      const u = url.trim();
      if (!u) return;
      const text = await (await fetch(u)).text();
      if (/^\s*</.test(text)) throw new Error('That URL returned HTML, not JavaScript. Use the "raw" link.');
      await Native.savePlugin(idFrom(u), text);
      setUrl(''); refreshSoon();
    } catch (e) { fail(e); }
  };

  const openEditor = async (id: string) => setEdit({id, code: await Native.getPluginCode(id), isNew: false});

  const saveEdit = async () => {
    if (!edit) return;
    try {
      await Native.savePlugin(edit.id.trim().toLowerCase(), edit.code);
      setEdit(null); refreshSoon();
    } catch (e) { fail(e); }
  };

  const remove = (p: any) =>
    Alert.alert('Delete plugin?', p.meta?.name || p.id, [
      {text: 'Cancel', style: 'cancel'},
      {text: 'Delete', style: 'destructive', onPress: () => run(Native.deletePlugin(p.id))},
    ]);

  const setSetting = (p: any, key: string, value: any) =>
    Native.setPluginSettings(p.id, {...p.settings, [key]: value}).then(refreshSoon).catch(fail);

  // ------------------------------------------------------------------ editor
  if (edit) {
    return (
      <ScrollView keyboardShouldPersistTaps="handled">
        <Card title={edit.isNew ? 'New plugin' : 'Edit plugin'}>
          <Field label="File name (a-z, 0-9, - and _)" value={edit.id} editable={edit.isNew}
                 onChangeText={(t: string) => setEdit({...edit, id: t})} />
          <Field label="Code" value={edit.code} multiline mono onChangeText={(t: string) => setEdit({...edit, code: t})} />
          <Btn title="Save" onPress={saveEdit} />
          <Btn kind="ghost" title="Cancel" onPress={() => setEdit(null)} />
        </Card>
      </ScrollView>
    );
  }

  // ------------------------------------------------------------------ list
  const installed = new Set(list.map(p => p.id));

  const startFrom = (id: string, code: string) => {
    let n = id, i = 2;
    while (installed.has(n)) n = id + '-' + i++;
    setEdit({id: n, code, isNew: true});
  };

  const Row = ({p}: any) => {
    const m = p.meta || {};
    const schema = m.settings || {};
    const actions = (m.actions || []) as any[];
    const expanded = open === p.id;
    return (
      <Card title={m.name || p.id}>
        {m.description ? <Text style={{color: C.sub, marginBottom: 4}}>{m.description}</Text> : null}
        {m.error ? <Text style={{color: C.danger, marginBottom: 6}}>⚠ {m.error}</Text> : null}
        {!p.meta && p.enabled ? <Text style={{color: C.sub, fontSize: 12}}>Not loaded yet - open the keyboard once.</Text> : null}
        <Toggle label="Enabled" value={p.enabled} onChange={(v: boolean) => run(Native.setPluginEnabled(p.id, v))} />
        <Text style={{color: C.accent, paddingVertical: 8}} onPress={() => setOpen(expanded ? null : p.id)}>
          {expanded ? 'Hide options' : 'Options, edit, delete'}
        </Text>
        {expanded && (
          <View>
            {Object.keys(schema).map(k => {
              const d = schema[k] || {};
              const val = p.settings[k] !== undefined ? p.settings[k] : d.default;
              if (d.type === 'bool') return <Toggle key={k} label={d.label || k} hint={d.hint} value={val} onChange={(v: boolean) => setSetting(p, k, v)} />;
              if (d.type === 'select' && Array.isArray(d.options))
                return (
                  <View key={k}>
                    <Text style={{color: C.sub, fontSize: 12}}>{d.label || k}</Text>
                    <Segmented options={d.options.map((o: string) => [o, o])} value={String(val)} onChange={(v: string) => setSetting(p, k, v)} />
                  </View>
                );
              return (
                <Field
                  key={k} label={d.label || k} defaultValue={val === undefined ? '' : String(val)}
                  multiline={d.type === 'textarea'} keyboardType={d.type === 'number' ? 'numeric' : 'default'}
                  onEndEditing={(e: any) => {
                    const t = e.nativeEvent.text;
                    setSetting(p, k, d.type === 'number' ? Number(t) : t);
                  }}
                />
              );
            })}
            {actions.length > 0 && (
              <Text style={{color: C.sub, fontSize: 12, marginTop: 4}}>
                {actions.map(a => a.icon + '  ' + (a.toolbar ? 'toolbar' : 'layout key only') + '  {js=' + a.key + '}').join('\n')}
              </Text>
            )}
            <View style={{flexDirection: 'row', marginTop: 6}}>
              <View style={{flex: 1, marginRight: 6}}><Btn kind="ghost" title="Edit code" onPress={() => openEditor(p.id)} /></View>
              <View style={{flex: 1}}><Btn kind="danger" title="Delete" onPress={() => remove(p)} /></View>
            </View>
          </View>
        )}
      </Card>
    );
  };

  return (
    <ScrollView keyboardShouldPersistTaps="handled">
      <Segmented
        options={[['mine', 'My plugins (' + list.length + ')'], ['library', 'Library'], ['bootstrap', 'Bootstrap']]}
        value={tab} onChange={(v: any) => setTab(v)} />

      {tab === 'mine' && (
        <View>
          <Card title="About">
            <Text style={{color: C.sub, fontSize: 13}}>
              Plugins are plain JavaScript files that run inside the keyboard and can see and change what you type. Only install code you trust.
              Changes apply instantly - open any text field to try them.
            </Text>
          </Card>
          {list.length === 0 && (
            <Card title="Nothing installed yet">
              <Text style={{color: C.sub}}>Add ready-made plugins from the Library tab, or write your own from the Bootstrap tab.</Text>
            </Card>
          )}
          {list.map(p => <Row key={p.id} p={p} />)}
          <Card title="Install from URL">
            <Field label="Raw .js link (a GitHub raw file or gist)" value={url} onChangeText={setUrl} placeholder="https://.../my-plugin.js" />
            <Btn title="Install from URL" onPress={installUrl} />
          </Card>
        </View>
      )}

      {tab === 'library' && (
        <Card title="Ready-made plugins">
          {samples.length === 0 && <Text style={{color: C.sub}}>No bundled plugins found.</Text>}
          {samples.map(s => (
            <View key={s.id} style={{flexDirection: 'row', alignItems: 'center', paddingVertical: 6}}>
              <View style={{flex: 1}}>
                <Text style={{color: C.text}}>{nameOf(s.code, s.id)}</Text>
                {descOf(s.code) ? <Text style={{color: C.sub, fontSize: 12}}>{descOf(s.code)}</Text> : null}
              </View>
              {installed.has(s.id)
                ? <Text style={{color: C.sub}}>installed</Text>
                : <Text style={{color: C.accent, padding: 8}} onPress={() => Native.savePlugin(s.id, s.code).then(refreshSoon).catch(fail)}>Install</Text>}
            </View>
          ))}
        </Card>
      )}

      {tab === 'bootstrap' && (
        <View>
          <Card title="Start a new plugin">
            <Text style={{color: C.sub, fontSize: 13, marginBottom: 6}}>
              Pick a starter: it opens in the editor with working code and comments, ready to change and save. Full API: plugins/README.md in the repo.
            </Text>
            {PLUGIN_TEMPLATES.map(t => (
              <View key={t.id} style={{flexDirection: 'row', alignItems: 'center', paddingVertical: 6}}>
                <View style={{flex: 1}}>
                  <Text style={{color: C.text}}>{t.title}</Text>
                  {t.about ? <Text style={{color: C.sub, fontSize: 12}}>{t.about}</Text> : null}
                </View>
                <Text style={{color: C.accent, padding: 8}} onPress={() => startFrom(t.id, t.code)}>Use</Text>
              </View>
            ))}
            <Btn kind="ghost" title="Empty file" onPress={() => startFrom('my-plugin', BLANK)} />
          </Card>
        </View>
      )}

      <Card title="Log">
        {log === null ? (
          <Btn kind="ghost" title="Show log" onPress={async () => setLog(await Native.getPluginLog())} />
        ) : (
          <>
            <Text selectable style={{color: C.text, fontFamily: 'monospace', fontSize: 11}}>{log || '(empty - console.log output and errors show up here)'}</Text>
            <Btn kind="ghost" title="Refresh" onPress={async () => setLog(await Native.getPluginLog())} />
            <Btn kind="ghost" title="Clear" onPress={async () => { await Native.clearPluginLog(); setLog(''); }} />
            <Btn kind="ghost" title="Hide" onPress={() => setLog(null)} />
          </>
        )}
      </Card>
      <View style={{height: 24}} />
    </ScrollView>
  );
}
