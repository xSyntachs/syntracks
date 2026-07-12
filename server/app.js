const $ = (id) => document.getElementById(id);
let token = localStorage.getItem("token");
let userName = localStorage.getItem("user") || "";
let isAdmin = false, songs = [], lastPending = [], filter = "ALLE", registerMode = false;
let playing = null, pollTimer = null;

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

const SOURCES = {
  TIKTOK:  { label: "Offizieller Song", color: "rgba(254,44,85,.5)" },
  SHAZAM:  { label: "Per Shazam erkannt", color: "rgba(120,40,200,.6)" },
  CAPTION: { label: "Aus Video-Text gelesen", color: "rgba(245,197,66,.45)" },
  SIMILAR: { label: "Empfehlung", color: "rgba(76,217,100,.45)" },
  ORIGINAL:{ label: "Nicht erkannt", color: "rgba(255,255,255,.18)" },
};
const FILTER_LABEL = { ALLE: "Alle", FAV: "Favoriten", TIKTOK: "Offiziell", SHAZAM: "Shazam",
                       CAPTION: "Aus Caption", SIMILAR: "Empfehlungen", ORIGINAL: "Original" };
const STAGE_PROGRESS = { "Wartet": 10, "Video wird geladen": 40, "Song wird erkannt": 75, "Wird gespeichert": 95 };

const sourceOf = (s) => s.similar ? "SIMILAR" : s.recognized ? "SHAZAM" : s.from_caption ? "CAPTION"
  : s.original ? "ORIGINAL" : "TIKTOK";

function toast(text) {
  $("toast").textContent = text;
  $("toast").classList.add("on");
  setTimeout(() => $("toast").classList.remove("on"), 2600);
}

async function api(path, opts = {}) {
  opts.headers = Object.assign({ "X-Token": token || "" }, opts.headers);
  const r = await fetch(path, opts);
  if (!r.ok) throw new Error(await r.text());
  return r;
}

function esc(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }
function fmtTime(sec) {
  if (!isFinite(sec)) return "0:00";
  return `${Math.floor(sec / 60)}:${String(Math.floor(sec % 60)).padStart(2, "0")}`;
}
function relTime(iso) {
  const min = Math.round((Date.now() - new Date(iso)) / 60000);
  if (min < 60) return `vor ${min} Min.`;
  if (min < 1440) return `vor ${Math.round(min / 60)} Std.`;
  return `vor ${Math.round(min / 1440)} Tagen`;
}

function show(loggedIn) {
  $("login").classList.toggle("hidden", loggedIn);
  $("app").classList.toggle("hidden", !loggedIn);
}

