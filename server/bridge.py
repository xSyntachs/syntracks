import asyncio
import hashlib
import json
import queue
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import unicodedata
from collections import defaultdict
from datetime import datetime, timezone

from shazamio import Shazam
from hmac import compare_digest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit
from urllib.request import urlopen

BASE = Path(__file__).resolve().parent
TOKEN = (BASE / "token.txt").read_text().strip()
USERS_FILE = BASE / "users.json"
INVITES_FILE = BASE / "invites.json"
SONGS_DIR = BASE / "songs"
SONGS_DIR.mkdir(exist_ok=True)
URL_RE = re.compile(r"https?://\S+")

JOBS = queue.Queue()
PENDING = defaultdict(dict)
FILE_LOCK = threading.Lock()
USERS_LOCK = threading.Lock()
CLIPS = BASE / "clips"
CLIPS.mkdir(exist_ok=True)


def load_users():
    return json.loads(USERS_FILE.read_text(encoding="utf-8")) if USERS_FILE.exists() else {}


def save_users(users):
    USERS_FILE.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")


def load_invites():
    return json.loads(INVITES_FILE.read_text(encoding="utf-8")) if INVITES_FILE.exists() else []


def save_invites(invites):
    INVITES_FILE.write_text(json.dumps(invites), encoding="utf-8")


def hash_pw(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 100_000).hex()


def user_for_token(token):
    if not token:
        return None
    for name, info in load_users().items():
        if compare_digest(token, info["token"]):
            return name
    return None


def songs_path(username):
    return SONGS_DIR / f"{username}.jsonl"


def clip_key(url):
    return hashlib.sha1(url.encode()).hexdigest()[:16]


def download_clip(url):
    """Lädt das volle TikTok-Audio (für Shazam) und schneidet daraus den 25-s-Preview-Clip."""
    preview = CLIPS / f"{clip_key(url)}.mp3"
    audio = CLIPS / f"audio_{clip_key(url)}.mp3"
    if not audio.exists():
        with tempfile.TemporaryDirectory() as tmp:
            run = subprocess.run(["yt-dlp", "-q", "-x", "--audio-format", "mp3",
                                  "-o", f"{tmp}/clip.%(ext)s", url],
                                 capture_output=True, text=True, timeout=120)
            if run.returncode != 0:
                raise RuntimeError("Audio-Download fehlgeschlagen")
            shutil.move(f"{tmp}/clip.mp3", audio)
    if not preview.exists():
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(audio),
                        "-t", "25", "-codec:a", "copy", str(preview)],
                       capture_output=True, timeout=60, check=True)
    return audio


def download_video(url):
    path = CLIPS / f"video_{clip_key(url)}.mp4"
    if path.exists():
        return path
    with tempfile.TemporaryDirectory() as tmp:
        run = subprocess.run(["yt-dlp", "-q", "-f", "mp4/best", "--no-playlist",
                              "-o", f"{tmp}/v.%(ext)s", url],
                             capture_output=True, text=True, timeout=180)
        out = next(Path(tmp).glob("v.*"), None)
        if run.returncode != 0 or not out:
            raise RuntimeError("Video-Download fehlgeschlagen")
        shutil.move(str(out), path)
    return path


def tiktok_url(text):
    for url in URL_RE.findall(text):
        host = urlsplit(url).hostname or ""
        if host == "tiktok.com" or host.endswith((".tiktok.com", ".tiktokv.com")):
            return url.rstrip(".,;)\"'")
    return None


# TikTok lokalisiert das "original sound"-Label nach Uploader-Sprache, ein echtes Flag liefert yt-dlp nicht
ORIGINAL_NON_LATIN = {"الصوت الأصلي", "оригинальный звук", "оригінальний звук", "オリジナル楽曲",
                      "오리지널 사운드", "原声", "原聲", "เสียงต้นฉบับ", "צליל מקורי",
                      "πρωτότυπος ήχος", "âm thanh gốc"}


def is_original_sound(track):
    low = (track or "").lower()
    return (not track or "origin" in low or "orijinal" in low or "suara asli" in low
            or "ses asli" in low or track in ORIGINAL_NON_LATIN)


EMOJI_RE = re.compile(
    "[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF⬀-⯿️‍]+")


