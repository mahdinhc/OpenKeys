// Panel — a button that opens a panel of tappable items (like a snippet picker).
// Items: "text" (inserts it) · {label, text} · {label, onTap() {...}}
plugin({
  name: "Quick replies",
  description: "Tap 💬 and pick a reply.",
  toolbar: [{
    icon: "💬",
    run() {
      keys.panel({
        title: "Quick replies",
        columns: 2,
        items: [
          "Thanks!",
          {label: "On my way", text: "On my way, be there soon."},
          {label: "Time", onTap() { keys.commit(new Date().toTimeString().slice(0, 5)); }},
        ],
      });
    },
  }],
});
