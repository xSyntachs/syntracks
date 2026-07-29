importScripts("i18n.js");

const API = "https://syntracks.app";

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type !== "save") return;
  (async () => {
    const { text: T, code } = await ttsLoadTexts();
    const { token } = await chrome.storage.local.get("token");
    if (!token) {
      sendResponse({ ok: false, text: T("sign_in_first") });
      return;
    }
    try {
      const r = await fetch(`${API}/add?lang=${code}`, {
        method: "POST",
        headers: { "X-Token": token },
        body: msg.url,
      });
      sendResponse({ ok: r.ok, text: await r.text() });
    } catch (e) {
      sendResponse({ ok: false, text: T("server_unreachable") });
    }
  })();
  return true;
});
