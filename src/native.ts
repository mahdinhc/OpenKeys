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
  playMelody: (t: string, speed: number): Promise<boolean> => N.playMelody(t, speed),
  stopMelody: (): Promise<boolean> => N.stopMelody(),
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
  debugLogRead: (maxChars: number): Promise<string> => N.debugLogRead(maxChars),
  debugLogClear: (): Promise<boolean> => N.debugLogClear(),
  debugLogWrite: (msg: string): Promise<boolean> => N.debugLogWrite(msg),
  clearUserDictionary: (): Promise<boolean> => N.clearUserDictionary(),
  // plugins
  getPlugins: async (): Promise<any[]> => JSON.parse(await N.getPlugins()),
  getPluginCode: (id: string): Promise<string> => N.getPluginCode(id),
  savePlugin: (id: string, code: string): Promise<boolean> => N.savePlugin(id, code),
  deletePlugin: (id: string): Promise<boolean> => N.deletePlugin(id),
  setPluginEnabled: (id: string, on: boolean): Promise<boolean> => N.setPluginEnabled(id, on),
  setPluginSettings: (id: string, values: any): Promise<boolean> => N.setPluginSettings(id, JSON.stringify(values)),
  getBundledPlugins: async (): Promise<{id: string; code: string}[]> => JSON.parse(await N.getBundledPlugins()),
  getPluginLog: (): Promise<string> => N.getPluginLog(),
  clearPluginLog: (): Promise<boolean> => N.clearPluginLog(),
};
