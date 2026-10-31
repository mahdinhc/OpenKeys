import re,collections
D='dictionaries-main/dictionaries-main/dictionaries/en/'
# --- parse affixes
pfx=collections.defaultdict(list); sfx=collections.defaultdict(list); cross={}
for ln in open(D+'index.aff',encoding='utf8'):
    p=ln.split('#')[0].split()
    if len(p)>=4 and p[0] in('PFX','SFX') and len(p)==4:  # header
        cross[(p[0],p[1])]=p[2]=='Y'; continue
    if len(p)>=5 and p[0] in('PFX','SFX'):
        kind,flag,strip,add,cond=p[:5]
        add=add.split('/')[0]
        strip='' if strip=='0' else strip; add='' if add=='0' else add
        rx=re.compile(('^'+cond if kind=='PFX' else cond+'$') if cond!='.' else '.*')
        (pfx if kind=='PFX' else sfx)[flag].append((strip,add,rx))
def apply(w,flag,kind):
    out=[]
    for strip,add,rx in (pfx if kind=='PFX' else sfx).get(flag,[]):
        if not rx.search(w): continue
        if kind=='SFX':
            if strip and not w.endswith(strip): continue
            out.append(w[:len(w)-len(strip)]+add)
        else:
            if strip and not w.startswith(strip): continue
            out.append(add+w[len(strip):])
    return out
roots={};derived=set()
for i,ln in enumerate(open(D+'index.dic',encoding='utf8')):
    if i==0: continue
    ln=ln.strip()
    if not ln: continue
    w,_,fl=ln.partition('/')
    if not re.fullmatch(r"[a-z][a-z']*",w): continue   # drops proper nouns, abbreviations, digits
    roots[w]=1
    ps=[f for f in fl if f in pfx]; ss=[f for f in fl if f in sfx]
    sf=[]
    for f in ss:
        for x in apply(w,f,'SFX'): sf.append((x,f))
    for x,_f in sf: derived.add(x)
    for f in ps:
        for x in apply(w,f,'PFX'): derived.add(x)
        for x,f2 in sf:
            if cross.get(('SFX',f2)) and cross.get(('PFX',f)):
                for y in apply(x,f,'PFX'): derived.add(y)
alpha=set(open('english-words-master/english-words-master/words_alpha.txt').read().split())
seed=[l.strip().split('\t')[0] for l in open('seedtmp/en.txt') if l.strip()]
allw=set(roots)|{d for d in derived if re.fullmatch(r"[a-z][a-z']*",d)}
# keep: roots always; derived only if they are real words per words_alpha (kills nonsense like "unfoo")
seedset=set(seed)
keep={w for w in allw if w in roots or w in alpha}
keep|=set(seed)
for w in ['i','a','i\'m','i\'ll','i\'ve','i\'d']: keep.add(w)
# drop 1-letter junk except a,i
TWO=set('am an as at be by do go he hi if in is it me my no of oh ok on or so to up us we ox ah aw eh ha ma pa yo ye ex id'.split())
keep={w for w in keep if (len(w)>2 or w in TWO or w in seedset or w=='a' or w=='i') and (len(w)!=3 or w in seedset or re.search('[aeiouy]',w))}
# heuristic frequency (no corpus offered): curated seed rank first, then short roots > long derived
def score(w):
    if w in seedrank: return 2_000_000-seedrank[w]*100
    s=60000/(len(w)+1)**2.2 if len(w)>3 else 400
    if w not in roots: s*=0.35
    if "'" in w: s*=0.5
    return max(1,int(s))
seedset=set(seed)
seedrank={w:i for i,w in enumerate(seed)}
rows=sorted(keep,key=lambda w:-score(w))
open('OpenKeys-fixed/OpenKeys-main/native/assets/dict/en.txt','w',encoding='utf8').write('\n'.join(f"{w}\t{score(w)}" for w in rows)+'\n')
print(len(rows),len(roots),len(derived)); print(rows[:5],rows[300:310],rows[-3:])
for t in ["don't","hello","heart","the","teh","wouldn't"]: print(t,t in keep)
