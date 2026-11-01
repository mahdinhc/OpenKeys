import React, {useState} from 'react';
import {Alert, ScrollView, Share, Text, TouchableOpacity, View} from 'react-native';
import {Icon} from '../Icon';
import {Native} from '../native';
import {Btn, C, Card, Field, Segmented, Stepper, Toggle} from '../ui';

export default function Typing({cfg, update}: any) {
  const [trig, setTrig] = useState('');
  const [exp, setExp] = useState('');
  const [backup, setBackup] = useState('');
  const T = (k: string) => (v: boolean) => update({[k]: v});

  const addShortcut = () => {
    if (!trig.trim() || !exp) return;
    update({shortcuts: {...cfg.shortcuts, [trig.trim().toLowerCase()]: exp}});
    setTrig(''); setExp('');
  };
  const removeShortcut = (k: string) => {
    const n = {...cfg.shortcuts}; delete n[k]; update({shortcuts: n});
  };
  const doExport = async () => { const j = await Native.exportBackup(); setBackup(j); Share.share({message: j}).catch(() => {}); };
  const doImport = async () => {
    try { await Native.importBackup(backup); Alert.alert('Imported', 'Restart this app to reload settings.'); }
    catch (e: any) { Alert.alert('Import failed', String(e?.message || e)); }
  };

  return (
    <ScrollView>
      <Card title="Writing">
        <Toggle label="Suggestions" hint="Word completion and next-word prediction" value={cfg.suggestions} onChange={T('suggestions')} />
        {cfg.suggestions && (
          <View style={{marginBottom: 6}}>
            <Text style={{color: C.text, fontSize: 15, marginTop: 4}}>Show suggestions in</Text>
            <Segmented
              options={[['strict', 'App decides'], ['moderate', 'Moderate'], ['everywhere', 'Everywhere']]}
              value={cfg.suggestMode || 'moderate'} onChange={(v: any) => update({suggestMode: v})} />
            <Text style={{color: C.sub, fontSize: 12}}>
              {(cfg.suggestMode || 'moderate') === 'strict'
                ? 'Follows each app: no suggestions where an app turns them off (many search bars, chat boxes), or in email and web-address fields.'
                : cfg.suggestMode === 'everywhere'
                ? 'Every text field, including email and web-address fields and apps that ask for none. Only password and number/phone fields are skipped. In email, web-address and "no suggestions" fields nothing is auto-corrected, expanded or learned.'
                : 'Also in search bars and in fields where an app opted out. Skipped in password, number/phone, email and web-address fields (search bars with a search key excepted). In "no suggestions" fields nothing is auto-corrected or learned.'}
            </Text>
          </View>
        )}
        <Toggle label="Auto-correct" hint="Backspace right after a correction undoes it" value={cfg.autocorrect} onChange={T('autocorrect')} />
        <Toggle label="Auto-capitalize" value={cfg.autoCap} onChange={T('autoCap')} />
        <Toggle label="Double-space for period" value={cfg.doubleSpacePeriod} onChange={T('doubleSpacePeriod')} />
        <Toggle label="Swipe typing" hint="Glide across letters (English)" value={cfg.swipeTyping} onChange={T('swipeTyping')} />
        <Toggle label="Emoji suggestions" hint="Suggest emoji in the bar (heart → ❤️)" value={cfg.emojiSuggest} onChange={T('emojiSuggest')} />
        <Toggle label="Urban Dictionary words" hint="Slang, internet words and names (yeet, bruh, thicc). Added to suggestions and no longer auto-corrected. Off by default; may include crude words" value={!!cfg.urbanWords} onChange={T('urbanWords')} />
        <Toggle label="Learn new words" value={cfg.learnWords} onChange={T('learnWords')} />
        <Btn kind="ghost" title="Clear learned words" onPress={() => Native.clearUserDictionary().then(() => Alert.alert('Done', 'Learned words cleared.'))} />
      </Card>

      <Card title="Feedback">
        <Toggle label="Vibrate on key press" value={cfg.haptic} onChange={T('haptic')} />
        {cfg.haptic && <Stepper label="Vibration length" value={cfg.hapticMs} min={0} max={40} step={2} unit="ms" onChange={(v: number) => update({hapticMs: v})} />}
        <Toggle label="Sound on key press" value={cfg.sound} onChange={T('sound')} />
        <Stepper label="Long-press delay" value={cfg.longPressMs} min={150} max={700} step={50} unit="ms" onChange={(v: number) => update({longPressMs: v})} />
      </Card>

      <Card title="Number row long-press">
        <Text style={{color: C.sub, marginBottom: 6}}>Characters shown when you hold a digit. Separate with spaces (up to 9).</Text>
        {['1','2','3','4','5','6','7','8','9','0'].map(d => (
          <Field key={d} label={`Hold ${d}`} value={(cfg.numberAlts || {})[d] ?? ''}
            onChangeText={(v: string) => update({numberAlts: {...(cfg.numberAlts || {}), [d]: v}})} placeholder="¹ ½ ⅓" />
        ))}
      </Card>

      <Card title="Text shortcuts">
        {Object.entries(cfg.shortcuts || {}).map(([k, v]: any) => (
          <View key={k} style={{flexDirection: 'row', alignItems: 'center', paddingVertical: 4}}>
            <Text style={{color: C.accent, width: 80}}>{k}</Text>
            <Text style={{color: C.text, flex: 1}} numberOfLines={1}>{v}</Text>
            <TouchableOpacity style={{padding: 8}} onPress={() => removeShortcut(k)}><Icon name="close" size={18} color={C.danger} /></TouchableOpacity>
          </View>
        ))}
        <Field label="Shortcut" value={trig} onChangeText={setTrig} placeholder="brb" />
        <Field label="Expands to" value={exp} onChangeText={setExp} placeholder="be right back" />
        <Btn title="Add shortcut" onPress={addShortcut} />
      </Card>

      <Card title="Backup">
        <Btn title="Export settings + custom languages" onPress={doExport} />
        <Field label="Paste a backup here to import" value={backup} onChangeText={setBackup} multiline mono />
        <Btn kind="ghost" title="Import" onPress={doImport} />
      </Card>
    </ScrollView>
  );
}
