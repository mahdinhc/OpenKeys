export type Config = {
  theme: string;
  colors: {bg: string; key: string; keyText: string; special: string; accent: string; hint: string};
  heightScale: number; fontScale: number; keyRadius: number; keyGap: number; bottomPad: number;
  oneHanded: 'off' | 'left' | 'right';
  showHints: boolean; popupPreview: boolean;
  haptic: boolean; hapticMs: number; sound: boolean; longPressMs: number;
  suggestions: boolean; suggestMode: 'strict' | 'moderate' | 'everywhere'; autocorrect: boolean; autoCap: boolean; doubleSpacePeriod: boolean;
  numberRow: boolean; swipeTyping: boolean; learnWords: boolean; emojiSuggest: boolean; urbanWords: boolean; animeWords: boolean;
  fontFamily: string; fontBold: boolean; fontItalic: boolean; customFontName: string;
  keyAlpha: number; keyBorder: number; keyShadow: boolean;
  bgName: string; bgPath: string; bgRev: number; bgFit: string; bgBlend: string;
  bgOpacity: number; bgBlur: number; bgGray: number; bgSepia: number; bgBright: number; bgContrast: number; bgSat: number; bgHue: number;
  bgInvert: boolean; bgDim: number; bgTint: string; bgTintAmt: number; bgFlipX: boolean; bgScale: number; bgPanels: number;
  emojiFont: 'system' | 'noto' | 'colr'; kitchenCacheMb: number; kitchenPrefetch: boolean; kitchenMetaUrl: string;
  emojiCols: number; emojiSize: number; emojiHeaders: boolean; emojiRecents: boolean; emojiHidden: string[];
  devMode: boolean; devLog: boolean; keyLogScope: 'skipPasswords' | 'everything'; devDebugLog: boolean;
  topTools: string[]; topIconSize: number; emojiHeight: number; pianoHeight: number;
  pianoWave: string; pianoAttack: number; pianoDecay: number; pianoSustain: number; pianoRelease: number;
  pianoVolume: number; pianoCutoff: number; pianoVibRate: number; pianoVibDepth: number; pianoDetune: number; pianoGate: number;
  pianoKeys: number; pianoOctave: number; pianoBlackH: number; pianoBlackW: number; pianoLabels: boolean;
  pianoWhite: string; pianoBlack: string; pianoPress: string;
  pianoSound: boolean; pianoRecord: boolean; pianoTiming: boolean; pianoChordMs: number; pianoQuant: number;
  enabledLayouts: string[];
  shortcuts: Record<string, string>;
  numberAlts: Record<string, string>;
};

