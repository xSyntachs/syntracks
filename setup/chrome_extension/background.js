const API = "https://syntracks.xsyntachs.de";

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type !== "save") return;
  (async () => {
    const { token } = await chrome.storage.local.get("token");
    if (!token) {
      sendResponse({ ok: false, text: chrome.i18n.getMessage("sign_in_first") });
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
      sendResponse({ ok: false, text: chrome.i18n.getMessage("server_unreachable") });
    }
  })();
  return true;
});
