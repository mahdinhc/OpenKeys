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
  clearUserDictionary: (): Promise<boolean> => N.clearUserDictionary(),
};