export const DEFAULTS: Config = {
  theme: 'dark',
  colors: {bg: '#2B2E32', key: '#494C50', keyText: '#E8EAED', special: '#383B3F', accent: '#669DF6', hint: '#B0B5BA'},
  heightScale: 100, fontScale: 100, keyRadius: 12, keyGap: 4, bottomPad: 0,
  oneHanded: 'off',
  showHints: true, popupPreview: true,
  haptic: true, hapticMs: 12, sound: false, longPressMs: 350,
  suggestions: true, suggestMode: 'moderate', autocorrect: true, autoCap: true, doubleSpacePeriod: true,
  numberRow: true, swipeTyping: true, learnWords: true, emojiSuggest: true, urbanWords: false, animeWords: false,
  fontFamily: 'default', fontBold: false, fontItalic: false, customFontName: '',
  keyAlpha: 100, keyBorder: 0, keyShadow: true,
  bgName: '', bgPath: '', bgRev: 0, bgFit: 'cover', bgBlend: 'normal',
  bgOpacity: 100, bgBlur: 0, bgGray: 0, bgSepia: 0, bgBright: 100, bgContrast: 100, bgSat: 100, bgHue: 0,
  bgInvert: false, bgDim: 0, bgTint: '#000000', bgTintAmt: 0, bgFlipX: false, bgScale: 100, bgPanels: 80,
  emojiFont: 'system', kitchenCacheMb: 150, kitchenPrefetch: false, kitchenMetaUrl: '',
  emojiCols: 8, emojiSize: 100, emojiHeaders: true, emojiRecents: true, emojiHidden: [],
  devMode: false, devLog: false, keyLogScope: 'skipPasswords', devDebugLog: false,
  topTools: ['emoji', 'clip', 'piano', 'settings'], topIconSize: 50, emojiHeight: 100, pianoHeight: 100,
  pianoWave: 'sine', pianoAttack: 5, pianoDecay: 120, pianoSustain: 60, pianoRelease: 200,
  pianoVolume: 80, pianoCutoff: 100, pianoVibRate: 5, pianoVibDepth: 0, pianoDetune: 0, pianoGate: 0,
  pianoKeys: 14, pianoOctave: 4, pianoBlackH: 62, pianoBlackW: 60, pianoLabels: true,
  pianoWhite: '#FFFFFF', pianoBlack: '#1A1A1A', pianoPress: '#669DF6',
  pianoSound: true, pianoRecord: true, pianoTiming: true, pianoChordMs: 60, pianoQuant: 50,
  enabledLayouts: ['en_qwerty', 'bn_phonetic'],
  shortcuts: {omw: 'On my way!'},
  numberAlts: {'1': '¹ ½ ⅓ ¼ ⅕ ⅙ ⅐ ⅛ ⅑', '2': '² ⅔ ⅖', '3': '³ ¾ ⅗ ⅜', '4': '⁴ ⅘', '5': '⁵ ⅚ ⅝', '6': '⁶', '7': '⁷ ⅞', '8': '⁸ ∞', '9': '⁹', '0': '⁰ ⁿ ∅ °'},
};

export const PRESETS: Record<string, Config['colors']> = {
  light: {bg: '#E3E6EA', key: '#FFFFFF', keyText: '#202124', special: '#C9CED4', accent: '#1A73E8', hint: '#5F6368'},
  dark: {bg: '#2B2E32', key: '#494C50', keyText: '#E8EAED', special: '#383B3F', accent: '#669DF6', hint: '#B0B5BA'},
  amoled: {bg: '#000000', key: '#1A1A1A', keyText: '#FFFFFF', special: '#0D0D0D', accent: '#BB86FC', hint: '#8A8A8A'},
  system: {bg: '#2B2E32', key: '#494C50', keyText: '#E8EAED', special: '#383B3F', accent: '#669DF6', hint: '#B0B5BA'},
  midnight: {bg: '#0B1026', key: '#1B2447', keyText: '#E6EBFF', special: '#131A38', accent: '#7AA2FF', hint: '#8F9BC7'},
  ocean: {bg: '#0A2A3A', key: '#14465C', keyText: '#E3F6FF', special: '#0E3649', accent: '#2DD4BF', hint: '#8FBBCB'},
  forest: {bg: '#14231A', key: '#25402D', keyText: '#E6F4EA', special: '#1B3022', accent: '#7BD88F', hint: '#9BB8A3'},
  sunset: {bg: '#2A1420', key: '#4A2536', keyText: '#FFE9E0', special: '#381B29', accent: '#FF8A65', hint: '#C99C9C'},
  cherry: {bg: '#240A12', key: '#431A28', keyText: '#FFE6EC', special: '#31101B', accent: '#FF4D6D', hint: '#C58A98'},
  mocha: {bg: '#2B211C', key: '#4A3B33', keyText: '#F3E5D8', special: '#382C25', accent: '#D9A066', hint: '#B5A294'},
  nord: {bg: '#2E3440', key: '#434C5E', keyText: '#ECEFF4', special: '#3B4252', accent: '#88C0D0', hint: '#9AA5B8'},
  dracula: {bg: '#282A36', key: '#44475A', keyText: '#F8F8F2', special: '#343746', accent: '#BD93F9', hint: '#8D93B5'},
  solarized_dark: {bg: '#002B36', key: '#073642', keyText: '#EEE8D5', special: '#00212B', accent: '#B58900', hint: '#839496'},
  gruvbox: {bg: '#282828', key: '#3C3836', keyText: '#EBDBB2', special: '#32302F', accent: '#FABD2F', hint: '#A89984'},
  graphite: {bg: '#1C1C1E', key: '#2C2C2E', keyText: '#F2F2F7', special: '#232325', accent: '#0A84FF', hint: '#8E8E93'},
  cyberpunk: {bg: '#0D0221', key: '#261447', keyText: '#F5F5F5', special: '#190A33', accent: '#FF2A6D', hint: '#05D9E8'},
  hc_dark: {bg: '#000000', key: '#1F1F1F', keyText: '#FFFFFF', special: '#111111', accent: '#FFD400', hint: '#CCCCCC'},
  rose: {bg: '#FCE4EC', key: '#FFFFFF', keyText: '#3E1F2B', special: '#F3C6D3', accent: '#D81B60', hint: '#8E5A6B'},
  lavender: {bg: '#ECE6F7', key: '#FFFFFF', keyText: '#2B2340', special: '#D6CCEB', accent: '#7E57C2', hint: '#6F6590'},
  mint: {bg: '#E0F2EC', key: '#FFFFFF', keyText: '#1B3B30', special: '#C3E3D8', accent: '#00A67E', hint: '#5B8A7A'},
  sand: {bg: '#EFE6D8', key: '#FFFBF3', keyText: '#3B3224', special: '#DDD0BB', accent: '#C77D2E', hint: '#8A7A62'},
  solarized_light: {bg: '#FDF6E3', key: '#FFFFFF', keyText: '#586E75', special: '#EEE8D5', accent: '#268BD2', hint: '#93A1A1'},
  paper: {bg: '#FFFFFF', key: '#F0F0F0', keyText: '#000000', special: '#DDDDDD', accent: '#0057D9', hint: '#444444'},
};

