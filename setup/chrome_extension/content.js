(() => {
  if (window.__ttsContent) return;
  window.__ttsContent = true;

  const BRAND = "#E9E64A";
  const INK = "#121212";
  let T = (key) => key;
  ttsLoadTexts().then(texts => { T = texts.text; });

  const NOTE_SVG = `<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="${INK}"
  stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
  <path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>`;

  function showToast(text, success) {
    document.getElementById("tts-toast")?.remove();
    const toast = document.createElement("div");
    toast.id = "tts-toast";
    toast.style.cssText = `position:fixed;top:24px;left:50%;transform:translateX(-50%);z-index:99999;
    background:${INK};color:#F5F3E7;border:2px solid ${success ? BRAND : "#2A2A26"};border-radius:5px;
    padding:13px 18px;font:500 14px/1.4 'Space Grotesk',system-ui,sans-serif;max-width:420px`;
    toast.textContent = text;
    if (success) {
      const link = document.createElement("a");
      link.href = "https://syntracks.app";
      link.target = "_blank";
      link.textContent = T("open_collection");
      link.style.cssText = `color:${BRAND};margin-left:10px;font-weight:700;text-decoration:underline;
      text-underline-offset:3px`;
      toast.appendChild(link);
    }
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 6000);
  }

  function videoUrlFor(button) {
    const item = button.closest('[data-tts-id], [data-e2e="recommend-list-item-container"], article');
    const link = item?.querySelector('a[href*="/video/"], a[href*="/photo/"]');
    if (link) return link.href;
    if (location.pathname.includes("/video/") || location.pathname.includes("/photo/")) return location.href;
    const id = item?.dataset.ttsId || document.body.dataset.ttsCurrent;
    if (!id) return null;
    const handle = item?.querySelector('a[href^="/@"]')?.getAttribute("href") || "/@i";
    return `https://www.tiktok.com${handle}/video/${id}`;
  }

  function saveVideo(button) {
    if (!chrome.runtime?.id) {
      showToast(T("reload_page"));
      return;
    }
    const url = videoUrlFor(button);
    if (!url) {
      showToast(T("address_not_found"));
      return;
    }
    showToast(T("saving_video"));
    try {
      chrome.runtime.sendMessage({ type: "save", url }, (answer) => {
        if (chrome.runtime.lastError || !answer) {
          showToast(T("reload_page"));
          return;
        }
        showToast(answer.text, answer.ok);
      });
    } catch {
      showToast(T("reload_page"));
    }
  }

  function saveButton(size) {
    const btn = document.createElement("button");
    btn.title = `${T("save_song")} (Syntracks)`;
    btn.style.cssText = `width:${size}px;height:${size}px;border:none;border-radius:5px;cursor:pointer;
    display:flex;align-items:center;justify-content:center;flex:none;background:${BRAND}`;
    const glyph = Math.round(size * 0.46);
    btn.innerHTML = NOTE_SVG.replace('width="22" height="22"', `width="${glyph}" height="${glyph}"`);
    return btn;
  }

  function injectButtons() {
    document.querySelectorAll('[data-e2e="share-icon"]').forEach((shareIcon) => {
      const wrapper = shareIcon.closest("button")?.parentElement || shareIcon.parentElement;
      if (!wrapper?.parentElement || wrapper.parentElement.querySelector(".tts-save")) return;
      const holder = document.createElement("div");
      holder.className = "tts-save";
      holder.style.cssText = "display:flex;flex-direction:column;align-items:center;gap:4px;margin-top:8px";
      const btn = saveButton(48);
      btn.onclick = (e) => { e.stopPropagation(); saveVideo(btn); };
      const label = document.createElement("span");
      label.textContent = T("save_song");
      label.style.cssText = `font:700 11px/1.15 'Space Grotesk',system-ui,sans-serif;
      color:rgba(255,255,255,.9);text-align:center;max-width:56px`;
      holder.appendChild(btn);
      holder.appendChild(label);
      wrapper.parentElement.insertBefore(holder, wrapper.nextSibling);
    });
    document.querySelectorAll('[data-e2e="browse-share-group"]').forEach((group) => {
      if (group.querySelector(".tts-save")) return;
      const btn = saveButton(32);
      btn.className = "tts-save";
      btn.style.marginRight = "8px";
      btn.onclick = (e) => { e.stopPropagation(); e.preventDefault(); saveVideo(btn); };
      group.insertBefore(btn, group.firstChild);
    });
  }

  let injectPending = false;
  new MutationObserver(() => {
    if (injectPending) return;
    injectPending = true;
    requestAnimationFrame(() => { injectPending = false; injectButtons(); });
  }).observe(document.body, { childList: true, subtree: true });
  injectButtons();
})();