def strip_emoji(text):
    return EMOJI_RE.sub("", text or "").strip() or None


def extract(url):
    run = subprocess.run(["yt-dlp", "--no-download", "-j", url],
                         capture_output=True, text=True, timeout=120)
    if run.returncode != 0:
        errors = run.stderr.strip().splitlines()
        raise RuntimeError(errors[-1] if errors else "yt-dlp ohne Fehlermeldung")
    meta = json.loads(run.stdout)
    track = strip_emoji(meta.get("track"))
    return {
        "saved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "track": track,
        "original": is_original_sound(track),
        "artist": strip_emoji(meta.get("artist") or meta.get("uploader")),
        "title": meta.get("title"),
        "url": meta.get("webpage_url") or url,
    }


def audio_duration(path):
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                            "-of", "csv=p=0", str(path)], capture_output=True, text=True, timeout=30)
    try:
        return float(probe.stdout.strip())
    except ValueError:
        return 0.0


def recognize(clip):
    # Das Lied liegt nicht immer am Anfang, also mehrere Stellen des Videos probieren.
    # loudnorm zieht leise hinterlegte Musik hoch, sonst matcht Shazam Voiceover-Videos nicht.
    duration = audio_duration(clip)
    offsets = [0.0] if duration <= 30 else sorted({0.0, duration * 0.35, duration * 0.7, max(0.0, duration - 22)})
    with tempfile.TemporaryDirectory() as tmp:
        # Slowed-Versionen sind auch pitch-verschoben, asetrate kehrt beides um (atempo allein reicht nicht)
        for rate in (1.0, 1.25, 1.4, 1.7, 0.8):
            for offset in offsets:
                candidate = f"{tmp}/r{rate}o{int(offset)}.mp3"
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", str(offset), "-t", "22",
                                "-i", str(clip),
                                "-af", f"asetrate=44100*{rate},aresample=44100,loudnorm", candidate],
                               capture_output=True, timeout=60)
                result = asyncio.run(Shazam().recognize(candidate))
                match = result.get("track")
                if not match:
                    continue
                # Stark verfremdetes Audio produziert Fantasie-Treffer, hohe Skew-Werte verraten die
                skew = (result.get("matches") or [{}])[0]
                if abs(skew.get("frequencyskew") or 0) > 0.08 or abs(skew.get("timeskew") or 0) > 0.3:
                    print(f"Shazam-Treffer verworfen (Skew {skew}): {match['subtitle']} - {match['title']}",
                          flush=True)
                    continue
                title = match["title"]
                hit = {"artist": match["subtitle"], "recognized": True}
                if rate > 1 and "slow" not in title.lower():
                    title += " (Slowed)"
                if rate > 1:
                    hit["slowed"] = True
                elif rate < 1:
                    hit["spedup"] = True
                hit["track"] = title
                # Shazam liefert Apple-Preview und Cover direkt mit, rettet Songs die iTunes-Suche nicht
                # findet. Bei Tempo-Varianten wäre die Preview aber die normale Version, dann lieber
                # der TikTok-Clip als Fallback.
                if rate == 1:
                    uris = [a.get("uri") for a in (match.get("hub") or {}).get("actions", [])]
                    hit["preview"] = next((u for u in uris if u and ".m4a" in u), None)
                hit["artwork"] = (match.get("images") or {}).get("coverart")
                return {k: v for k, v in hit.items() if v is not None}
    return None


def download_full(match):
    path = CLIPS / f"full_{clip_key(match['url'])}.mp3"
    if path.exists():
        return path
    name = song_name(match)
    # Erkannte Tracks existieren als echtes Release, da liefert YouTube die volle Länge der exakten
    # Version. Nur unerkannte und Caption-Sounds haben kein Release, da bleibt das TikTok-Audio.
    if match.get("recognized") or not match.get("original", is_original_sound(match["track"])):
        # Erkannt aus einer Tempo-Variante heißt, das Video hatte die Slowed/Sped-Up-Fassung,
        # dann soll die Volllänge auch die sein und nicht das normale Release
        variant = " slowed" if match.get("slowed") else " sped up" if match.get("spedup") else ""
        source = f'ytsearch1:"{name}" {match["artist"]}{variant}'
    else:
        source = match["url"]
    with tempfile.TemporaryDirectory() as tmp:
        run = subprocess.run(["yt-dlp", "-q", "-x", "--audio-format", "mp3", "--no-playlist",
                              "-o", f"{tmp}/full.%(ext)s", source],
                             capture_output=True, text=True, timeout=180)
        if run.returncode != 0:
            raise RuntimeError("Download fehlgeschlagen")
        shutil.move(f"{tmp}/full.mp3", path)
    return path


