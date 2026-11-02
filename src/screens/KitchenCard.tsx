import React, {useCallback, useEffect, useState} from 'react';
import {Alert, Text} from 'react-native';
import {Native} from '../native';
import {Btn, Card, Field, Stepper, Toggle} from '../ui';

const mb = (b: number) => (b / 1048576).toFixed(1) + ' MB';

export default function KitchenCard({cfg, update}: any) {
  const [st, setSt] = useState<any>(null);
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(() => { Native.kitchenStatus().then(setSt).catch(() => {}); }, []);
  useEffect(() => { refresh(); const t = setInterval(refresh, 3000); return () => clearInterval(t); }, [refresh]);

  const build = async () => {
    setBusy(true);
    try {
      const ok = await Native.kitchenBuildIndex();
      refresh();
      Alert.alert(ok ? 'Done' : 'Failed', ok ? 'Mix list updated. Every pair is now checked offline.' : (st?.error || 'Could not download the mix list. Check your connection.'));
    } catch (e: any) { Alert.alert('Failed', String(e?.message || e)); }
    setBusy(false); refresh();
  };

  return (
    <Card title="Emoji Kitchen">
      <Text style={{color: '#9aa0a6', marginBottom: 6}}>
        {st?.indexed
          ? `Mix list: ${st.pairs.toLocaleString()} pairs, updated ${new Date(st.builtAt).toLocaleDateString()}. Pairs are checked offline and the picker shows only emoji that mix.`
          : st?.building ? 'Downloading the mix list…'
          : 'Mix list not downloaded yet. It downloads by itself the first time you open Emoji Kitchen with internet; until then newer mixes may be missed.'}
        {st?.error ? `\nLast error: ${st.error}` : ''}
      </Text>
      <Btn title={busy || st?.building ? 'Downloading…' : st?.indexed ? 'Update mix list now' : 'Download mix list now'} onPress={build} />
      <Text style={{color: '#9aa0a6', marginVertical: 6}}>Saved mixes: {st ? `${st.cacheFiles} (${mb(st.cacheBytes)})` : '…'} - they open instantly and work offline.</Text>
      <Stepper label="Saved mixes limit" value={cfg.kitchenCacheMb ?? 150} min={0} max={2000} step={50} unit=" MB (0 = no limit)" onChange={(v: number) => update({kitchenCacheMb: v})} />
      <Toggle label="Pre-download mixes" hint="After you pick the first emoji, also download its mixes (up to 150) so they are ready offline. Uses data" value={!!cfg.kitchenPrefetch} onChange={(v: boolean) => update({kitchenPrefetch: v})} />
      <Btn kind="ghost" title="Clear saved mixes" onPress={() => Native.kitchenClearCache().then(() => { refresh(); })} />
      <Field label="Mix list source (advanced, blank = default)" value={cfg.kitchenMetaUrl || ''} onChangeText={(v: string) => update({kitchenMetaUrl: v.trim()})} placeholder="https://raw.githubusercontent.com/xsalazar/emoji-kitchen-backend/main/app/metadata.json" />
    </Card>
  );
}
