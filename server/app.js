const $ = (id) => document.getElementById(id);
let token = localStorage.getItem("token");
let userName = localStorage.getItem("user") || "";
let isAdmin = false, mayDownload = false, songs = [], lastPending = [], filter = "ALL", view = "SONGS", registerMode = false;
let playing = null, pollTimer = null;
let page = 1;
// Die Seitengröße folgt der Fensterhöhe, damit die Liste nie über den Bildschirm hinausläuft
let perPage = 8, totalPages = 1;
let refitting = false;
const ROW_HEIGHT = 88;

const LANGS = { en: "English", de: "Deutsch", es: "Español", fr: "Français", pt: "Português", tr: "Türkçe" };
let LANG = localStorage.getItem("lang") || (navigator.language || "en").slice(0, 2).toLowerCase();
if (!LANGS[LANG]) LANG = "en";
let I18N = {};
function T(key, vars) {
  let text = (I18N[key] && (I18N[key][LANG] || I18N[key].en)) || key;
  for (const name in vars || {}) text = text.replace(`{${name}}`, vars[name]);
  return text;
}

const audio = new Audio();
// Gehör ist logarithmisch: Regler-Wert quadriert ergibt eine brauchbare Lautstärkekurve
const sliderToVolume = (v) => Math.pow(v / 100, 2);
let volSlider = parseInt(localStorage.getItem("volSlider") || "25", 10);
audio.volume = sliderToVolume(volSlider);

const IC = {
  play: '<svg class="ic" viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><path d="M7 4.5v15l13-7.5z"/></svg>',
  pause: '<svg class="ic" viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>',
  more: '<svg class="ic" viewBox="0 0 24 24" style="fill:currentColor;stroke:none"><circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/></svg>',
  star: '<svg viewBox="0 0 24 24"><path d="m12 2 3 6.6 7 .8-5.2 4.8L18.3 21 12 17.4 5.7 21l1.5-6.8L2 9.4l7-.8z"/></svg>',
  note: '<svg class="ic" viewBox="0 0 24 24"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>',
};

const SOURCE_KEY = {
  TIKTOK: "src_official", SHAZAM: "src_shazam", CAPTION: "src_caption",
  SIMILAR: "src_recommendation", ORIGINAL: "src_unknown",
};
const FILTER_KEY = { ALL: "filter_all", TIKTOK: "filter_official", SHAZAM: "filter_shazam",
                     CAPTION: "filter_caption", ORIGINAL: "filter_original" };
const VIEW_KEY = { SONGS: "view_history", FAV: "view_favorites", RECS: "view_recommendations" };
const STAGE_PROGRESS = { waiting: 10, loading_video: 40, identifying: 75, saving: 95 };

const sourceOf = (s) => s.similar ? "SIMILAR" : s.recognized ? "SHAZAM" : s.from_caption ? "CAPTION"
  : s.original ? "ORIGINAL" : "TIKTOK";

function toast(text) {
  $("toast").textContent = text;
  $("toast").classList.add("on");
  setTimeout(() => $("toast").classList.remove("on"), 2600);
}

async function api(path, opts = {}) {
  opts.headers = Object.assign({ "X-Token": token || "", "Accept-Language": LANG }, opts.headers);
  const r = await fetch(path, opts);
  if (r.status === 401 && token) {
    // Sitzung wurde serverseitig beendet (Anmeldung auf einem anderen Gerät)
    doLogout();
    $("login-err").textContent = T("session_expired");
  }
  if (!r.ok) throw new Error(await readError(r));
  return r;
}

async function readError(response) {
  // Bei einem Neustart antwortet der Proxy mit einer HTML-Seite, die roh im Fehlerfeld landen würde
  const text = (await response.text()).trim();
  return !text || text.startsWith("<") || text.length > 200
    ? `${T("server_error")} (${response.status})` : text;
}

