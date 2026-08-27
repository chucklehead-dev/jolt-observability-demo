(() => {
  const dialog = document.querySelector("[data-otel-dialog]");
  const content = dialog?.querySelector("[data-otel-dialog-content]");

  if (!dialog || !content || typeof dialog.showModal !== "function") return;

  document.addEventListener("click", async (event) => {
    const link = event.target.closest?.("a[data-otel-trace]");
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey ||
        event.shiftKey || event.altKey) return;

    event.preventDefault();
    try {
      const response = await fetch(link.href, {headers: {Accept: "text/html"}});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);

      const page = new DOMParser().parseFromString(await response.text(), "text/html");
      const viewer = page.querySelector(".otel-viewer");
      if (!viewer) throw new Error("trace viewer missing");

      content.replaceChildren(viewer);
      dialog.showModal();
    } catch (_) {
      location.assign(link.href);
    }
  });

  dialog.addEventListener("close", () => content.replaceChildren());
})();
