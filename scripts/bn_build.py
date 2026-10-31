import json,re,sqlite3,collections,os
R='/home/claude/w/'
P=R+'OpenKeys-fixed/OpenKeys-main/native/'
# ---------- 1. extend phonetic rules (Avro-isms)
jp=P+'assets/layouts/bn_phonetic.json'
j=json.load(open(jp,encoding='utf8'))
t=j['translit']
t['consonants'].update({'x':'ক্স','rr':'র্','ZH':'জ্ঞ'})
t['consonants'].pop('ZH')
t['vowels'].update({'ee':['ঈ','ী'],'oo':['উ','ু'],'OI':['ঐ','ৈ']})
t['keepMarks']={'Z':'্য'}
json.dump(j,open(jp,'w',encoding='utf8'),ensure_ascii=False,indent=1)
# ---------- python port of Transliterator.kt
virama='্'
E_=lambda k,a,b='':(k,a,b)
M={}
for k,v in t['consonants'].items(): M[k]=(0,v,'')
for k,(a,b) in t['vowels'].items(): M[k]=(1,a,b)
for k,v in t['marks'].items(): M[k]=(2,v,'')
for k,v in t['keepMarks'].items(): M[k]=(3,v,'')
for k,e in list(M.items()):
    if e[0]==0 and len(k)==1 and k+k not in M: M[k+k]=(0,e[1]+virama+e[1],'')
maxlen=max(map(len,M))
def conv(s):
    out=[];i=0;pc=False
    while i<len(s):
        f=None;l=min(maxlen,len(s)-i)
        while l>0:
            if s[i:i+l] in M: f=(M[s[i:i+l]],l);break
            l-=1
        if not f and s[i].lower() in M: f=(M[s[i].lower()],1)
        if not f:
            c=s[i]
            if c=='`':
                if pc: out.append(virama)
            else: out.append(c);pc=False
            i+=1;continue
        (kd,a,b),l=f
        if kd==0: out.append(a);pc=True
        elif kd==1: out.append(b if pc else a);pc=False
        elif kd==3: out.append(a);pc=True
        else: out.append(a);pc=False
        i+=l
    return ''.join(out)
# ---------- skeleton (MUST match WordDict.skeleton in Kotlin)
SK={}
for grp,to in [('ইঈিী','ি'),('উঊুূ','ু'),('নণ','ন'),('শষস','স'),('তটৎ','ত'),('থঠ','থ'),('দড','দ'),('ধঢ','ধ'),('জয','জ'),('রড়ঢ়','র'),('অওো','অ'),('আা','া'),('এে','ে'),('ঐৈ','ৈ'),('ঔৌ','ৌ'),('ঋৃ','ৃ'),('ঙংঞ','ং'),('ঁঃ\u200c\u200d্','')]:
    for c in grp: SK[c]=to
SK['এ']='ে'
def skel(w): return ''.join(SK.get(c,c) for c in w)
def norm(w):
    return w.replace('\u09A1\u09BC','\u09DC').replace('\u09A2\u09BC','\u09DD').replace('\u09AF\u09BC','\u09DF')
# ---------- 2. dictionary words
ok=re.compile('^[\u0985-\u09B9\u09BC-\u09CE\u09DC-\u09DF\u09E6-\u09EF\u0981-\u0983\u200c\u200d]+$')
avro=set()
c=sqlite3.connect(R+'Avro-Keyboard-master/Avro-Keyboard-master/assets/Database.db3')
for (n,) in c.execute("select name from sqlite_master where type='table' and name!='Suffix'"):
    for (w,) in c.execute(f'select Words from "{n}"'):
        if w: avro.add(norm(w.strip()))
sup=set()
for ln in open(R+'BengaliDictionary-master/BengaliDictionary-master/BengaliWordList_112.txt',encoding='utf-8-sig'):
    w=norm(ln.strip())
    if w: sup.add(w)