function esc(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }
function fmtTime(sec) {
  if (!isFinite(sec)) return "0:00";
  return `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;
}
function relTime(iso) {
  const min = Math.round((Date.now() - new Date(iso)) / 60000);
  if (min < 60) return T("minutes_ago", { n: min });
  if (min < 1440) return T("hours_ago", { n: Math.round(min / 60) });
  return T("days_ago", { n: Math.round(min / 1440) });
}

function show(loggedIn) {
  $("login").classList.toggle("hidden", loggedIn);
  $("app").classList.toggle("hidden", !loggedIn);
}

function applyStaticText() {
  document.documentElement.lang = LANG;
  document.querySelectorAll("[data-i18n]").forEach(el => el.textContent = T(el.dataset.i18n));
  document.querySelectorAll("[data-ph]").forEach(el => el.placeholder = T(el.dataset.ph));
  document.querySelectorAll("[data-tip]").forEach(el => el.dataset.tiptext = T(el.dataset.tip));
  $("lang-pick").innerHTML = Object.entries(LANGS).map(([code, label]) =>
    `<option value="${code}"${code === LANG ? " selected" : ""}>${label}</option>`).join("");
  $("lang-pick").onchange = () => switchLang($("lang-pick").value);
  const theme = localStorage.getItem("theme") || "dark";
  $("theme-pick").innerHTML = ["dark", "light", "system"].map(mode =>
    `<option value="${mode}"${mode === theme ? " selected" : ""}>${T("theme_" + mode)}</option>`).join("");
  $("theme-pick").onchange = () => applyTheme($("theme-pick").value);
}

function switchLang(code) {
  localStorage.setItem("lang", code);
  location.reload();
}

function applyTheme(mode) {
  if (mode === "system") { localStorage.removeItem("theme"); delete document.documentElement.dataset.theme; }
  else { localStorage.setItem("theme", mode); document.documentElement.dataset.theme = mode; }
}

/* ---------- Player ---------- */
function playSource(id, src, name, artist, artwork) {
  playing = { clip: id };
  audio.src = src;
  audio.play().catch(() => { toast(T("playback_failed")); stopPlay(); });
  $("pb-cover").style.backgroundImage = artwork ? `url('${artwork}')` : "none";
  $("pb-name").textContent = name;
  $("pb-artist").textContent = artist;
  $("playerbar").classList.add("on");
  $("app").classList.add("with-player");
  render();
}
function startPlay(song, full) {
  playSource(song.clip, `/${full ? "full" : "preview"}?id=${song.clip}&token=${token}`,
    song.name, song.artist, song.artwork);
}
function stopPlay() {
  audio.pause();
  audio.removeAttribute("src");
  playing = null;
  $("playerbar").classList.remove("on");
  $("app").classList.remove("with-player");
  render();
}
audio.addEventListener("ended", stopPlay);
let seeking = false;
audio.addEventListener("timeupdate", () => {
  $("pb-cur").textContent = fmtTime(audio.currentTime);
  $("pb-dur").textContent = fmtTime(audio.duration);
  if (audio.duration && !seeking) $("pb-seek").value = Math.round(audio.currentTime / audio.duration * 1000);
});
function setToggle(state) {
  $("pb-toggle").innerHTML = state === "loading" ? '<span class="spinner"></span>'
    : state === "playing" ? IC.pause : IC.play;
}
setToggle("paused");
audio.addEventListener("loadstart", () => setToggle("loading"));
audio.addEventListener("waiting", () => setToggle("loading"));
audio.addEventListener("playing", () => setToggle("playing"));
audio.addEventListener("pause", () => setToggle("paused"));
$("pb-toggle").onclick = () => audio.paused ? audio.play() : audio.pause();
$("pb-seek").addEventListener("pointerdown", () => seeking = true);
$("pb-seek").addEventListener("pointerup", () => seeking = false);
$("pb-seek").oninput = () => { if (audio.duration) audio.currentTime = $("pb-seek").value / 1000 * audio.duration; };
$("pb-vol").value = volSlider;
$("pb-vol").oninput = () => {
  volSlider = parseInt($("pb-vol").value, 10);
  audio.volume = sliderToVolume(volSlider);
  localStorage.setItem("volSlider", volSlider);
};
$("pb-close").onclick = stopPlay;

/* ---------- Laden ---------- */
async function load() {
  try {
    const data = await (await api("/songs")).json();
    userName = data.user; isAdmin = data.admin; mayDownload = data.downloads;
    localStorage.setItem("user", userName);
    songs = data.songs;
    lastPending = data.pending;
    $("whoami").textContent = `@${userName} · ${T("songs_count", { n: songs.length })}`;
    renderStats();
    render();
    schedulePoll();
  } catch (e) {
    $("list").innerHTML = `<div class="sub" style="padding:30px;text-align:center">${esc(e.message)}</div>`;
  }
}
function schedulePoll() {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(() => { if (token && !document.hidden) load(); else schedulePoll(); },
    lastPending.length ? 4000 : 12000);
}

function renderStats() {
  $("stats").innerHTML =
    `<button class="taste-btn" id="taste">${IC.note} ${T("your_taste")}</button>` +
    (view === "SONGS"
      ? `<span class="chip click ${filter !== "ALL" ? "active" : ""}" id="filter-btn">${T("filter_prefix")} ${T(FILTER_KEY[filter])}</span>`
      : "");
  $("taste").onclick = openTaste;
  const filterBtn = $("filter-btn");
  if (filterBtn) filterBtn.onclick = (ev) => {
    ev.stopPropagation();
    closeMenu();
    const items = Object.keys(FILTER_KEY).map(f =>
      [T(FILTER_KEY[f]) + (f === filter ? "  ✓" : ""), () => { filter = f; page = 1; renderStats(); render(); }]);
    anchorMenu(buildMenu(items), filterBtn);
  };
  $("filters").innerHTML = Object.keys(VIEW_KEY).map(v =>
    `<span class="tab ${v === view ? "active" : ""}" data-v="${v}">${T(VIEW_KEY[v])}</span>`).join("");
  document.querySelectorAll("#filters .tab").forEach(el =>
    el.onclick = () => { view = el.dataset.v; page = 1; renderStats(); render(); });
}

/* ---------- Empfehlungen ---------- */
let recs = null, recsLoading = false;
async function loadRecs() {
  if (recsLoading) return;
  recsLoading = true;
  try { recs = (await (await api("/recommendations")).json()).recommendations; }
  catch (e) { recs = []; }
  recsLoading = false;
  render();
}

/* ---------- Rendern ---------- */
function fitPerPage() {
  const list = $("list");
  const card = list.querySelector(".card");
  // Gemessen statt geschätzt, sonst wird die letzte Zeile angeschnitten
  const rowHeight = card ? card.getBoundingClientRect().height + 8 : ROW_HEIGHT;
  const next = Math.max(3, Math.floor(list.clientHeight / rowHeight));
  const changed = next !== perPage;
  perPage = next;
  return changed;
}

function pagerHtml(total) {
  const pages = Math.ceil(total / perPage);
  if (pages < 2) return "";
  // Bei vielen Seiten nur Anfang, Umgebung der aktuellen Seite und Ende zeigen
  const wanted = new Set([1, pages, page, page - 1, page + 1]);
  if (page <= 3) [2, 3, 4].forEach(n => wanted.add(n));
  if (page >= pages - 2) [pages - 1, pages - 2, pages - 3].forEach(n => wanted.add(n));
  const numbers = [...wanted].filter(n => n >= 1 && n <= pages).sort((a, b) => a - b);
  let html = `<div class="pager"><button data-page="${page - 1}"${page === 1 ? " disabled" : ""} aria-label="${T("page_prev")}">‹</button>`;
  numbers.forEach((n, i) => {
    if (i && n - numbers[i - 1] > 1) html += `<span class="gap">…</span>`;
    html += `<button data-page="${n}" class="${n === page ? "on" : ""}">${n}</button>`;
  });
  html += `<button data-page="${page + 1}"${page === pages ? " disabled" : ""} aria-label="${T("page_next")}">›</button>`;
  return html + `<input class="page-jump" id="page-jump" type="number" min="1" max="${pages}"
                        inputmode="numeric" placeholder="${page}/${pages}" aria-label="${T("page_go")}"></div>`;
}

function goToPage(target, lastPage) {
  const next = Math.min(Math.max(1, target), lastPage);
  if (next === page) return;
  page = next;
  render();
}

function render() {
  const q = $("search").value.toLowerCase();
  const shown = view === "RECS" ? [] : songs.filter(s => {
    const src = sourceOf(s);
    if (view === "FAV") { if (!s.favorite) return false; }
    else {
      if (src === "SIMILAR") return false;
      if (filter !== "ALL" && src !== filter) return false;
    }
    return !q || (s.name || "").toLowerCase().includes(q) || (s.artist || "").toLowerCase().includes(q);
  });
  let html = "";
  if (lastPending.length) {
    const stage = lastPending[0].stage || "waiting";
    html += `<div class="pendcard">
      <div class="pendrow"><div class="spinner"></div>
        <div>${lastPending.length === 1 ? T("processing_one") : T("processing_many", { n: lastPending.length })}
          <small>${esc(T(stage))}</small></div></div>
      <div class="pendbar"><div style="width:${STAGE_PROGRESS[stage] || 10}%"></div></div>
    </div>`;
  }
  if (view === "RECS") {
    if (recs === null && !recsLoading) loadRecs();
    html += `<div class="pendcard"><b style="font-size:14px">${T("for_you")}</b>
      <div class="sub" style="margin-bottom:10px">${T("based_on_recent")}</div>`;
    html += recs === null
      ? `<div class="pendrow"><div class="spinner"></div><div>${T("searching_recs")}</div></div>`
      : recs.length
        ? recs.map((t, i) => `<div class="mrow">
            <div class="cover" ${t.artwork ? `style="background-image:url('${esc(t.artwork)}')"` : ""} data-rp="${i}">
              <div class="ply">${playing && playing.clip === "rec:" + i ? IC.pause : IC.play}</div></div>
            <div class="meta"><b>${esc(t.track)}</b><span class="artist">${esc(t.artist)}</span></div>
            <button class="add" data-ra="${i}" title="${T("add_to_favorites")}">+</button>
          </div>`).join("")
        : `<div class="sub">${T("no_recs_now")}</div>`;
    html += `</div>`;
  }
  if (!shown.length && !lastPending.length && view !== "RECS") {
    html += `<div class="sub" style="padding:56px 0;text-align:center;font-size:15px">${T("nothing_found")}</div>`;
  }
  const lastPage = Math.max(1, Math.ceil(shown.length / perPage));
  if (page > lastPage) page = lastPage;
  html += shown.slice((page - 1) * perPage, page * perPage).map(s => {
    const src = sourceOf(s);
    const idx = songs.indexOf(s);
    const isPlaying = playing && playing.clip === s.clip;
    return `<div class="card ${isPlaying ? "playing" : ""}">
      <div class="cover" ${s.artwork ? `style="background-image:url('${esc(s.artwork)}')"` : ""} data-play="${idx}">
        <div class="ply">${isPlaying ? IC.pause : IC.play}</div></div>
      <div class="meta">
        <b>${esc(s.name)}</b><span class="artist">${esc(s.artist)}</span>
        <span class="badge-row"><span class="badge">${T(SOURCE_KEY[src])}</span>
        ${s.favorite ? `<span class="fav-star" title="${T("view_favorites")}">${IC.star}</span>` : ""}
        <span class="time">${relTime(s.saved_at)}</span></span>
      </div>
      <button class="icon-btn bare" data-menu="${idx}">${IC.more}</button>
    </div>`;
  }).join("");
  $("list").innerHTML = html;
  $("pager").innerHTML = pagerHtml(shown.length);
  if (!refitting && fitPerPage()) { refitting = true; render(); refitting = false; return; }
  totalPages = Math.max(1, Math.ceil(shown.length / perPage));
  document.querySelectorAll("[data-page]").forEach(el => el.onclick = () => goToPage(+el.dataset.page, totalPages));
  const jump = $("page-jump");
  if (jump) jump.oninput = () => {
    const wanted = parseInt(jump.value, 10);
    // Erst springen, wenn die Zahl im gültigen Bereich liegt, sonst zappelt es bei jeder Ziffer
    if (!wanted || wanted < 1 || wanted > totalPages) return;
    const keep = jump.value;
    goToPage(wanted, totalPages);
    const fresh = $("page-jump");
    if (fresh) { fresh.value = keep; fresh.focus(); }
  };
  document.querySelectorAll("[data-play]").forEach(el => el.onclick = () => {
    const s = songs[el.dataset.play];
    if (playing && playing.clip === s.clip) stopPlay(); else startPlay(s, false);
  });
  document.querySelectorAll("[data-menu]").forEach(el => el.onclick = (ev) => {
    ev.stopPropagation();
    openSongMenu(songs[el.dataset.menu], el);
  });
  document.querySelectorAll("[data-rp]").forEach(el => el.onclick = () => {
    const t = recs[el.dataset.rp];
    if (playing && playing.clip === "rec:" + el.dataset.rp) { stopPlay(); return; }
    if (!t.preview) { toast(T("no_preview")); return; }
    playSource("rec:" + el.dataset.rp, t.preview, t.track, t.artist, t.artwork);
  });
  document.querySelectorAll("[data-ra]").forEach(el => el.onclick = async () => {
    const t = recs[el.dataset.ra];
    try {
      await api("/save-similar", { method: "POST", body: JSON.stringify(t) });
      el.textContent = "✓"; el.classList.add("done");
      toast(T("added_to_favorites"));
      load();
    } catch (e) { toast(T("save_failed")); }
  });
}

/* ---------- Menüs ---------- */
function closeMenu() { document.querySelectorAll(".menu").forEach(m => m.remove()); }
document.addEventListener("click", closeMenu);

function anchorMenu(menu, anchorEl) {
  document.body.appendChild(menu);
  const a = anchorEl.getBoundingClientRect();
  const m = menu.getBoundingClientRect();
  let top = a.bottom + 6;
  if (top + m.height > innerHeight - 8) top = a.top - m.height - 6;
  menu.style.left = Math.max(8, a.right - m.width) + "px";
  menu.style.top = Math.max(8, top) + "px";
}

function buildMenu(items) {
  const menu = document.createElement("div");
  menu.className = "menu";
  items.forEach(([label, fn, cls]) => {
    if (label === "h") {
      const h = document.createElement("h5"); h.textContent = fn; menu.appendChild(h);
    } else {
      const b = document.createElement("button");
      b.textContent = label; if (cls) b.className = cls;
      b.onclick = (e) => { e.stopPropagation(); closeMenu(); fn(); };
      menu.appendChild(b);
    }
  });
  return menu;
}

function openSongMenu(song, anchorEl) {
  closeMenu();
  const src = sourceOf(song);
  const isOriginal = src === "ORIGINAL", isSimilar = src === "SIMILAR";
  const items = [["h", T("group_play")]];
  if (!isOriginal) items.push([T("play_full_song"), () => startPlay(song, true)]);
  items.push([isSimilar ? T("open_deezer") : T("open_tiktok"), () => window.open(song.url, "_blank")]);
  items.push(["h", T("group_collection")]);
  items.push([song.favorite ? T("remove_from_favorites") : T("add_to_favorites"), async () => {
    await api("/favorite", { method: "POST",
      body: JSON.stringify({ saved_at: song.saved_at, value: !song.favorite }) });
    toast(song.favorite ? T("removed_from_favorites") : T("added_to_favorites"));
    load();
  }]);
  if (!isOriginal) {
    items.push(["h", T("group_discover")]);
    items.push([T("similar_songs"), () => openSimilar(song)]);
    items.push([T("search_spotify"), () =>
      window.open("https://open.spotify.com/search/" + encodeURIComponent(song.artist + " " + song.name), "_blank")]);
    items.push([T("search_ytmusic"), () =>
      window.open("https://music.youtube.com/search?q=" + encodeURIComponent(song.artist + " " + song.name), "_blank")]);
  }
  if (mayDownload) {
    items.push(["h", T("group_download")]);
    items.push([isOriginal ? T("sound_as_mp3") : T("song_as_mp3"),
      () => window.open(`/download-mp3?id=${song.clip}&token=${token}`, "_blank")]);
    if (!isSimilar) items.push([T("video_as_mp4"),
      () => window.open(`/download-mp4?id=${song.clip}&token=${token}`, "_blank")]);
  }
  items.push(["h", T("group_share")]);
  items.push([T("copy_name"), () => {
    navigator.clipboard.writeText(`${song.artist} - ${song.name}`);
    toast(T("copied"));
  }]);
  items.push([T("delete"), async () => {
    if (playing && playing.clip === song.clip) stopPlay();
    await api("/delete", { method: "POST", body: song.saved_at });
    toast(T("song_deleted"));
    load();
  }, "danger"]);
  anchorMenu(buildMenu(items), anchorEl);
}

function openProfileMenu() {
  closeMenu();
  const items = [
    ["h", `@${userName} · ${T("songs_count", { n: songs.length })}`],
    [T("change_name"), openRename],
    [T("change_password"), openPassword],
    [T("language"), openLanguage],
    [T("appearance"), openAppearance],
  ];
  if (isAdmin) items.push([T("manage_accounts"), openAdmin]);
  items.push([T("sign_out"), doLogout, "danger"]);
  const menu = buildMenu(items);
  anchorMenu(menu, $("profile"));
  menu.onmouseenter = () => clearTimeout(menuTimer);
  menu.onmouseleave = closeMenu;
}
let menuTimer = null;
$("profile").onclick = (ev) => { ev.stopPropagation(); openProfileMenu(); };
document.querySelector(".profile-wrap").onmouseenter = () => { clearTimeout(menuTimer); openProfileMenu(); };
document.querySelector(".profile-wrap").onmouseleave = () => {
  menuTimer = setTimeout(() => { if (!document.querySelector(".menu:hover")) closeMenu(); }, 220);
};

/* ---------- Overlays ---------- */
function modal(title, bodyHtml) {
  const ov = document.createElement("div");
  ov.className = "overlay";
  ov.innerHTML = `<div class="modal"><header><b>${title}</b>
    <button class="icon-btn bare close"><svg class="ic" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg></button>
    </header><div class="body">${bodyHtml}</div></div>`;
  ov.onclick = (e) => { if (e.target === ov) close(); };
  function close() { ov.remove(); }
  ov.querySelector(".close").onclick = close;
  $("overlays").appendChild(ov);
  return { el: ov, close };
}

function openAppearance() {
  const current = localStorage.getItem("theme") || "dark";
  const m = modal(T("appearance"), ["dark", "light", "system"].map(mode =>
    `<button class="btn-ghost" data-theme-pick="${mode}" style="margin-bottom:8px">${T("theme_" + mode)}${mode === current ? "  ✓" : ""}</button>`).join(""));
  m.el.querySelectorAll("[data-theme-pick]").forEach(el => el.onclick = () => {
    applyTheme(el.dataset.themePick);
    m.close();
  });
}

function openLanguage() {
  const m = modal(T("language"), Object.entries(LANGS).map(([code, label]) =>
    `<button class="btn-ghost" data-lang="${code}" style="margin-bottom:8px">${label}${code === LANG ? "  ✓" : ""}</button>`).join(""));
  m.el.querySelectorAll("[data-lang]").forEach(el => el.onclick = () => switchLang(el.dataset.lang));
}

async function openSimilar(seed) {
  const m = modal(T("similar_to", { name: esc(seed.name) }),
    `<div class="sub" style="padding:12px 0">${T("searching_similar")}</div>`);
  let tracks;
  try {
    tracks = (await (await api(`/similar?id=${seed.clip}`)).json()).similar;
  } catch (e) { m.el.querySelector(".body").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
  if (!tracks.length) { m.el.querySelector(".body").innerHTML = `<div class="sub">${T("nothing_found")}</div>`; return; }
  m.el.querySelector(".body").innerHTML = tracks.map((t, i) => `<div class="mrow">
    <div class="cover" ${t.artwork ? `style="background-image:url('${esc(t.artwork)}')"` : ""} data-sp="${i}">
      <div class="ply">${IC.play}</div></div>
    <div class="meta"><b>${esc(t.track)}</b><span class="artist">${esc(t.artist)}</span></div>
    <button class="add" data-sa="${i}" title="${T("add_to_favorites")}">+</button>
  </div>`).join("");
  const syncIcons = () => m.el.querySelectorAll("[data-sp]").forEach(el =>
    el.querySelector(".ply").innerHTML =
      playing && playing.clip === "sim:" + el.dataset.sp ? IC.pause : IC.play);
  m.el.querySelectorAll("[data-sp]").forEach(el => el.onclick = () => {
    const t = tracks[el.dataset.sp];
    if (playing && playing.clip === "sim:" + el.dataset.sp) { stopPlay(); syncIcons(); return; }
    if (!t.preview) { toast(T("no_preview")); return; }
    playSource("sim:" + el.dataset.sp, t.preview, t.track, t.artist, t.artwork);
    syncIcons();
  });
  m.el.querySelectorAll("[data-sa]").forEach(el => el.onclick = async () => {
    const t = tracks[el.dataset.sa];
    try {
      await api("/save-similar", { method: "POST", body: JSON.stringify(t) });
      el.textContent = "✓"; el.classList.add("done");
      toast(T("added_to_favorites"));
      load();
    } catch (e) { toast(T("save_failed")); }
  });
  const origClose = m.close;
  const closeAll = () => { if (playing && String(playing.clip).startsWith("sim:")) stopPlay(); origClose(); };
  m.el.querySelector(".close").onclick = closeAll;
  m.el.onclick = (e) => { if (e.target === m.el) closeAll(); };
}

function openTaste() {
  const genres = {};
  songs.forEach(s => { if (s.genre) genres[s.genre] = (genres[s.genre] || 0) + 1; });
  const sorted = Object.entries(genres).sort((a, b) => b[1] - a[1]).slice(0, 6);
  const max = sorted.length ? sorted[0][1] : 1;
  const artists = {};
  songs.forEach(s => artists[s.artist] = (artists[s.artist] || 0) + 1);
  const tops = Object.entries(artists).sort((a, b) => b[1] - a[1]).slice(0, 3);
  const week = songs.filter(s => Date.now() - new Date(s.saved_at) < 7 * 864e5).length;
  const favs = songs.filter(s => s.favorite).length;
  modal(T("your_taste"),
    `<div class="chips" style="margin-bottom:4px">
       <span class="chip">${T("songs_count", { n: songs.length })}</span>
       <span class="chip">${T("this_week", { n: week })}</span>
       <span class="chip">${T("favorites_count", { n: favs })}</span>
     </div>` +
    (sorted.length
      ? sorted.map(([g, c]) => `<div><div style="display:flex;font-size:13px;margin-bottom:5px">
          <span style="flex:1">${esc(g)}</span><span style="color:var(--dim)">${c}</span></div>
          <div class="genrebar"><div style="width:${Math.round(c / max * 100)}%"></div></div></div>`).join("")
      : `<div class="sub">${T("no_genre_data")}</div>`) +
    `<b style="font-size:14px;margin-top:6px">${T("top_artists")}</b>` +
    tops.map(([a, c]) => `<div style="display:flex;font-size:13px">
      <span style="flex:1">${esc(a)}</span>
      <span style="color:var(--dim)">${c === 1 ? T("one_song") : T("songs_count", { n: c })}</span></div>`).join(""));
}

function openRename() {
  const m = modal(T("change_name"),
    `<input type="text" id="rn" placeholder="${T("new_username")}"><div class="err" id="rn-err"></div>
     <button class="btn-primary" id="rn-btn">${T("save")}</button>`);
  m.el.querySelector("#rn-btn").onclick = async () => {
    try {
      const r = await api("/rename", { method: "POST",
        body: JSON.stringify({ new: m.el.querySelector("#rn").value.trim() }) });
      userName = (await r.json()).user;
      localStorage.setItem("user", userName);
      toast(T("renamed_to", { name: userName }));
      m.close(); load();
    } catch (e) { m.el.querySelector("#rn-err").textContent = e.message; }
  };
}

function openPassword() {
  const m = modal(T("change_password"),
    `<input type="password" id="p-old" placeholder="${T("current_password")}">
     <input type="password" id="p-new" placeholder="${T("new_password")}">
     <input type="password" id="p-conf" placeholder="${T("confirm_password")}">
     <div class="err" id="p-err"></div><button class="btn-primary" id="p-btn">${T("change")}</button>`);
  m.el.querySelector("#p-btn").onclick = async () => {
    const oldPw = m.el.querySelector("#p-old").value,
          newPw = m.el.querySelector("#p-new").value,
          confPw = m.el.querySelector("#p-conf").value;
    if (newPw !== confPw) { m.el.querySelector("#p-err").textContent = T("passwords_mismatch"); return; }
    try {
      await api("/change-password", { method: "POST", body: JSON.stringify({ old: oldPw, new: newPw }) });
      toast(T("change_password"));
      m.close();
    } catch (e) { m.el.querySelector("#p-err").textContent = e.message; }
  };
}

async function openAdmin() {
  const m = modal(T("manage_accounts"), `<div class="sub">${T("loading")}</div>`);
  async function refresh() {
    let users;
    try { users = (await (await api("/admin/users")).json()).users; }
    catch (e) { m.el.querySelector(".body").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
    m.el.querySelector(".body").innerHTML = users.map(u => `<div class="adm-row">
      <div class="adm-av">${esc(u.name[0])}</div>
      <div class="meta"><b style="color:${u.admin ? "var(--brand)" : "inherit"}">@${esc(u.name)}${u.admin ? " · Admin" : ""}</b>
        <span class="artist">${T("songs_count", { n: u.songs })}${u.downloads ? " · " + T("downloads_enabled") : ""}</span></div>
      <div class="adm-actions">
        <button class="adm-btn cyan" data-vu="${esc(u.name)}">${T("view_action")}</button>
        <button class="adm-btn" data-rp2="${esc(u.name)}">${T("password_action")}</button>
        ${u.admin ? "" : `<button class="adm-btn" data-dl="${esc(u.name)}" data-on="${u.downloads ? 1 : 0}">${u.downloads ? T("block_downloads") : T("allow_downloads")}</button>`}
        ${u.name !== userName ? `<button class="adm-btn danger" data-du="${esc(u.name)}">${T("delete")}</button>` : ""}
      </div>
    </div>`).join("");
    m.el.querySelectorAll("[data-vu]").forEach(el => el.onclick = () => openUserLibrary(el.dataset.vu));
    m.el.querySelectorAll("[data-dl]").forEach(el => el.onclick = async () => {
      await api("/admin/set-downloads", { method: "POST",
        body: JSON.stringify({ user: el.dataset.dl, value: el.dataset.on !== "1" }) });
      refresh();
    });
    m.el.querySelectorAll("[data-rp2]").forEach(el => el.onclick = () => {
      const target = el.dataset.rp2;
      const pm = modal(T("password_for", { name: esc(target) }),
        `<input type="password" id="ap" placeholder="${T("new_password")}">
         <input type="password" id="ap2" placeholder="${T("confirm_password")}">
         <div class="err" id="ap-err"></div>
         <button class="btn-primary" id="ap-btn">${T("reset")}</button>`);
      pm.el.querySelector("#ap-btn").onclick = async () => {
        const p1 = pm.el.querySelector("#ap").value, p2 = pm.el.querySelector("#ap2").value;
        if (p1 !== p2) { pm.el.querySelector("#ap-err").textContent = T("passwords_mismatch"); return; }
        try {
          await api("/admin/reset-password", { method: "POST",
            body: JSON.stringify({ user: target, new: p1 }) });
          toast(T("reset_done")); pm.close();
        } catch (e) { pm.el.querySelector("#ap-err").textContent = e.message; }
      };
    });
    m.el.querySelectorAll("[data-du]").forEach(el => el.onclick = async () => {
      if (!confirm(T("delete_account_confirm", { name: el.dataset.du }))) return;
      await api("/admin/delete-user", { method: "POST", body: el.dataset.du });
      refresh();
    });
  }
  refresh();
}

async function openUserLibrary(name) {
  const m = modal(T("collection_of", { name: esc(name) }), `<div class="sub">${T("loading")}</div>`);
  let songs;
  try { songs = (await (await api(`/admin/user-songs?user=${encodeURIComponent(name)}`)).json()).songs; }
  catch (e) { m.el.querySelector(".body").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
  let recs = null, tab = "SONGS";
  const songRow = s => `<div class="mrow">
    ${s.artwork ? `<img class="cover" src="${esc(s.artwork)}">` : `<div class="cover" ></div>`}
    <div class="meta"><b>${esc(s.name)}${s.favorite ? ` <span style="color:var(--gold)">★</span>` : ""}</b>
      <span class="artist">${esc(s.artist || T("unknown_artist"))} · ${T(SOURCE_KEY[sourceOf(s)])}</span></div>
  </div>`;
  const recRow = t => `<div class="mrow">
    ${t.artwork ? `<img class="cover" src="${esc(t.artwork)}">` : `<div class="cover" ></div>`}
    <div class="meta"><b>${esc(t.track)}</b><span class="artist">${esc(t.artist || T("unknown_artist"))}</span></div>
  </div>`;
  async function renderLib() {
    let rows;
    if (tab === "RECS") {
      if (!recs) {
        try { recs = (await (await api(`/admin/user-recommendations?user=${encodeURIComponent(name)}`)).json()).recommendations; }
        catch { recs = []; }
      }
      rows = recs.length ? recs.map(recRow).join("") : `<div class="sub">${T("no_recommendations")}</div>`;
    } else {
      const list = tab === "FAV" ? songs.filter(s => s.favorite) : songs;
      rows = list.length ? list.map(songRow).join("") : `<div class="sub">${T("nothing_here")}</div>`;
    }
    m.el.querySelector(".body").innerHTML = `<div class="libtabs">
      ${Object.keys(VIEW_KEY).map(k =>
        `<span class="tab${tab === k ? " active" : ""}" data-lt="${k}">${T(VIEW_KEY[k])}</span>`).join("")}
    </div>` + rows;
    m.el.querySelectorAll("[data-lt]").forEach(el => el.onclick = () => { tab = el.dataset.lt; renderLib(); });
  }
  renderLib();
}

/* ---------- Auth ---------- */
async function doAuth() {
  $("login-err").textContent = "";
  try {
    if (registerMode && $("pass").value !== $("pass2").value) {
      $("login-err").textContent = T("passwords_mismatch");
      return;
    }
    const r = await fetch(registerMode ? "/register" : "/login", {
      method: "POST",
      headers: { "Accept-Language": LANG },
      body: JSON.stringify({ user: $("user").value.trim(), pass: $("pass").value }),
    });
    if (!r.ok) throw new Error(await readError(r));
    const data = await r.json();
    token = data.token; userName = data.user;
    localStorage.setItem("token", token); localStorage.setItem("user", userName);
    show(true); load();
  } catch (e) { $("login-err").textContent = e.message; }
}
function doLogout() {
  stopPlay();
  localStorage.removeItem("token"); localStorage.removeItem("user");
  token = null; show(false);
}

$("login-btn").onclick = doAuth;
$("pass").addEventListener("keydown", e => { if (e.key === "Enter") doAuth(); });
function setRegisterMode(on) {
  registerMode = on;
  $("login-mode").textContent = registerMode ? T("create_account") : T("sign_in");
  $("login-btn").textContent = registerMode ? T("create_account") : T("sign_in");
  $("toggle-register").textContent = registerMode ? T("already_account") : T("new_here");
  $("pass2").classList.toggle("hidden", !registerMode);
  $("pass").autocomplete = registerMode ? "new-password" : "current-password";
}
$("toggle-register").onclick = () => setRegisterMode(!registerMode);
$("search").addEventListener("input", () => { page = 1; render(); });
document.addEventListener("visibilitychange", () => { if (!document.hidden && token) load(); });
addEventListener("resize", () => { if (fitPerPage()) render(); });

// In der Liste blättert das Rad die Seiten weiter, gescrollt wird hier nichts
let wheelLock = 0;
$("list").addEventListener("wheel", (ev) => {
  ev.preventDefault();
  if (Date.now() < wheelLock || Math.abs(ev.deltaY) < 4) return;
  wheelLock = Date.now() + 220;
  goToPage(page + (ev.deltaY > 0 ? 1 : -1), totalPages);
}, { passive: false });

let touchStartY = null;
$("list").addEventListener("touchstart", (ev) => { touchStartY = ev.touches[0].clientY; }, { passive: true });
$("list").addEventListener("touchend", (ev) => {
  if (touchStartY === null) return;
  const moved = touchStartY - ev.changedTouches[0].clientY;
  touchStartY = null;
  if (Math.abs(moved) > 60) goToPage(page + (moved > 0 ? 1 : -1), totalPages);
}, { passive: true });

async function boot() {
  try { I18N = await (await fetch("/i18n.json")).json(); } catch (e) { I18N = {}; }
  applyStaticText();
  setRegisterMode(false);
  $("howto-btn").onclick = () => modal(T("howto_title"), `<ol class="howto-list">
    <li>${T("howto_account")}</li>
    <li>${T("howto_android")}</li>
    <li>${T("howto_browser")}</li>
    <li>${T("howto_iphone")}</li>
    <li>${T("howto_collection")}</li>
  </ol>`);
  if (token) { show(true); fitPerPage(); load(); } else { show(false); }
}
boot();
