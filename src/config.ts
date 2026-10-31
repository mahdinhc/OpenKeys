export type Config = {
  theme: 'light' | 'dark' | 'amoled' | 'custom';
  colors: {bg: string; key: string; keyText: string; special: string; accent: string; hint: string};
  heightScale: number; fontScale: number; keyRadius: number; keyGap: number; bottomPad: number;
  oneHanded: 'off' | 'left' | 'right';
  showHints: boolean; popupPreview: boolean;
  haptic: boolean; hapticMs: number; sound: boolean; longPressMs: number;
  suggestions: boolean; autocorrect: boolean; autoCap: boolean; doubleSpacePeriod: boolean;
  numberRow: boolean; swipeTyping: boolean; learnWords: boolean; emojiSuggest: boolean;
  fontFamily: string; fontBold: boolean; customFontName: string;
  emojiCols: number; emojiSize: number; emojiHeaders: boolean; emojiRecents: boolean; emojiHidden: string[];
  devMode: boolean; devLog: boolean; devDebugLog: boolean;
  topTools: string[]; topIconSize: number; emojiHeight: number; pianoHeight: number;
  pianoWave: string; pianoAttack: number; pianoDecay: number; pianoSustain: number; pianoRelease: number;
  pianoVolume: number; pianoCutoff: number; pianoVibRate: number; pianoVibDepth: number; pianoDetune: number; pianoGate: number;
  pianoKeys: number; pianoOctave: number; pianoBlackH: number; pianoLabels: boolean;
  pianoWhite: string; pianoBlack: string; pianoPress: string;
  pianoSound: boolean; pianoRecord: boolean; pianoTiming: boolean; pianoChordMs: number; pianoQuant: number;
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
  numberRow: true, swipeTyping: true, learnWords: true, emojiSuggest: true,
  fontFamily: 'default', fontBold: false, customFontName: '',
  emojiCols: 8, emojiSize: 100, emojiHeaders: true, emojiRecents: true, emojiHidden: [],
  devMode: false, devLog: false, devDebugLog: false,
  topTools: ['emoji', 'clip', 'piano', 'settings'], topIconSize: 50, emojiHeight: 100, pianoHeight: 100,
  pianoWave: 'sine', pianoAttack: 5, pianoDecay: 120, pianoSustain: 60, pianoRelease: 200,
  pianoVolume: 80, pianoCutoff: 100, pianoVibRate: 5, pianoVibDepth: 0, pianoDetune: 0, pianoGate: 0,
  pianoKeys: 14, pianoOctave: 4, pianoBlackH: 62, pianoLabels: true,
  pianoWhite: '#FFFFFF', pianoBlack: '#1A1A1A', pianoPress: '#669DF6',
  pianoSound: true, pianoRecord: true, pianoTiming: true, pianoChordMs: 60, pianoQuant: 50,
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
export const TOP_TOOLS: [string, string][] = [['emoji', 'Emoji'], ['clip', 'Clipboard'], ['piano', 'Piano'], ['hand', 'One-handed mode'], ['settings', 'Settings']];
