// Snippets — a 📝 button that opens a panel of your saved snippets; tap one to insert it.
// Edit them in Plugins → Snippets (one per line:  label=text   — use \n for a newline).
plugin({
  name: "Snippets",
  description: "Tap 📝, tap a snippet, it's inserted.",
  settings: {
    snippets: {
      type: "textarea",
      label: "One per line: label=text",
      default: "Email=me@example.com\nSign-off=Best regards,\\nMe\nOn my way=On my way, be there in 10!",
    },
  },
  toolbar: [{
    icon: "📝",
    run() {
      const items = String(keys.settings.snippets)
        .split("\n")
        .map((l) => l.trim())
        .filter((l) => l.includes("="))
        .map((l) => {
          const i = l.indexOf("=");
          return {label: l.slice(0, i), text: l.slice(i + 1).replace(/\\n/g, "\n")};   // text => inserted as-is
        });
      if (!items.length) return keys.toast("No snippets yet — add some in the app");
      keys.panel({title: "Snippets", columns: 2, items});
    },
  }],
});