// shown in this order in Appearance (keep in sync with Theme.kt)
export const THEMES: [string, string][] = [
  ['system', 'Auto'], ['light', 'Light'], ['dark', 'Dark'], ['amoled', 'AMOLED'], ['graphite', 'Graphite'], ['midnight', 'Midnight'], ['ocean', 'Ocean'],
  ['forest', 'Forest'], ['sunset', 'Sunset'], ['cherry', 'Cherry'], ['mocha', 'Mocha'], ['nord', 'Nord'], ['dracula', 'Dracula'],
  ['solarized_dark', 'Solarized Dark'], ['gruvbox', 'Gruvbox'], ['cyberpunk', 'Cyberpunk'], ['hc_dark', 'High contrast'],
  ['rose', 'Rose'], ['lavender', 'Lavender'], ['mint', 'Mint'], ['sand', 'Sand'], ['solarized_light', 'Solarized Light'], ['paper', 'Paper'],
  ['custom', 'Custom'],
];

export const APP_VERSION = '1.0.0';
// Only system fonts that exist on every Android device (Roboto / Noto family) + a custom file.
export const FONTS: [string, string][] = [['default', 'Default'], ['light', 'Light'], ['medium', 'Medium'], ['black', 'Black'], ['condensed', 'Condensed'], ['serif', 'Serif'], ['mono', 'Mono'], ['custom', 'Custom file']];
export const BG_BLENDS: [string, string][] = [['normal', 'Normal'], ['multiply', 'Multiply'], ['screen', 'Screen'], ['overlay', 'Overlay'], ['darken', 'Darken'], ['lighten', 'Lighten'], ['add', 'Add']];
export const BG_FITS: [string, string][] = [['cover', 'Fill'], ['fit', 'Fit'], ['stretch', 'Stretch'], ['tile', 'Tile']];
export const EMOJI_GROUPS = ['Smileys & Emotions', 'People & Body', 'Animals & Nature', 'Food & Drink', 'Travel & Places', 'Activities', 'Objects', 'Symbols', 'Flags'];
export const TOP_TOOLS: [string, string][] = [['emoji', 'Emoji'], ['clip', 'Clipboard'], ['piano', 'Piano'], ['hand', 'One-handed mode'], ['settings', 'Settings']];