def caption_song(title):
    plain = unicodedata.normalize("NFKC", title or "")
    m = re.search(r"song(?:\s*name)?\s*[:\-–]\s*([^|#\n]+)", plain, re.I)
    return m.group(1).strip() or None if m else None


def song_name(song):
    if song.get("recognized") or song.get("from_caption") or not song.get("original", is_original_sound(song["track"])):
        return song["track"]
    return "Original-Sound"


def _norm(s):
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def itunes_track(artist, name):
    # Nur validierte Treffer, ein falscher Song ist schlechter als der Clip-Fallback
    plain = re.sub(r"\(.*?\)", "", name).strip() or name
    want_artist, want_track = _norm(artist), _norm(plain)
    for term in dict.fromkeys((f"{artist} {name}", f"{artist} {plain}")):
        try:
            with urlopen(f"https://itunes.apple.com/search?media=music&limit=5&term={quote(term)}",
                         timeout=10) as r:
                results = json.loads(r.read()).get("results", [])
        except Exception:
            continue
        for hit in results:
            got_artist, got_track = _norm(hit.get("artistName")), _norm(hit.get("trackName"))
            artist_ok = want_artist and (want_artist in got_artist or got_artist in want_artist)
            track_ok = want_track and (want_track in got_track or got_track in want_track)
            if hit.get("previewUrl") and artist_ok and track_ok:
                return {"preview": hit["previewUrl"],
                        "artwork": (hit.get("artworkUrl100") or "").replace("100x100", "300x300") or None,
                        "genre": hit.get("primaryGenreName")}
    return None


def deezer(path):
    with urlopen(f"https://api.deezer.com{path}", timeout=10) as r:
        return json.loads(r.read())


def similar_tracks(artist, name):
    plain = re.sub(r"\(.*?\)", "", name).strip() or name
    hits = (deezer(f"/search?q={quote(f'{artist} {plain}')}").get("data")
            or deezer(f"/search?q={quote(plain)}").get("data") or [])
    if not hits:
        return []
    tracks = []
    for related in deezer(f"/artist/{hits[0]['artist']['id']}/related").get("data", [])[:5]:
        for t in deezer(f"/artist/{related['id']}/top?limit=2").get("data", []):
            tracks.append({
                "track": t["title"],
                "artist": t["artist"]["name"],
                "preview": t.get("preview"),
                "artwork": (t.get("album") or {}).get("cover_medium"),
                "url": t.get("link"),
            })
    return tracks


REC_CACHE = {}


def recommendations(username, limit=10):
    all_songs = load_songs(username)
    favorites = [s for s in all_songs if s.get("favorite")]
    cache_key = (len(all_songs), len(favorites))
    cached = REC_CACHE.get(username)
    if cached and cached[0] == cache_key:
        return cached[1]
    # Favoriten zuerst und mit mehr verwandten Artists, sie wiegen schwerer als bloß Gespeichertes
    recent = [s for s in all_songs if not s.get("similar") and not s.get("favorite")][-20:]
    seeds = list(reversed(favorites)) + list(reversed(recent))
    have = {_norm(f"{s['artist']} {song_name(s)}") for s in all_songs}
    seen_artists, pool = set(), []
    for seed in seeds:
        name = song_name(seed)
        if name == "Original-Sound":
            continue
        plain = re.sub(r"\(.*?\)", "", name).strip() or name
        try:
            term = quote(f"{seed['artist']} {plain}")
            hits = deezer(f"/search?q={term}").get("data") or []
            if not hits:
                continue
            related_count = 4 if seed.get("favorite") else 2
            for rel in deezer(f"/artist/{hits[0]['artist']['id']}/related").get("data", [])[:related_count]:
                if rel["id"] in seen_artists:
                    continue
                seen_artists.add(rel["id"])
                for t in deezer(f"/artist/{rel['id']}/top?limit=2").get("data", []):
                    key = _norm(f"{t['artist']['name']} {t['title']}")
                    if key in have:
                        continue
                    have.add(key)
                    pool.append({
                        "track": t["title"],
                        "artist": t["artist"]["name"],
                        "preview": t.get("preview"),
                        "artwork": (t.get("album") or {}).get("cover_medium"),
                        "url": t.get("link"),
                    })
        except Exception:
            continue
        if len(pool) >= limit * 2:
            break
    result = pool[:limit]
    REC_CACHE[username] = (cache_key, result)
    return result


