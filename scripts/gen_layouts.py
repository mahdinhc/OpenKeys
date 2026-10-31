import re,json,html
A='Avro-Keyboard-master/Avro-Keyboard-master/assets/keyboard-layouts/'
P='OpenKeys-fixed/OpenKeys-main/native/assets/layouts/'
base=json.load(open(P+'bn_bijoy.json',encoding='utf8'))
def load(f):
    s=open(A+f,encoding='utf-8-sig').read()
    d={}
    for m in re.finditer(r'<(Key_\w+)>\s*<!\[CDATA\[(.*?)\]\]>',s,re.S): d[m.group(1)]=m.group(2)
    return d
def esc(s):
    o=''
    for ch in s:
        if ch in ' {}|=\\~:' or ord(ch)<32 or ch in '\u200c\u200d\u200b\u00a0': o+='\\u{%X}'%ord(ch)
        else: o+=ch
    return o
def key(d,k,shift):
    n=d.get(f'Key_{k}_'+('Shift' if shift else 'Normal'),'').strip('\r\n')
    alts=[d.get(f'Key_{k}_'+('ShiftAltGr' if shift else 'AltGr'),'')]
    alts=[a for a in alts if a and a!=n]
    if not n: n=alts.pop(0) if alts else ''
    if not n: return '{pad:1}'
    return '|'.join(esc(x) for x in [n]+alts)
rows=[['Q','W','E','R','T','Y','U','I','O','P'],['A','S','D','F','G','H','J','K','L'],['Z','X','C','V','B','N','M']]
def build(file,id_,name):
    d=load(file)
    L=json.loads(json.dumps(base)); L['id']=id_; L['name']=name
    for layer,sh in(('base',False),('shift',True)):
        r=[' '.join(key(d,k,sh) for k in rows[0]),
           '{pad:0.5} '+' '.join(key(d,k,sh) for k in rows[1])+' {pad:0.5}',
           '{shift:1.5} '+' '.join(key(d,k,sh) for k in rows[2])+' {del:1.5}']
        L['layers'][layer][:3]=r
    json.dump(L,open(P+id_+'.json','w',encoding='utf8'),ensure_ascii=False,indent=1)
    print(id_,L['layers']['base'][:3],L['layers']['shift'][:3])
build('Probhat.avrolayout','bn_probhat','বাংলা · Probhat')
build('National (Jatiya).avrolayout','bn_jatiya','বাংলা · Jatiya')
