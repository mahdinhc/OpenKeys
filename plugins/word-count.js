// Word Count — select text, tap #, see characters / words / lines.
plugin({
  name: "Word Count",
  toolbar: [{
    icon: "#",
    run() {
      const s = keys.selected();
      if (!s) return keys.toast("Select some text first");
      const words = (s.match(/\S+/g) || []).length;
      const lines = s.split("\n").length;
      keys.toast(s.length + " chars · " + words + " words · " + lines + (lines === 1 ? " line" : " lines"));
    },
  }],
});
