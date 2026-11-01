// Word rewriter — runs when you finish a word (space / punctuation) and can replace it.
// Return a string to replace the word, return nothing to leave it alone. Must be fast (under ~30 ms).
plugin({
  name: "Word rewriter",
  description: "Turns the typed word 'brb' into 'be right back'.",
  settings: {
    trigger: {type: "text", label: "Word to replace", default: "brb"},
    text: {type: "text", label: "Replace with", default: "be right back"},
  },
  onCommit(word) {
    if (word.toLowerCase() === String(keys.settings.trigger).toLowerCase()) return String(keys.settings.text);
  },
});
