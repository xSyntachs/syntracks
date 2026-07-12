const API = "https://syntracks.xsyntachs.de";

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type !== "save") return;
  (async () => {
    const { token } = await chrome.storage.local.get("token");
    if (!token) {
      sendResponse({ ok: false, text: "Nicht angemeldet. Klick oben auf das Syntracks-Symbol und melde dich an." });
      return;
    }
    try {
      const r = await fetch(API + "/add", {
        method: "POST",
        headers: { "X-Token": token },
        body: msg.url,
      });
      sendResponse({ ok: r.ok, text: await r.text() });
    } catch (e) {
      sendResponse({ ok: false, text: "Server nicht erreichbar" });
    }
  })();
  return true;
});
