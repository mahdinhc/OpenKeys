// Blank — the smallest plugin: one toolbar button.
// A plugin is ONE .js file. Call plugin({...}) once; everything else is optional.
plugin({
  name: "My plugin",
  description: "What it does, in one line.",
  toolbar: [{
    icon: "⭐",
    run() {
      const sel = keys.selected();
      keys.toast(sel ? 'Selected: "' + sel + '"' : "Nothing selected");
    },
  }],
});
