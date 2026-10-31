import {NativeModules} from 'react-native';

const N = NativeModules.OpenKeys;

export type LayoutInfo = {id: string; name: string; builtin: boolean; overridden: boolean; layout: any};

export const Native = {
  status: (): Promise<{enabled: boolean; selected: boolean}> => N.getStatus(),
  openImeSettings: (): void => N.openImeSettings(),
  showPicker: (): void => N.showPicker(),
  getConfig: async (): Promise<any> => JSON.parse(await N.getConfig()),
  setConfig: (c: any): Promise<boolean> => N.setConfig(JSON.stringify(c)),
  getLayouts: async (): Promise<LayoutInfo[]> => JSON.parse(await N.getLayouts()),
  saveLayout: (l: any): Promise<boolean> => N.saveLayout(JSON.stringify(l)),
  deleteLayout: (id: string): Promise<boolean> => N.deleteLayout(id),
  exportBackup: (): Promise<string> => N.exportBackup(),
  importBackup: (s: string): Promise<boolean> => N.importBackup(s),
  pickFont: (): Promise<string> => N.pickFont(),
  clearFont: (): Promise<boolean> => N.clearFont(),
  logHasPassword: (): Promise<boolean> => N.logHasPassword(),
  logSetPassword: (o: string, n: string): Promise<boolean> => N.logSetPassword(o, n),
  logVerify: (pw: string): Promise<boolean> => N.logVerify(pw),
  logIndex: async (pw: string): Promise<Record<string, Record<string, number>>> => JSON.parse(await N.logIndex(pw)),
  logRead: async (pw: string, d: string, h: string): Promise<{t: number; app: string; s: string}[]> => JSON.parse(await N.logRead(pw, d, h)),
  logClear: (pw: string): Promise<boolean> => N.logClear(pw),
  logExport: (pw: string): Promise<string> => N.logExport(pw),
  logImport: (json: string, pw: string): Promise<number> => N.logImport(json, pw),
  clearUserDictionary: (): Promise<boolean> => N.clearUserDictionary(),
};
