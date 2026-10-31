// Clean Paste — 🧹 pastes the clipboard as tidy plain text:
// trims, collapses extra spaces/blank lines, strips tracking params (utm_*, fbclid, gclid...) from links.
plugin({
  name: "Clean Paste",
  toolbar: [{
    icon: "🧹",
    run() {
      const raw = keys.clipboard.get();
      if (!raw) return keys.toast("Clipboard is empty");
      const t = raw
        .replace(/\r\n/g, "\n")
        .replace(/[ \t]+/g, " ")
        .replace(/ ?\n ?/g, "\n")
        .replace(/\n{3,}/g, "\n\n")
        .trim()
        .replace(/https?:\/\/\S+/g, cleanUrl);
      keys.commit(t);
    },
  }],
});

function cleanUrl(u) {
  const i = u.indexOf("?");
  if (i < 0) return u;
  let q = u.slice(i + 1), hash = "";
  const h = q.indexOf("#");
  if (h >= 0) { hash = q.slice(h); q = q.slice(0, h); }
  const kept = q.split("&").filter((p) => !/^(utm_|fbclid|gclid|igshid|mc_cid|mc_eid|ref_src)/i.test(p));
  return u.slice(0, i) + (kept.length ? "?" + kept.join("&") : "") + hash;
}