def load_songs(username):
    path = songs_path(username)
    if not path.exists():
        return []
    with FILE_LOCK:
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def append_song(username, entry):
    with FILE_LOCK, songs_path(username).open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def find_song(username, key):
    return next((s for s in load_songs(username) if clip_key(s["url"]) == key), None)


def songs_payload(username):
    songs = [{**s, "original": s.get("original", is_original_sound(s["track"])),
              "name": song_name(s), "clip": clip_key(s["url"])} for s in reversed(load_songs(username))]
    pending = [{"url": u, **info} for u, info in PENDING[username].items()]
    return {"user": username, "admin": bool(load_users().get(username, {}).get("admin")),
            "pending": pending, "songs": songs}


def worker():
    # ponytail: eine Queue, ein Worker, Jobs nur im RAM. Stirbt der Prozess mitten im Job, ist der Share weg.
    while True:
        username, url = JOBS.get()
        try:
            PENDING[username][url]["stage"] = "Video wird geladen"
            song = extract(url)
            if any(s["url"] == song["url"] for s in load_songs(username)):
                continue
            try:
                clip = download_clip(song["url"])
            except Exception:
                clip = None
            if clip:
                # Shazam läuft immer, TikToks eigene Song-Metadaten sind oft falsch oder nur der Sound-Titel
                PENDING[username][url]["stage"] = "Song wird erkannt"
                hit = recognize(clip)
                if hit:
                    song.update(hit)
                elif song["original"]:
                    caption = caption_song(song["title"])
                    if caption:
                        song.update({"track": caption, "from_caption": True})
            PENDING[username][url]["stage"] = "Wird gespeichert"
            # Gleicher Song aus einem anderen Video landet nicht nochmal in der Liste
            if song.get("track") and any(
                    _norm(s.get("track")) == _norm(song["track"])
                    and _norm(s.get("artist")) == _norm(song.get("artist"))
                    for s in load_songs(username)):
                print(f"Duplikat übersprungen: {username} {song.get('artist')} - {song['track']}", flush=True)
                continue
            if song_name(song) != "Original-Sound":
                extra = itunes_track(song["artist"], song_name(song)) or {}
                if song.get("slowed") or song.get("spedup"):
                    extra.pop("preview", None)
                song.update(extra)
            append_song(username, song)
        except Exception as e:
            print(f"Job fehlgeschlagen: {username} {url}: {e}", flush=True)
        finally:
            PENDING[username].pop(url, None)
            JOBS.task_done()


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, text, content_type="text/plain; charset=utf-8", no_store=False):
        body = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        if no_store:
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, data, content_type, filename=None):
        # Ohne Range-Support (206) kann kein Browser und kein MediaPlayer im Audio spulen
        total = len(data)
        start, end, code = 0, total - 1, 200
        rng = self.headers.get("Range", "")
        if rng.startswith("bytes="):
            first, _, last = rng[6:].split(",")[0].partition("-")
            try:
                if first:
                    start = int(first)
                    if last:
                        end = min(int(last), total - 1)
                else:
                    start = max(0, total - int(last))
                if 0 <= start <= end:
                    code = 206
                else:
                    start, end = 0, total - 1
            except ValueError:
                start, end = 0, total - 1
        chunk = data[start:end + 1]
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.send_header("Content-Length", str(len(chunk)))
        self.end_headers()
        self.wfile.write(chunk)

    def body(self):
        length = min(int(self.headers.get("Content-Length") or 0), 65536)
        return self.rfile.read(length).decode(errors="replace")

    def token_user(self):
        query = parse_qs(urlsplit(self.path).query)
        token = self.headers.get("X-Token") or (query.get("token") or [""])[0]
        return user_for_token(token)

    def query_id(self):
        return (parse_qs(urlsplit(self.path).query).get("id") or [""])[0]

    def do_POST(self):
        path = urlsplit(self.path).path
        if path in ("/register", "/login"):
            return self.handle_auth(path)
        username = self.token_user()
        if not username:
            return self.reply(401, "Nicht angemeldet")
        if path == "/add":
            url = tiktok_url(self.body())
            if not url:
                return self.reply(400, "Kein TikTok-Link im geteilten Text")
            if url not in PENDING[username]:
                PENDING[username][url] = {"queued_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                                          "stage": "Wartet"}
                JOBS.put((username, url))
            return self.reply(200, "Gespeichert, Song wird im Hintergrund erkannt")
        if path == "/save-similar":
            try:
                track = json.loads(self.body())
            except ValueError:
                return self.reply(400, "Kein JSON")
            append_song(username, {
                "saved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "track": track.get("track"), "original": False, "similar": True, "favorite": True,
                "artist": track.get("artist"), "title": None, "url": track.get("url") or "",
                "preview": track.get("preview"), "artwork": track.get("artwork"),
            })
            return self.reply(200, "In deine Favoriten gespeichert")
        if path == "/favorite":
            try:
                data = json.loads(self.body())
                saved_at, value = str(data["saved_at"]), bool(data["value"])
            except (ValueError, KeyError):
                return self.reply(400, "saved_at und value nötig")
            updated = [{**s, "favorite": value} if s["saved_at"] == saved_at else s
                       for s in load_songs(username)]
            with FILE_LOCK:
                songs_path(username).write_text(
                    "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in updated), encoding="utf-8")
            return self.reply(200, "Favorit aktualisiert")
        if path == "/delete":
            saved_at = self.body().strip()
            remaining = [s for s in load_songs(username) if s["saved_at"] != saved_at]
            with FILE_LOCK:
                songs_path(username).write_text(
                    "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in remaining), encoding="utf-8")
            return self.reply(200, "Eintrag entfernt")
        if path == "/rename":
            try:
                new = str(json.loads(self.body())["new"]).strip().lower()
            except (ValueError, KeyError):
                return self.reply(400, "Neuer Name fehlt")
            if not re.fullmatch(r"[a-z0-9_.]{3,20}", new):
                return self.reply(400, "Name 3-20 Zeichen, nur a-z 0-9 _ .")
            with USERS_LOCK:
                users = load_users()
                if new in users:
                    return self.reply(409, "Name vergeben")
                users[new] = users.pop(username)
                if songs_path(username).exists():
                    shutil.move(str(songs_path(username)), str(songs_path(new)))
                save_users(users)
            return self.reply(200, json.dumps({"user": new}), "application/json")
        if path == "/change-password":
            try:
                data = json.loads(self.body())
                old, new = str(data["old"]), str(data["new"])
            except (ValueError, KeyError):
                return self.reply(400, "Altes und neues Passwort fehlen")
            if len(new) < 6:
                return self.reply(400, "Neues Passwort mindestens 6 Zeichen")
            with USERS_LOCK:
                users = load_users()
                info = users[username]
                if not compare_digest(hash_pw(old, info["salt"]), info["hash"]):
                    return self.reply(401, "Altes Passwort falsch")
                info["salt"] = secrets.token_hex(16)
                info["hash"] = hash_pw(new, info["salt"])
                save_users(users)
            return self.reply(200, "Passwort geändert")
        if path == "/admin/delete-user":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            target = self.body().strip().lower()
            if target == username:
                return self.reply(400, "Eigenes Konto nicht löschbar")
            with USERS_LOCK:
                users = load_users()
                if target not in users:
                    return self.reply(404, "Unbekannter Nutzer")
                users.pop(target)
                songs_path(target).unlink(missing_ok=True)
                save_users(users)
            return self.reply(200, "Konto gelöscht")
        if path == "/admin/create-invite":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            key = secrets.token_urlsafe(6)
            with USERS_LOCK:
                save_invites(load_invites() + [key])
            return self.reply(200, json.dumps({
                "key": key, "link": f"https://tiktok.xsyntachs.de/?invite={key}",
            }), "application/json")
        if path == "/admin/delete-invite":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            key = self.body().strip()
            with USERS_LOCK:
                save_invites([k for k in load_invites() if k != key])
            return self.reply(200, "Einladung gelöscht")
        if path == "/admin/reset-password":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            try:
                data = json.loads(self.body())
                target, new = str(data["user"]).strip().lower(), str(data["new"])
            except (ValueError, KeyError):
                return self.reply(400, "Nutzer und neues Passwort fehlen")
            if len(new) < 6:
                return self.reply(400, "Passwort mindestens 6 Zeichen")
            with USERS_LOCK:
                users = load_users()
                if target not in users:
                    return self.reply(404, "Unbekannter Nutzer")
                users[target]["salt"] = secrets.token_hex(16)
                users[target]["hash"] = hash_pw(new, users[target]["salt"])
                save_users(users)
            return self.reply(200, "Passwort zurückgesetzt")
        return self.reply(404, "Nicht gefunden")

    def handle_auth(self, path):
        try:
            data = json.loads(self.body())
            name = str(data["user"]).strip().lower()
            password = str(data["pass"])
        except (ValueError, KeyError):
            return self.reply(400, "Benutzername und Passwort nötig")
        if not re.fullmatch(r"[a-z0-9_.]{3,20}", name):
            return self.reply(400, "Benutzername 3-20 Zeichen, nur a-z 0-9 _ .")
        if len(password) < 6:
            return self.reply(400, "Passwort mindestens 6 Zeichen")
        with USERS_LOCK:
            users = load_users()
            if path == "/register":
                if name in users:
                    return self.reply(409, "Benutzername vergeben")
                invite = str(data.get("invite", "")).strip()
                invites = load_invites()
                # Allererstes Konto (leere users.json) braucht keinen Key, sonst sperrt man sich aus
                if users:
                    if invite not in invites:
                        return self.reply(403, "Einladungs-Key ungültig. Frag einen Admin nach einer Einladung.")
                    invites.remove(invite)
                    save_invites(invites)
                salt = secrets.token_hex(16)
                token = secrets.token_hex(24)
                users[name] = {"salt": salt, "hash": hash_pw(password, salt), "token": token}
                # Erster registrierter Nutzer erbt die Alt-Songs aus der Zeit vor den Konten
                legacy = BASE / "songs.jsonl"
                if len(users) == 1 and legacy.exists():
                    shutil.move(str(legacy), str(songs_path(name)))
                save_users(users)
                return self.reply(200, json.dumps({"token": token, "user": name}), "application/json")
            info = users.get(name)
            if not info or not compare_digest(hash_pw(password, info["salt"]), info["hash"]):
                return self.reply(401, "Falscher Benutzername oder Passwort")
            return self.reply(200, json.dumps({"token": info["token"], "user": name}), "application/json")

    def do_GET(self):
        path = urlsplit(self.path).path
        # no_store, sonst kleben Browser und Cloudflare auf alten UI-Ständen fest
        if path == "/":
            return self.reply(200, (BASE / "index.html").read_text(encoding="utf-8"),
                              "text/html; charset=utf-8", no_store=True)
        if path == "/privacy":
            return self.reply(200, (BASE / "privacy.html").read_text(encoding="utf-8"),
                              "text/html; charset=utf-8")
        if path == "/styles.css":
            return self.reply(200, (BASE / "styles.css").read_text(encoding="utf-8"),
                              "text/css; charset=utf-8", no_store=True)
        if path == "/app.js":
            return self.reply(200, (BASE / "app.js").read_text(encoding="utf-8"),
                              "application/javascript; charset=utf-8", no_store=True)
        if path == "/app-version":
            return self.reply(200, (BASE / "version.json").read_text(encoding="utf-8"),
                              "application/json", no_store=True)
        if path == "/extension.zip" and (BASE / "extension.zip").exists():
            data = (BASE / "extension.zip").read_bytes()
            return self.send_file(data, "application/zip", "tiktok_songs_extension.zip")
        if path == "/app.apk" and (BASE / "app.apk").exists():
            data = (BASE / "app.apk").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        username = self.token_user()
        if not username:
            return self.reply(401, "Nicht angemeldet")
        if path == "/songs":
            return self.reply(200, json.dumps(songs_payload(username), ensure_ascii=False), "application/json")
        if path == "/admin/user-songs":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            target = (parse_qs(urlsplit(self.path).query).get("user") or [""])[0]
            if target not in load_users():
                return self.reply(404, "Unbekannter Nutzer")
            return self.reply(200, json.dumps(songs_payload(target), ensure_ascii=False), "application/json")
        if path == "/admin/user-recommendations":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            target = (parse_qs(urlsplit(self.path).query).get("user") or [""])[0]
            if target not in load_users():
                return self.reply(404, "Unbekannter Nutzer")
            try:
                tracks = recommendations(target)
            except Exception as e:
                return self.reply(502, f"Empfehlungen nicht verfügbar: {e}")
            return self.reply(200, json.dumps({"recommendations": tracks}, ensure_ascii=False),
                              "application/json")
        if path == "/admin/users":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            overview = [{"name": name, "admin": bool(info.get("admin")),
                         "songs": len(load_songs(name))} for name, info in sorted(load_users().items())]
            return self.reply(200, json.dumps({"users": overview}, ensure_ascii=False), "application/json")
        if path == "/admin/invites":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "Kein Admin")
            return self.reply(200, json.dumps({"invites": load_invites()}), "application/json")
        if path == "/recommendations":
            try:
                tracks = recommendations(username)
            except Exception as e:
                return self.reply(502, f"Empfehlungen nicht verfügbar: {e}")
            return self.reply(200, json.dumps({"recommendations": tracks}, ensure_ascii=False),
                              "application/json")
        if path == "/similar":
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "Nicht gefunden")
            try:
                tracks = similar_tracks(match["artist"], song_name(match))
            except Exception as e:
                return self.reply(502, f"Deezer nicht erreichbar: {e}")
            return self.reply(200, json.dumps({"similar": tracks}, ensure_ascii=False), "application/json")
        if path in ("/full", "/download-mp3"):
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "Nicht gefunden")
            try:
                full = download_full(match)
            except Exception as e:
                return self.reply(502, f"Song nicht verfügbar: {e}")
            fname = safe_name(f"{match['artist']} - {song_name(match)}.mp3") if path == "/download-mp3" else None
            return self.send_file(full.read_bytes(), "audio/mpeg", fname)
        if path == "/download-mp4":
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "Nicht gefunden")
            try:
                video = download_video(match["url"])
            except Exception as e:
                return self.reply(502, f"Video nicht verfügbar: {e}")
            return self.send_file(video.read_bytes(), "video/mp4",
                                  safe_name(f"{match['artist']} - {song_name(match)}.mp4"))
        if path == "/preview":
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "Nicht gefunden")
            name = song_name(match)
            preview = match.get("preview")
            if match.get("similar"):
                # Deezer-Preview-URLs laufen nach kurzer Zeit ab, pro Abruf frisch auflösen
                try:
                    term = quote(f"{match['artist']} {match['track']}")
                    hits = deezer(f"/search?q={term}").get("data") or []
                    preview = next((h.get("preview") for h in hits if h.get("preview")), None) or preview
                except Exception:
                    pass
            # Bei Tempo-Varianten wäre jede Katalog-Preview die normale Fassung, da bleibt der TikTok-Clip
            if match.get("slowed") or match.get("spedup"):
                preview = None
            elif not preview and name != "Original-Sound":
                preview = (itunes_track(match["artist"], name) or {}).get("preview")
            if preview:
                self.send_response(302)
                self.send_header("Location", preview)
                self.end_headers()
                return
            try:
                download_clip(match["url"])
            except Exception as e:
                return self.reply(502, f"Clip nicht verfügbar: {e}")
            return self.send_file((CLIPS / f"{clip_key(match['url'])}.mp3").read_bytes(), "audio/mpeg")
        return self.reply(404, "Nicht gefunden")


def safe_name(name):
    return re.sub(r'[<>:"/\\|?*]', "_", name)


if __name__ == "__main__":
    threading.Thread(target=worker, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", 8737), Handler).serve_forever()
