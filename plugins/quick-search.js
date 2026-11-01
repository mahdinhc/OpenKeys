// Quick Search — select text, tap 🔎, pick where to look it up (opens your browser or app).
plugin({
  name: "Quick Search",
  description: "Select text, tap 🔎: Google, translate, maps, YouTube or Wikipedia.",
  settings: {
    lang: {type: "text", label: "Translate to (language code)", default: "en"},
  },
  toolbar: [{
    id: "menu",
    icon: "🔎",
    run() {
      const s = keys.selected();
      if (!s) return keys.toast("Select some text first");
      const q = encodeURIComponent(s.slice(0, 200));
      const lang = encodeURIComponent(String(keys.settings.lang || "en"));
      keys.panel({
        title: "Search for “" + s.slice(0, 24) + (s.length > 24 ? "…" : "") + "”",
        columns: 2,
        items: [
          ["Google", "https://www.google.com/search?q=" + q],
          ["Translate", "https://translate.google.com/?sl=auto&tl=" + lang + "&text=" + q + "&op=translate"],
          ["Maps", "https://www.google.com/maps/search/" + q],
          ["YouTube", "https://www.youtube.com/results?search_query=" + q],
          ["Wikipedia", "https://" + lang + ".wikipedia.org/w/index.php?search=" + q],
          ["Images", "https://www.google.com/search?tbm=isch&q=" + q],
        ].map(([label, url]) => ({label, onTap: () => keys.openUrl(url)})),
      });
    },
  }],
});
