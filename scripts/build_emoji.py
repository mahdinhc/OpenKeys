import re
T='/mnt/user-data/uploads/emoji-test.txt'
GR={'Smileys & Emotion':0,'People & Body':1,'Animals & Nature':2,'Food & Drink':3,'Travel & Places':4,'Activities':5,'Objects':6,'Symbols':7,'Flags':8}
old={}
for ln in open('/home/claude/w/emoji_old.txt',encoding='utf8'):
    p=ln.rstrip('\n').split('\t'); 
    if len(p)>3: old[p[1].replace('\ufe0f','')]=p[3]
rows=[];fq=set();grp=None
for ln in open(T,encoding='utf8'):
    if ln.startswith('# group:'): grp=ln.split(':',1)[1].strip()
    if ln.startswith('#') or not ln.strip(): continue
    m=re.match(r'([0-9A-F ]+?)\s*;\s*(\S+)\s*#\s*(\S+)\s+E([\d.]+)\s+(.*)',ln)
    cps,st,ch,v,name=m.groups()
    if st!='fully-qualified' or grp not in GR: continue
    fq.add(ch)
    if any(0x1F3FB<=ord(c)<=0x1F3FF for c in ch): continue
    rows.append((GR[grp],ch,name))
def tone(s):
    r=s[1:] if len(s)>1 else ''
    if r.startswith('\ufe0f'): r=r[1:]
    return s[0]+chr(0x1F3FB)+r
