const API = "https://tiktok.xsyntachs.de";

const el = (id) => document.getElementById(id);

function msg(text, ok) {
  el("msg").textContent = text;
  el("msg").className = "msg " + (ok ? "ok" : "err");
}

function render(token, user) {
  el("login").classList.toggle("hidden", !!token);
  el("main").classList.toggle("hidden", !token);
  el("who").textContent = token ? "@" + user : "Nicht angemeldet";
}

async function init() {
  const { token, user } = await chrome.storage.local.get(["token", "user"]);
  render(token, user);
}

el("login-btn").onclick = async () => {
  msg("");
  try {
    const r = await fetch(API + "/login", {
      method: "POST",
      body: JSON.stringify({ user: el("user").value.trim(), pass: el("pass").value }),
    });
    if (!r.ok) throw new Error(await r.text());
    const data = await r.json();
    await chrome.storage.local.set({ token: data.token, user: data.user });
    render(data.token, data.user);
  } catch (e) { msg(e.message); }
};

el("save-btn").onclick = async () => {
  msg("");
  const { token } = await chrome.storage.local.get("token");
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.url || !tab.url.includes("tiktok.com")) {
    msg("Kein TikTok-Tab offen");
    return;
  }
  if (!tab.url.includes("/video/") && !tab.url.includes("/photo/")) {
    msg("Öffne das Video einzeln (im Feed auf die Beschreibung klicken), sonst kennt der Browser die Video-Adresse nicht");
    return;
  }
  try {
    const r = await fetch(API + "/add", {
      method: "POST",
      headers: { "X-Token": token },
      body: tab.url,
    });
    if (!r.ok) throw new Error(await r.text());
    msg(await r.text(), true);
  } catch (e) { msg(e.message); }
};

el("open-web").onclick = () => chrome.tabs.create({ url: API + "/" });

el("logout").onclick = async () => {
  await chrome.storage.local.clear();
  render(null, null);
};

init();
