// Auto Pair — typing ( [ { or a quote also inserts the closing one and puts the cursor between.
plugin({
  name: "Auto Pair",
  description: "Auto-closes brackets and quotes.",
  settings: {
    pairs: {type: "text", label: "Pairs (space separated)", default: "() [] {} \"\" ''"},
  },

  // Runs just BEFORE the typed character is inserted, so ctx.before doesn't include it yet.
  onText(ch, ctx) {
    const pair = String(keys.settings.pairs).split(/\s+/).find((p) => p.length === 2 && p[0] === ch);
    if (!pair) return;
    const prev = ctx.before.slice(-1);
    // quotes after a letter are apostrophes (don't), not openers
    if (pair[0] === pair[1] && /[\p{L}\p{N}]/u.test(prev)) return;
    keys.commit(pair[1], 1);                // insert the closer, then move the cursor back 1
  },
});