PRI={
'heart':'❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔','love':'❤️ 😍 🥰 😘 💕','broken':'💔','heartbreak':'💔','demon':'😈 👿 👹','devil':'😈 👿','evil':'😈 👿','angel':'😇','happy':'😊 😀 😄','sad':'😢 😞 ☹️','cry':'😭 😢','crying':'😭 😢','laugh':'😂 🤣','lol':'😂 🤣','haha':'😂 😆','smile':'🙂 😊 😄','fire':'🔥','hot':'🥵 🔥 🌶️','cold':'🥶 ❄️','ok':'👌 👍','okay':'👌 👍','yes':'👍 ✅','no':'🙅 ❌ 👎','thanks':'🙏 😊','thank':'🙏','please':'🙏 🥺','pray':'🙏','party':'🥳 🎉','birthday':'🎂 🎉 🥳','cake':'🎂 🍰','pizza':'🍕','burger':'🍔','coffee':'☕','tea':'🍵 ☕','beer':'🍺','cat':'🐱 🐈','dog':'🐶 🐕','sun':'☀️ 🌞','moon':'🌙 🌛','star':'⭐ 🌟','rain':'🌧️ ☔','snow':'❄️ ⛄','money':'💰 💵 🤑','kiss':'😘 💋','hug':'🤗','angry':'😠 😡 🤬','mad':'😡 😠','sleep':'😴 💤','sleepy':'😴','sick':'🤒 🤢','skull':'💀 ☠️','dead':'💀','ghost':'👻','clap':'👏','wow':'😮 😲 🤩','cool':'😎 🆒','congrats':'🎉 👏','good':'👍 😊','bad':'👎','bye':'👋','hi':'👋','hello':'👋','hey':'👋','strong':'💪','muscle':'💪','eyes':'👀','think':'🤔','thinking':'🤔','poop':'💩','rocket':'🚀','car':'🚗','home':'🏠','house':'🏠','phone':'📱','music':'🎵 🎶','sorry':'😔 🙏','sweet':'🍬 🥰','cute':'🥰 😊','beautiful':'😍 🌸','flower':'🌸 🌹','rose':'🌹','smart':'🤓 🧠','idea':'💡','win':'🏆 🎉','winner':'🏆','trophy':'🏆','gift':'🎁','christmas':'🎄 🎅','halloween':'🎃 👻','food':'🍽️ 🍔','eat':'🍽️ 😋','hungry':'😋 🍔','drink':'🥤 🍹','water':'💧 🌊','tired':'😫 😴 🥱','scared':'😱 😨','fear':'😱 😨','shy':'😳 🙈','blush':'😊 😳','wink':'😉','crazy':'🤪','funny':'😂 🤣','bored':'😑 🥱','fight':'👊 🥊','peace':'✌️ ☮️','victory':'✌️','luck':'🍀 🤞','lucky':'🍀','sunglasses':'😎','tongue':'😛 😜','mask':'😷','baby':'👶','boy':'👦','girl':'👧','man':'👨','woman':'👩','king':'🤴 👑','queen':'👸 👑','crown':'👑','bangladesh':'🇧🇩','india':'🇮🇳','usa':'🇺🇸','pakistan':'🇵🇰','uk':'🇬🇧','night':'🌙 🌃','morning':'🌅 ☀️','sunrise':'🌅','sunset':'🌇','beach':'🏖️','tree':'🌳 🌲','earth':'🌍','world':'🌍','book':'📖 📚','read':'📖','write':'✍️','work':'💼','office':'🏢 💼','school':'🏫','study':'📚','time':'⏰','clock':'🕐','alarm':'⏰','phone':'📱','computer':'💻','laptop':'💻','camera':'📷','video':'🎥','game':'🎮','gaming':'🎮','football':'⚽ 🏈','soccer':'⚽','basketball':'🏀','cricket':'🏏','run':'🏃','dance':'💃 🕺','swim':'🏊','bike':'🚴 🚲','plane':'✈️','flight':'✈️','train':'🚆','bus':'🚌','boat':'⛵ 🚤','ship':'🚢','rainbow':'🌈','lightning':'⚡','storm':'⛈️','wind':'💨','cloud':'☁️','pig':'🐷','cow':'🐮','monkey':'🐵 🙈','lion':'🦁','tiger':'🐯','bear':'🐻','rabbit':'🐰','fox':'🦊','panda':'🐼','unicorn':'🦄','bird':'🐦','fish':'🐟 🐠','snake':'🐍','spider':'🕷️','butterfly':'🦋','apple':'🍎','banana':'🍌','mango':'🥭','strawberry':'🍓','cherry':'🍒','watermelon':'🍉','rice':'🍚','bread':'🍞','egg':'🥚','chicken':'🍗 🐔','icecream':'🍦','chocolate':'🍫','cookie':'🍪','candy':'🍬','wine':'🍷','cheers':'🍻 🥂','medal':'🏅','check':'✅ ✔️','cross':'❌','warning':'⚠️','question':'❓','sorry':'😔 🙏','shush':'🤫','secret':'🤫','sweat':'😅 💦','nervous':'😬 😅','proud':'🥹 😌','relax':'😌','relief':'😌','excited':'🤩 🥳','celebrate':'🎉 🥳','welcome':'🤗 🙏','miss':'🥺 💔','bless':'🙏 😇','blessed':'🙏 😇','amen':'🙏','dua':'🤲','mosque':'🕌','eid':'🌙 🕌','ramadan':'🌙 🕌','mubarak':'🌙 🕌'}
out=[];seen=set()
for g,ch,name in rows:
    if ch in seen: continue
    seen.add(ch)
    mod=1 if tone(ch) in fq else 0
    n=name.lower(); toks=re.split(r"[\s:,()“”\"’.!&]+",n.replace('-',' - '))
    toks=[t for t in toks if t and t!='-']
    toks+= [p for t in n.split() if '-' in t for p in t.split('-') if p]
    base=ch.replace('\ufe0f','')
    extra=old.get(base,'').split()
    kw=[]
    for t in toks+extra:
        if t not in kw: kw.append(t)
    out.append([g,ch,mod,' '.join(kw),''])
byc={r[1]:r for r in out}
byb={r[1].replace('\ufe0f',''):r for r in out}
pri={}
for w,es in PRI.items():
    for rank,e in enumerate(es.split()):
        r=byc.get(e) or byb.get(e.replace('\ufe0f',''))
        if not r: print('missing',w,e); continue
        r[4]+=f"{w}:{rank} "; 
        if w not in r[3].split(): r[3]+=' '+w
open('OpenKeys-fixed/OpenKeys-main/native/assets/emoji/emoji.txt','w',encoding='utf8').write('\n'.join('\t'.join(map(str,[r[0],r[1],r[2],r[3],r[4].strip()])) for r in out)+'\n')
print(len(out), sum(r[2] for r in out))
