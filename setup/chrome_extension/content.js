const NOTE_SVG = `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#fff"
  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
  <path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`;

function showToast(text, withLink) {
  document.getElementById("tts-toast")?.remove();
  const toast = document.createElement("div");
  toast.id = "tts-toast";
  toast.style.cssText = `position:fixed;top:24px;left:50%;transform:translateX(-50%);z-index:99999;
    background:#191921;color:#F5F5FA;border:1px solid rgba(255,255,255,.25);border-radius:14px;
    padding:14px 22px;font:14px/1.4 system-ui;box-shadow:0 10px 40px rgba(0,0,0,.6);max-width:420px`;
  toast.textContent = text;
  if (withLink) {
    const link = document.createElement("a");
    link.href = "https://tiktok.xsyntachs.de";
    link.target = "_blank";
    link.textContent = "Sammlung öffnen";
    link.style.cssText = "color:#25F4EE;margin-left:10px;text-decoration:none";
    toast.appendChild(link);
  }
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 6000);
}

function videoUrlFor(button) {
  const item = button.closest('[data-e2e="recommend-list-item-container"], article');
  const link = item?.querySelector('a[href*="/video/"], a[href*="/photo/"]');
  if (link) return link.href;
  if (location.pathname.includes("/video/") || location.pathname.includes("/photo/")) return location.href;
  return null;
}

function saveVideo(button) {
  const url = videoUrlFor(button);
  if (!url) {
    showToast("Video-Adresse nicht gefunden, öffne das Video einzeln.");
    return;
  }
  showToast("Video wird gespeichert…");
  chrome.runtime.sendMessage({ type: "save", url }, (answer) => {
    if (!answer) { showToast("Erweiterung neu laden (chrome://extensions)."); return; }
    showToast(answer.text, answer.ok);
  });
}

function injectButtons() {
  document.querySelectorAll('[data-e2e="share-icon"]').forEach((shareIcon) => {
    const wrapper = shareIcon.closest("button")?.parentElement;
    if (!wrapper || wrapper.parentElement.querySelector(".tts-save")) return;
    const holder = document.createElement("div");
    holder.className = "tts-save";
    holder.style.cssText = "display:flex;flex-direction:column;align-items:center;gap:4px;margin-top:8px";
    const btn = document.createElement("button");
    btn.title = "Song speichern (TikTok Songs)";
    btn.style.cssText = `width:48px;height:48px;border:none;border-radius:50%;cursor:pointer;
      display:flex;align-items:center;justify-content:center;
      background:linear-gradient(135deg,#FE2C55,#7828C8)`;
    btn.innerHTML = NOTE_SVG;
    btn.onclick = (e) => { e.stopPropagation(); saveVideo(btn); };
    const label = document.createElement("span");
    label.textContent = "Song speichern";
    label.style.cssText = `font: 700 11px/1.15 system-ui; color: rgba(255,255,255,.9);
      text-align:center; max-width:56px`;
    holder.appendChild(btn);
    holder.appendChild(label);
    wrapper.parentElement.insertBefore(holder, wrapper.nextSibling);
  });
}

// TikTok ist eine SPA, die Action-Leisten kommen und gehen beim Scrollen
new MutationObserver(injectButtons).observe(document.body, { childList: true, subtree: true });
injectButtons();
