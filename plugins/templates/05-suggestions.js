// Suggestions — add your own words to the suggestion bar while typing.
// Return an array now, or call done([...]) later (for network results). Up to 3 words show after the typed word.
plugin({
  name: "My suggestions",
  description: "Suggests 'regards' after typing 'best'.",
  suggest(ctx) {
    if (ctx.prev && ctx.prev.toLowerCase() === "best" && !ctx.word) return ["regards", "wishes"];
  },
});
