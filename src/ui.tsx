import React from 'react';
import {Switch, Text, TextInput, TouchableOpacity, View, StyleSheet} from 'react-native';

export const C = {bg: '#0f1115', card: '#181b22', text: '#e8eaed', sub: '#9aa0a6', accent: '#8ab4f8', line: '#2a2f3a', danger: '#f28b82'};

export const Card = ({title, children}: any) => (
  <View style={s.card}>
    {title ? <Text style={s.cardTitle}>{title}</Text> : null}
    {children}
  </View>
);

export const Toggle = ({label, hint, value, onChange}: any) => (
  <View style={s.row}>
    <View style={{flex: 1, paddingRight: 12}}>
      <Text style={s.label}>{label}</Text>
      {hint ? <Text style={s.hint}>{hint}</Text> : null}
    </View>
    <Switch value={!!value} onValueChange={onChange} trackColor={{true: C.accent, false: C.line}} thumbColor="#fff" />
  </View>
);

export const Stepper = ({label, value, onChange, min, max, step = 1, unit = ''}: any) => (
  <View style={s.row}>
    <Text style={[s.label, {flex: 1}]}>{label}</Text>
    <TouchableOpacity style={s.step} onPress={() => onChange(Math.max(min, value - step))}><Text style={s.stepT}>−</Text></TouchableOpacity>
    <Text style={s.val}>{value}{unit}</Text>
    <TouchableOpacity style={s.step} onPress={() => onChange(Math.min(max, value + step))}><Text style={s.stepT}>+</Text></TouchableOpacity>
  </View>
);

export const Segmented = ({options, value, onChange}: {options: [string, string][]; value: string; onChange: (v: string) => void}) => (
  <View style={s.seg}>
    {options.map(([v, l]) => (
      <TouchableOpacity key={v} style={[s.segI, value === v && {backgroundColor: C.accent}]} onPress={() => onChange(v)}>
        <Text style={{color: value === v ? '#0f1115' : C.text, fontWeight: '600'}}>{l}</Text>
      </TouchableOpacity>
    ))}
  </View>
);

export const Btn = ({title, onPress, kind}: any) => (
  <TouchableOpacity onPress={onPress} style={[s.btn, kind === 'ghost' && {backgroundColor: 'transparent', borderWidth: 1, borderColor: C.line}, kind === 'danger' && {backgroundColor: C.danger}]}>
    <Text style={{color: kind === 'ghost' ? C.text : '#0f1115', fontWeight: '700'}}>{title}</Text>
  </TouchableOpacity>
);

export const Field = ({label, multiline, mono, ...rest}: any) => (
  <View style={{marginVertical: 6}}>
    {label ? <Text style={s.hint}>{label}</Text> : null}
    <TextInput
      {...rest} multiline={multiline} placeholderTextColor={C.sub} autoCapitalize="none" autoCorrect={false}
      style={[s.input, multiline && {minHeight: 90, textAlignVertical: 'top'}, mono && {fontFamily: 'monospace'}]}
    />
  </View>
);

export const Chip = ({label, on, onPress}: any) => (
  <TouchableOpacity onPress={onPress} style={[s.chip, on && {backgroundColor: C.accent}]}>
    <Text style={{color: on ? '#0f1115' : C.text}}>{label}</Text>
  </TouchableOpacity>
);

export const s = StyleSheet.create({
  card: {backgroundColor: C.card, borderRadius: 14, padding: 14, marginHorizontal: 12, marginTop: 12},
  cardTitle: {color: C.accent, fontWeight: '700', marginBottom: 8, fontSize: 13, letterSpacing: 0.6, textTransform: 'uppercase'},
  row: {flexDirection: 'row', alignItems: 'center', paddingVertical: 8},
  label: {color: C.text, fontSize: 15},
  hint: {color: C.sub, fontSize: 12, marginBottom: 2},
  step: {width: 34, height: 34, borderRadius: 17, backgroundColor: C.line, alignItems: 'center', justifyContent: 'center'},
  stepT: {color: C.text, fontSize: 20, lineHeight: 22},
  val: {color: C.text, minWidth: 56, textAlign: 'center'},
  seg: {flexDirection: 'row', backgroundColor: C.line, borderRadius: 10, overflow: 'hidden', marginVertical: 6},
  segI: {flex: 1, alignItems: 'center', paddingVertical: 9},
  btn: {backgroundColor: C.accent, paddingVertical: 11, paddingHorizontal: 16, borderRadius: 10, alignItems: 'center', marginVertical: 4},
  input: {backgroundColor: '#0f1115', color: C.text, borderRadius: 8, borderWidth: 1, borderColor: C.line, paddingHorizontal: 10, paddingVertical: 8, marginTop: 2},
  chip: {paddingHorizontal: 12, paddingVertical: 7, borderRadius: 16, backgroundColor: C.line, marginRight: 8, marginVertical: 3},
});
