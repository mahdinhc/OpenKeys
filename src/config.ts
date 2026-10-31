export type Config = {
  theme: 'light' | 'dark' | 'amoled' | 'custom';
  colors: {bg: string; key: string; keyText: string; special: string; accent: string; hint: string};
  heightScale: number; fontScale: number; keyRadius: number; keyGap: number; bottomPad: number;
  oneHanded: 'off' | 'left' | 'right';
  showHints: boolean; popupPreview: boolean;
  haptic: boolean; hapticMs: number; sound: boolean; longPressMs: number;
  suggestions: boolean; autocorrect: boolean; autoCap: boolean; doubleSpacePeriod: boolean;
  numberRow: boolean; swipeTyping: boolean; learnWords: boolean;
  enabledLayouts: string[];
  shortcuts: Record<string, string>;
};

export const DEFAULTS: Config = {
  theme: 'dark',
  colors: {bg: '#202124', key: '#3C4043', keyText: '#E8EAED', special: '#2D2F31', accent: '#8AB4F8', hint: '#9AA0A6'},
  heightScale: 100, fontScale: 100, keyRadius: 8, keyGap: 4, bottomPad: 0,
  oneHanded: 'off',
  showHints: true, popupPreview: true,
  haptic: true, hapticMs: 12, sound: false, longPressMs: 350,
  suggestions: true, autocorrect: true, autoCap: true, doubleSpacePeriod: true,
  numberRow: false, swipeTyping: true, learnWords: true,
  enabledLayouts: ['en_qwerty', 'bn_phonetic'],
  shortcuts: {omw: 'On my way!'},
};

export const PRESETS: Record<string, Config['colors']> = {
  light: {bg: '#E8EAED', key: '#FFFFFF', keyText: '#202124', special: '#CDD1D6', accent: '#1A73E8', hint: '#80868B'},
  dark: {bg: '#202124', key: '#3C4043', keyText: '#E8EAED', special: '#2D2F31', accent: '#8AB4F8', hint: '#9AA0A6'},
  amoled: {bg: '#000000', key: '#1A1A1A', keyText: '#FFFFFF', special: '#0D0D0D', accent: '#BB86FC', hint: '#8A8A8A'},
};
