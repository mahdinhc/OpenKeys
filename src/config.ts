export type Config = {
  theme: 'light' | 'dark' | 'amoled' | 'custom';
  colors: {bg: string; key: string; keyText: string; special: string; accent: string; hint: string};
  heightScale: number; fontScale: number; keyRadius: number; keyGap: number; bottomPad: number;
  oneHanded: 'off' | 'left' | 'right';
  showHints: boolean; popupPreview: boolean;
  haptic: boolean; hapticMs: number; sound: boolean; longPressMs: number;
  suggestions: boolean; autocorrect: boolean; autoCap: boolean; doubleSpacePeriod: boolean;
  numberRow: boolean; swipeTyping: boolean; learnWords: boolean;
  fontFamily: string; fontBold: boolean; customFontName: string;
  emojiCols: number; emojiSize: number; emojiHeaders: boolean; emojiRecents: boolean; emojiHidden: string[];
  devMode: boolean; devLog: boolean;
  enabledLayouts: string[];
  shortcuts: Record<string, string>;
};

export const DEFAULTS: Config = {
  theme: 'dark',
  colors: {bg: '#2B2E32', key: '#494C50', keyText: '#E8EAED', special: '#383B3F', accent: '#669DF6', hint: '#B0B5BA'},
  heightScale: 100, fontScale: 100, keyRadius: 12, keyGap: 4, bottomPad: 0,
  oneHanded: 'off',
  showHints: true, popupPreview: true,
  haptic: true, hapticMs: 12, sound: false, longPressMs: 350,
  suggestions: true, autocorrect: true, autoCap: true, doubleSpacePeriod: true,
  numberRow: true, swipeTyping: true, learnWords: true,
  fontFamily: 'default', fontBold: false, customFontName: '',
  emojiCols: 8, emojiSize: 100, emojiHeaders: true, emojiRecents: true, emojiHidden: [],
  devMode: false, devLog: false,
  enabledLayouts: ['en_qwerty', 'bn_phonetic'],
  shortcuts: {omw: 'On my way!'},
};

export const PRESETS: Record<string, Config['colors']> = {
  light: {bg: '#E3E6EA', key: '#FFFFFF', keyText: '#202124', special: '#C9CED4', accent: '#1A73E8', hint: '#5F6368'},
  dark: {bg: '#2B2E32', key: '#494C50', keyText: '#E8EAED', special: '#383B3F', accent: '#669DF6', hint: '#B0B5BA'},
  amoled: {bg: '#000000', key: '#1A1A1A', keyText: '#FFFFFF', special: '#0D0D0D', accent: '#BB86FC', hint: '#8A8A8A'},
};

export const APP_VERSION = '1.0.0';
export const FONTS: [string, string][] = [['default', 'Default'], ['serif', 'Serif'], ['mono', 'Mono'], ['condensed', 'Condensed'], ['casual', 'Casual'], ['cursive', 'Cursive'], ['custom', 'Custom file']];
export const EMOJI_GROUPS = ['Smileys & Emotions', 'People & Body', 'Animals & Nature', 'Food & Drink', 'Travel & Places', 'Activities', 'Objects', 'Symbols', 'Flags'];
