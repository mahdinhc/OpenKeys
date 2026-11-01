// Online Suggest — extra word suggestions from the free Datamuse API while you type.
// Shows how async hooks and keys.http work. (Needs internet.)
let timer = 0;

plugin({
  name: "Online Suggest",
  description: "Adds Datamuse completions to the suggestion bar.",
  settings: {
    min: {type: "number", label: "Start after N letters", default: 3},
  },

  suggest(ctx, done) {
    clearTimeout(timer);                                  // debounce: only the last keystroke fires
    const w = ctx.word;
    if (!w || w.length < Number(keys.settings.min) || !/^\p{L}+$/u.test(w)) return;
    timer = setTimeout(() => {
      keys.http("https://api.datamuse.com/sug?max=3&s=" + encodeURIComponent(w), {}, (err, res) => {
        if (err || !res.ok) return console.warn("datamuse failed", err ? err.message : res.status);
        done(res.json().map((x) => x.word));              // done([...]) adds them to the bar
      });
    }, 250);
  },
});
