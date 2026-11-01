// Selection button — a toolbar button that rewrites the selected text.
// The id makes it usable as a layout key too: {js=<file name>.shout}
plugin({
  name: "Shout",
  description: "Select text, tap the button: it becomes UPPERCASE!",
  toolbar: [{
    id: "shout",
    icon: "📣",
    run() {
      const s = keys.selected();
      if (!s) return keys.toast("Select some text first");
      keys.commit(s.toUpperCase() + "!");      // committing while text is selected replaces it
    },
  }],
});
