// Text Expander — dynamic shortcuts. Type a trigger and press space:
//   zzdate -> 2026-10-31     zztime -> 14:05     zznow -> 2026-10-31 14:05
//   zzuuid -> random id      zzsig  -> your signature (set in Plugins → Text Expander)
// (Static shortcuts like "omw" already exist in Typing → Text shortcuts; this is for computed text.)
plugin({
  name: "Text Expander",
  description: "zzdate, zztime, zznow, zzuuid, zzsig expand when you press space.",
  settings: {
    prefix:    {type: "text", label: "Trigger prefix", default: "zz"},
    signature: {type: "text", label: "Signature (zzsig)", default: "Cheers"},
  },

  onCommit(word) {
    const p = String(keys.settings.prefix || "zz").toLowerCase();
    const w = word.toLowerCase();
    if (!w.startsWith(p)) return;           // return nothing = leave the word alone
    const d = new Date();
    switch (w.slice(p.length)) {
      case "date": return ymd(d);
      case "time": return hm(d);
      case "now":  return ymd(d) + " " + hm(d);
      case "uuid": return uuid();
      case "sig":  return String(keys.settings.signature);
    }
  },
});

const z = (n) => (n < 10 ? "0" : "") + n;
const ymd = (d) => d.getFullYear() + "-" + z(d.getMonth() + 1) + "-" + z(d.getDate());
const hm = (d) => z(d.getHours()) + ":" + z(d.getMinutes());
const uuid = () => "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0;
  return (c === "x" ? r : (r & 3) | 8).toString(16);
});