def good(w):
    if not ok.match(w) or len(w)>24: return False
    if w[0] in 'ািীুূৃেৈোৌ্ংঃঁ়': return False
    return True
avro={w for w in avro if good(w)}; sup={w for w in sup if good(w)}
words=avro|sup
print('avro',len(avro),'sup',len(sup),'union',len(words))
bysk=collections.defaultdict(list)
for w in words: bysk[skel(w)].append(w)
# ---------- 3. frequencies from phonetic frequency list
freq=collections.Counter(); hit=0;tot=0
for ln in open(R+'BengaliDictionary-master/BengaliDictionary-master/BengaliPhoneticWordFrequency_164.txt',encoding='utf-8-sig'):
    p=ln.rstrip('\n').split('\t')
    if len(p)<2 or not p[1].isdigit(): continue
    tot+=1
    b=norm(conv(p[0])); cs=bysk.get(skel(b))
    if p[0].endswith('o') and len(p[0])>2:
        c2=bysk.get(skel(b)+'অ')       # roman final 'o' is usually ো ("valo" = ভালো)
        if c2: cs=c2; b=''
    if not cs: continue
    hit+=1
    best=b if b in cs else sorted(cs,key=lambda w:(w not in avro,len(w)))[0]
    freq[best]+=int(p[1])
print('freq matched',hit,'of',tot,'distinct words with freq',len(freq))
# ---------- 4. autocorrect (roman -> bangla) from Avro autodict
auto={}
for ln in open(R+'Avro-Keyboard-master/Avro-Keyboard-master/assets/autodict.dct',encoding='utf-8-sig'):
    if ln.startswith('/') or not ln.strip(): continue
    p=ln.split()
    if len(p)!=2 or not re.fullmatch(r"[A-Za-z]{2,}",p[0]): continue
    out=norm(conv(p[1]))
    if re.search('[A-Za-z`]',out) or not ok.match(out): continue
    auto[p[0].lower()]=out
print('auto',len(auto),list(auto.items())[:6])
for w in auto.values():
    if good(w): words.add(w); freq[w]+=300
# ---------- 5. write bn.txt
rows=sorted(words,key=lambda w:-(freq.get(w,0)*3+(2 if w in avro else 1)))
open(P+'assets/dict/bn.txt','w',encoding='utf8').write('\n'.join(f"{w}\t{freq.get(w,0)*3+(2 if w in avro else 1)}" for w in rows)+'\n')
open(P+'assets/dict/bn_auto.txt','w',encoding='utf8').write('\n'.join(f"{k}\t{v}" for k,v in sorted(auto.items()))+'\n')
# suffixes from Avro suffix table
suf=collections.Counter()
for (e,b) in c.execute('select English,Bangla from Suffix'):
    b=norm(b.strip())
    if b and ok.match(b) and len(b)<=6: suf[b]+=1
open(P+'assets/dict/bn_suffix.txt','w',encoding='utf8').write('\n'.join(sorted(suf,key=lambda s:-len(s)))+'\n')
print(len(rows),len(suf)); print(rows[:12])
for t_ in ['ami','tumi','bangladesh','valobasi','kemon']: print(t_,conv(t_))

def sim(roman,n=6):
    p=conv(roman); sp=skel(p); hits={}
    for w in words:
        if skel(w).startswith(sp): hits[w]=(freq.get(w,0)*3+(2 if w in avro else 1))*10-len(w)*400+(3000 if w.startswith(p) else 0)
    return p,[w for w,_ in sorted(hits.items(),key=lambda x:-x[1])[:n]]
if os.environ.get('SIM'):
    for r in ['am','tum','bangla','valo','kemo','bhalo','bidd','amar','sob','prem','ghor','shikkha']: print(r,*sim(r))
if os.environ.get('DBG'):
    w='ভালো'; print(w in words, freq.get(w), skel(w), skel(conv('valo')), [hex(ord(c)) for c in w], [hex(ord(c)) for c in conv('valo')])