/* ---------- Player ---------- */
function playSource(id, src, name, artist, artwork) {
  playing = { clip: id };
  audio.src = src;
  audio.play().catch(() => { toast("Abspielen fehlgeschlagen"); stopPlay(); });
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
    userName = data.user; isAdmin = data.admin;
    localStorage.setItem("user", userName);
    songs = data.songs;
    lastPending = data.pending;
    $("whoami").textContent = "@" + userName;
    renderStats();
    render();
    schedulePoll();
  } catch (e) {
    if (String(e.message).includes("angemeldet")) { doLogout(); return; }
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
    `<button class="taste-btn" id="taste">${IC.note} Dein Geschmack</button>` +
    `<span class="chip click ${filter !== "ALLE" ? "active" : ""}" id="filter-btn">Filter: ${FILTER_LABEL[filter]}</span>`;
  $("taste").onclick = openTaste;
  $("filter-btn").onclick = (ev) => {
    ev.stopPropagation();
    closeMenu();
    const items = Object.keys(FILTER_LABEL).map(f =>
      [FILTER_LABEL[f] + (f === filter ? "  ✓" : ""), () => { filter = f; renderStats(); render(); }]);
    anchorMenu(buildMenu(items), $("filter-btn"));
  };
  $("filters").innerHTML = "";
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
function render() {
  const q = $("search").value.toLowerCase();
  const shown = filter === "SIMILAR" ? [] : songs.filter(s => {
    const src = sourceOf(s);
    if (filter === "ALLE" && src === "SIMILAR") return false;
    if (filter === "FAV" && !s.favorite) return false;
    if (filter !== "ALLE" && filter !== "FAV" && src !== filter) return false;
    return !q || (s.name || "").toLowerCase().includes(q) || (s.artist || "").toLowerCase().includes(q);
  });
  let html = "";
  if (lastPending.length) {
    const stage = lastPending[0].stage || "Wartet";
    html += `<div class="pendcard">
      <div class="pendrow"><div class="spinner"></div>
        <div>${lastPending.length === 1 ? "1 Song wird verarbeitet…" : lastPending.length + " Songs werden verarbeitet…"}
          <small>${esc(stage)}</small></div></div>
      <div class="pendbar"><div style="width:${STAGE_PROGRESS[stage] || 10}%"></div></div>
    </div>`;
  }
  if (filter === "SIMILAR") {
    if (recs === null && !recsLoading) loadRecs();
    html += `<div class="pendcard"><b style="font-size:14px">Für dich</b>
      <div class="sub" style="margin-bottom:10px">Auf Basis deiner letzten Songs</div>`;
    html += recs === null
      ? `<div class="pendrow"><div class="spinner"></div><div>Empfehlungen werden gesucht…</div></div>`
      : recs.length
        ? recs.map((t, i) => `<div class="mrow">
            <div class="cover" ${t.artwork ? `style="background-image:url('${esc(t.artwork)}')"` : ""} data-rp="${i}">
              <div class="ply">${playing && playing.clip === "rec:" + i ? IC.pause : IC.play}</div></div>
            <div class="meta"><b>${esc(t.track)}</b><span class="artist">${esc(t.artist)}</span></div>
            <button class="add" data-ra="${i}" title="Zu Favoriten hinzufügen">+</button>
          </div>`).join("")
        : `<div class="sub">Gerade keine neuen Empfehlungen.</div>`;
    html += `</div>`;
  }
  if (!shown.length && !lastPending.length && filter !== "SIMILAR") {
    html += `<div class="sub" style="padding:56px 0;text-align:center;font-size:15px">Nichts gefunden.</div>`;
  }
  html += shown.map(s => {
    const src = sourceOf(s), info = SOURCES[src];
    const idx = songs.indexOf(s);
    const isPlaying = playing && playing.clip === s.clip;
    return `<div class="card ${isPlaying ? "playing" : ""}">
      <div class="cover" ${s.artwork ? `style="background-image:url('${esc(s.artwork)}')"` : ""} data-play="${idx}">
        <div class="ply">${isPlaying ? IC.pause : IC.play}</div></div>
      <div class="meta">
        <b>${esc(s.name)}</b><span class="artist">${esc(s.artist)}</span>
        <span class="badge-row"><span class="badge" style="background:${info.color}">${info.label}</span>
        ${s.favorite ? `<span class="fav-star" title="Favorit">${IC.star}</span>` : ""}
        <span class="time">${relTime(s.saved_at)}</span></span>
      </div>
      <button class="icon-btn bare" data-menu="${idx}" title="Aktionen">${IC.more}</button>
    </div>`;
  }).join("");
  $("list").innerHTML = html;
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
    if (!t.preview) { toast("Keine Vorschau verfügbar"); return; }
    playSource("rec:" + el.dataset.rp, t.preview, t.track, t.artist, t.artwork);
  });
  document.querySelectorAll("[data-ra]").forEach(el => el.onclick = async () => {
    const t = recs[el.dataset.ra];
    try {
      await api("/save-similar", { method: "POST", body: JSON.stringify(t) });
      el.textContent = "✓"; el.classList.add("done");
      toast("Zu Favoriten hinzugefügt");
      load();
    } catch (e) { toast("Speichern fehlgeschlagen"); }
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
  const items = [["h", "Abspielen"]];
  if (!isOriginal) items.push(["Ganzen Song abspielen", () => startPlay(song, true)]);
  items.push([isSimilar ? "Auf Deezer öffnen" : "TikTok öffnen", () => window.open(song.url, "_blank")]);
  items.push(["h", "Sammlung"]);
  items.push([song.favorite ? "Aus Favoriten entfernen" : "Zu Favoriten hinzufügen", async () => {
    await api("/favorite", { method: "POST",
      body: JSON.stringify({ saved_at: song.saved_at, value: !song.favorite }) });
    toast(song.favorite ? "Aus Favoriten entfernt" : "Zu Favoriten hinzugefügt");
    load();
  }]);
  if (!isOriginal) {
    items.push(["h", "Entdecken"]);
    items.push(["Ähnliche Songs", () => openSimilar(song)]);
    items.push(["Auf Spotify suchen", () =>
      window.open("https://open.spotify.com/search/" + encodeURIComponent(song.artist + " " + song.name), "_blank")]);
    items.push(["Auf YouTube Music suchen", () =>
      window.open("https://music.youtube.com/search?q=" + encodeURIComponent(song.artist + " " + song.name), "_blank")]);
  }
  items.push(["h", "Herunterladen"]);
  items.push([isOriginal ? "Sound als MP3" : "Song als MP3",
    () => window.open(`/download-mp3?id=${song.clip}&token=${token}`, "_blank")]);
  if (!isSimilar) items.push(["TikTok-Video als MP4",
    () => window.open(`/download-mp4?id=${song.clip}&token=${token}`, "_blank")]);
  items.push(["h", "Teilen"]);
  items.push(["Namen kopieren", () => {
    navigator.clipboard.writeText(`${song.artist} - ${song.name}`);
    toast("Kopiert");
  }]);
  items.push(["Löschen", async () => {
    if (playing && playing.clip === song.clip) stopPlay();
    await api("/delete", { method: "POST", body: song.saved_at });
    toast("Song gelöscht");
    load();
  }, "danger"]);
  anchorMenu(buildMenu(items), anchorEl);
}

$("profile").onclick = (ev) => {
  ev.stopPropagation();
  closeMenu();
  const items = [
    ["Name ändern", openRename],
    ["Passwort ändern", openPassword],
  ];
  if (isAdmin) items.push(["Konten verwalten", openAdmin]);
  items.push(["Abmelden", doLogout, "danger"]);
  anchorMenu(buildMenu(items), $("profile"));
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

async function openSimilar(seed) {
  const m = modal(`Ähnlich zu ${esc(seed.name)}`,
    `<div class="sub" style="padding:12px 0">Suche ähnliche Songs…</div>`);
  let tracks;
  try {
    tracks = (await (await api(`/similar?id=${seed.clip}`)).json()).similar;
  } catch (e) { m.el.querySelector(".body").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
  if (!tracks.length) { m.el.querySelector(".body").innerHTML = `<div class="sub">Nichts gefunden.</div>`; return; }
  m.el.querySelector(".body").innerHTML = tracks.map((t, i) => `<div class="mrow">
    <div class="cover" ${t.artwork ? `style="background-image:url('${esc(t.artwork)}')"` : ""} data-sp="${i}">
      <div class="ply">${IC.play}</div></div>
    <div class="meta"><b>${esc(t.track)}</b><span class="artist">${esc(t.artist)}</span></div>
    <button class="add" data-sa="${i}" title="Zu Favoriten hinzufügen">+</button>
  </div>`).join("");
  const syncIcons = () => m.el.querySelectorAll("[data-sp]").forEach(el =>
    el.querySelector(".ply").innerHTML =
      playing && playing.clip === "sim:" + el.dataset.sp ? IC.pause : IC.play);
  m.el.querySelectorAll("[data-sp]").forEach(el => el.onclick = () => {
    const t = tracks[el.dataset.sp];
    if (playing && playing.clip === "sim:" + el.dataset.sp) { stopPlay(); syncIcons(); return; }
    if (!t.preview) { toast("Keine Vorschau verfügbar"); return; }
    playSource("sim:" + el.dataset.sp, t.preview, t.track, t.artist, t.artwork);
    syncIcons();
  });
  m.el.querySelectorAll("[data-sa]").forEach(el => el.onclick = async () => {
    const t = tracks[el.dataset.sa];
    try {
      await api("/save-similar", { method: "POST", body: JSON.stringify(t) });
      el.textContent = "✓"; el.classList.add("done");
      toast("Zu Favoriten hinzugefügt");
      load();
    } catch (e) { toast("Speichern fehlgeschlagen"); }
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
  modal("Dein Geschmack",
    `<div class="chips" style="margin-bottom:4px">
       <span class="chip">${songs.length} Songs</span>
       <span class="chip">${week} diese Woche</span>
       <span class="chip">${favs} Favoriten</span>
     </div>` +
    (sorted.length
      ? sorted.map(([g, c]) => `<div><div style="display:flex;font-size:13px;margin-bottom:5px">
          <span style="flex:1">${esc(g)}</span><span style="color:var(--muted)">${c}</span></div>
          <div class="genrebar"><div style="width:${Math.round(c / max * 100)}%"></div></div></div>`).join("")
      : `<div class="sub">Noch keine Genre-Daten. Genres kommen automatisch bei jedem erkannten Song dazu.</div>`) +
    `<b style="font-size:14px;margin-top:6px">Top-Artists</b>` +
    tops.map(([a, c]) => `<div style="display:flex;font-size:13px">
      <span style="flex:1">${esc(a)}</span><span style="color:var(--muted)">${c === 1 ? "1 Song" : c + " Songs"}</span></div>`).join(""));
}

function openRename() {
  const m = modal("Name ändern",
    `<input type="text" id="rn" placeholder="Neuer Benutzername"><div class="err" id="rn-err"></div>
     <button class="btn-primary" id="rn-btn">Speichern</button>`);
  m.el.querySelector("#rn-btn").onclick = async () => {
    try {
      const r = await api("/rename", { method: "POST",
        body: JSON.stringify({ new: m.el.querySelector("#rn").value.trim() }) });
      userName = (await r.json()).user;
      localStorage.setItem("user", userName);
      toast("Umbenannt in @" + userName);
      m.close(); load();
    } catch (e) { m.el.querySelector("#rn-err").textContent = e.message; }
  };
}

function openPassword() {
  const m = modal("Passwort ändern",
    `<input type="password" id="p-old" placeholder="Aktuelles Passwort">
     <input type="password" id="p-new" placeholder="Neues Passwort">
     <input type="password" id="p-conf" placeholder="Neues Passwort bestätigen">
     <div class="err" id="p-err"></div><button class="btn-primary" id="p-btn">Ändern</button>`);
  m.el.querySelector("#p-btn").onclick = async () => {
    const oldPw = m.el.querySelector("#p-old").value,
          newPw = m.el.querySelector("#p-new").value,
          confPw = m.el.querySelector("#p-conf").value;
    if (newPw !== confPw) { m.el.querySelector("#p-err").textContent = "Passwörter stimmen nicht überein"; return; }
    try {
      await api("/change-password", { method: "POST", body: JSON.stringify({ old: oldPw, new: newPw }) });
      toast("Passwort geändert");
      m.close();
    } catch (e) { m.el.querySelector("#p-err").textContent = e.message; }
  };
}

async function openAdmin() {
  const m = modal("Konten verwalten", `<div class="sub">Lädt…</div>`);
  async function refresh() {
    let users;
    try { users = (await (await api("/admin/users")).json()).users; }
    catch (e) { m.el.querySelector(".body").innerHTML = `<div class="err">${esc(e.message)}</div>`; return; }
    m.el.querySelector(".body").innerHTML = users.map(u => `<div class="mrow">
      <div class="meta"><b style="color:${u.admin ? "var(--cyan)" : "inherit"}">@${esc(u.name)}${u.admin ? " · Admin" : ""}</b>
        <span class="artist">${u.songs} Songs</span></div>
      <button class="add" style="font-size:13px" data-rp2="${esc(u.name)}">Passwort</button>
      ${u.name !== userName ? `<button class="add" style="color:var(--pink);font-size:13px" data-du="${esc(u.name)}">Löschen</button>` : ""}
    </div>`).join("");
    m.el.querySelectorAll("[data-rp2]").forEach(el => el.onclick = () => {
      const target = el.dataset.rp2;
      const pm = modal("Passwort für @" + esc(target),
        `<input type="password" id="ap" placeholder="Neues Passwort">
         <input type="password" id="ap2" placeholder="Neues Passwort bestätigen">
         <div class="err" id="ap-err"></div>
         <button class="btn-primary" id="ap-btn">Zurücksetzen</button>`);
      pm.el.querySelector("#ap-btn").onclick = async () => {
        const p1 = pm.el.querySelector("#ap").value, p2 = pm.el.querySelector("#ap2").value;
        if (p1 !== p2) { pm.el.querySelector("#ap-err").textContent = "Passwörter stimmen nicht überein"; return; }
        try {
          await api("/admin/reset-password", { method: "POST",
            body: JSON.stringify({ user: target, new: p1 }) });
          toast("Zurückgesetzt"); pm.close();
        } catch (e) { pm.el.querySelector("#ap-err").textContent = e.message; }
      };
    });
    m.el.querySelectorAll("[data-du]").forEach(el => el.onclick = async () => {
      if (!confirm(`Konto @${el.dataset.du} wirklich löschen?`)) return;
      await api("/admin/delete-user", { method: "POST", body: el.dataset.du });
      toast("Konto gelöscht"); refresh();
    });
  }
  refresh();
}

/* ---------- Auth ---------- */
async function doAuth() {
  $("login-err").textContent = "";
  try {
    const r = await fetch(registerMode ? "/register" : "/login", {
      method: "POST",
      body: JSON.stringify({ user: $("user").value.trim(), pass: $("pass").value }),
    });
    if (!r.ok) throw new Error(await r.text());
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
$("toggle-register").onclick = () => {
  registerMode = !registerMode;
  $("login-mode").textContent = registerMode ? "Konto erstellen" : "Anmelden";
  $("login-btn").textContent = registerMode ? "Konto erstellen" : "Anmelden";
  $("toggle-register").textContent = registerMode ? "Schon ein Konto? Anmelden" : "Neu hier? Konto erstellen";
};
$("search").addEventListener("input", render);
document.addEventListener("visibilitychange", () => { if (!document.hidden && token) load(); });

if (token) { show(true); load(); } else { show(false); }
