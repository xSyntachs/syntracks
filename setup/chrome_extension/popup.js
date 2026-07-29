const API = "https://syntracks.xsyntachs.de";
const WEB = "https://syntracks.app";

const el = (id) => document.getElementById(id);
let registering = false;

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

el("toggle-register").onclick = () => {
  registering = !registering;
  msg("");
  el("pass2").classList.toggle("hidden", !registering);
  el("login-btn").textContent = registering ? "Konto erstellen" : "Anmelden";
  el("toggle-register").textContent = registering
    ? "Ich habe schon ein Konto"
    : "Neu hier? Konto erstellen";
};

el("login").onsubmit = async (event) => {
  event.preventDefault();
  msg("");
  if (registering && el("pass").value !== el("pass2").value) {
    msg("Passwörter stimmen nicht überein");
    return;
  }
  el("login-btn").disabled = true;
  try {
    const r = await fetch(API + (registering ? "/register" : "/login"), {
      method: "POST",
      body: JSON.stringify({ user: el("user").value.trim(), pass: el("pass").value }),
    });
    if (!r.ok) throw new Error(await r.text());
    const account = await r.json();
    await chrome.storage.local.set({ token: account.token, user: account.user });
    render(account.token, account.user);
  } catch (e) {
    msg(e.message);
  } finally {
    el("login-btn").disabled = false;
  }
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
  el("save-btn").disabled = true;
  try {
    const r = await fetch(API + "/add", {
      method: "POST",
      headers: { "X-Token": token },
      body: tab.url,
    });
    if (!r.ok) throw new Error(await r.text());
    msg(await r.text(), true);
  } catch (e) {
    msg(e.message);
  } finally {
    el("save-btn").disabled = false;
  }
};

el("open-web").onclick = () => chrome.tabs.create({ url: WEB });

el("logout").onclick = async () => {
  await chrome.storage.local.clear();
  registering = false;
  el("pass2").classList.add("hidden");
  el("login-btn").textContent = "Anmelden";
  el("toggle-register").textContent = "Neu hier? Konto erstellen";
  msg("");
  render(null, null);
};

init();
