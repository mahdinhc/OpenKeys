// Hello — the smallest useful plugin. Copy this file to start your own.
// A plugin is ONE .js file. Call plugin({...}) once; everything else is optional.
plugin({
  name: "Hello",
  description: "Template: adds a ⭐ toolbar button and logs when you focus a text field.",

  // Buttons on the toolbar (the row you see when no suggestions are showing).
  // Give a button an id to use it as a layout key: {js=hello.star}
  toolbar: [{
    id: "star",
    icon: "⭐",
    run() {
      const sel = keys.selected();
      keys.toast(sel ? 'Selected: "' + sel + '"' : "Nothing selected");
    },
  }],

  // Runs whenever a text field gets focus. ctx.package = the app, ctx.inputType = Android input type.
  onStartInput(ctx) {
    console.log("typing in", ctx.package || "?");   // see Plugins → Show log
  },
});
