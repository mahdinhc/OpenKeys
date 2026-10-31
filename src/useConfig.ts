import {useCallback, useEffect, useRef, useState} from 'react';
import {Native} from './native';
import {Config, DEFAULTS} from './config';

export function useConfig() {
  const ref = useRef<Config>(DEFAULTS);
  const timer = useRef<any>(null);
  const [cfg, setCfg] = useState<Config | null>(null);

  useEffect(() => {
    Native.getConfig()
      .then(c => { ref.current = {...DEFAULTS, ...c}; setCfg(ref.current); })
      .catch(() => setCfg(DEFAULTS));
  }, []);

  const update = useCallback((patch: Partial<Config>) => {
    ref.current = {...ref.current, ...patch};
    setCfg(ref.current);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => Native.setConfig(ref.current).catch(() => {}), 150);
  }, []);

  return {cfg, update};
}
