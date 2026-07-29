const API = "https://syntracks.app";

const el = (id) => document.getElementById(id);
let T = (key) => key;
let lang = "en";
let registering = false;

function applyText() {
  document.querySelectorAll("[data-i18n]").forEach(node => {
    node.textContent = T(node.dataset.i18n);
  });
  document.querySelectorAll("[data-ph]").forEach(node => {
    node.placeholder = T(node.dataset.ph);
  });
}

function msg(text, ok) {
  el("msg").textContent = text;
  el("msg").className = "msg " + (ok ? "ok" : "err");
}

function render(token, user) {
  el("login").classList.toggle("hidden", !!token);
  el("main").classList.toggle("hidden", !token);
  el("who").textContent = token ? "@" + user : T("not_signed_in");
}

async function init() {
  const texts = await ttsLoadTexts();
  T = texts.text;
  lang = texts.code;
  applyText();
  el("lang-pick").innerHTML = Object.entries(TTS_LANGS).map(([code, label]) =>
    `<option value="${code}"${code === texts.code ? " selected" : ""}>${label}</option>`).join("");
  el("lang-pick").onchange = async () => {
    await chrome.storage.local.set({ lang: el("lang-pick").value });
    location.reload();
  };
  const { token, user } = await chrome.storage.local.get(["token", "user"]);
  render(token, user);
}

el("toggle-register").onclick = () => {
  registering = !registering;
  msg("");
  el("pass2").classList.toggle("hidden", !registering);
  el("login-btn").textContent = registering ? T("create_account") : T("sign_in");
  el("toggle-register").textContent = registering ? T("already_account") : T("new_here");
};

el("login").onsubmit = async (event) => {
  event.preventDefault();
  msg("");
  if (registering && el("pass").value !== el("pass2").value) {
    msg(T("passwords_mismatch"));
    return;
  }
  el("login-btn").disabled = true;
  try {
    const r = await fetch(`${API}${registering ? "/register" : "/login"}?lang=${lang}`, {
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
    msg(T("no_tiktok_tab"));
    return;
  }
  if (!tab.url.includes("/video/") && !tab.url.includes("/photo/")) {
    msg(T("open_video_alone"));
    return;
  }
  el("save-btn").disabled = true;
  try {
    const r = await fetch(`${API}/add?lang=${lang}`, {
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

el("open-web").onclick = () => chrome.tabs.create({ url: API });

el("logout").onclick = async () => {
  const { lang } = await chrome.storage.local.get("lang");
  await chrome.storage.local.clear();
  if (lang) await chrome.storage.local.set({ lang });
  registering = false;
  el("pass2").classList.add("hidden");
  el("login-btn").textContent = T("sign_in");
  el("toggle-register").textContent = T("new_here");
  msg("");
  render(null, null);
};

init();
