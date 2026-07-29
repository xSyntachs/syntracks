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
from collections import Counter, defaultdict
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
SONGS_DIR = BASE / "songs"
SONGS_DIR.mkdir(exist_ok=True)
URL_RE = re.compile(r"https?://\S+")

JOBS = queue.Queue()
PENDING = defaultdict(dict)
FILE_LOCK = threading.Lock()
USERS_LOCK = threading.Lock()
CLIPS = BASE / "clips"
CLIPS.mkdir(exist_ok=True)

I18N = json.loads((BASE / "i18n.json").read_text(encoding="utf-8"))
LANGS = ("en", "de", "es", "fr", "pt", "tr")

# Ein Job kostet einen yt-dlp-Download plus bis zu 16 Shazam-Anfragen. Mehr Worker
# holen die Downloads parallel, die Erkennung selbst bleibt durch SHAZAM_GATE seriell,
# weil shazamio bei schnellen Serien mit ServerDisconnectedError abbricht.
WORKERS = 3
SHAZAM_GATE = threading.Semaphore(1)

DAILY_SONG_LIMIT = 50
SIGNUPS_PER_HOUR = 5
SIGNUPS = defaultdict(list)
SIGNUP_LOCK = threading.Lock()

CACHE_MAX_AGE_DAYS = 14
CACHE_SWEEP_HOURS = 6


def t(key, lang):
    entry = I18N.get(key)
    return entry.get(lang) or entry["en"] if entry else key


def today():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def load_users():
    return json.loads(USERS_FILE.read_text(encoding="utf-8")) if USERS_FILE.exists() else {}


def save_users(users):
    USERS_FILE.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")


def hash_pw(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 100_000).hex()


# Jede Anmeldung ist eine eigene Sitzung, die älteste fällt hinten raus. Der Wert ist
# hoch, weil der Token im iPhone-Kurzbefehl fest verdrahtet ist und bei knapper Rotation
# still ungültig würde. Alte users.json-Einträge haben noch "token".
MAX_SESSIONS = 50


def user_tokens(info):
    return info.get("tokens") or ([info["token"]] if info.get("token") else [])


def user_for_token(token):
    if not token:
        return None
    for name, info in load_users().items():
        if any(compare_digest(token, t) for t in user_tokens(info)):
            return name
    return None


def songs_path(username):
    return SONGS_DIR / f"{username}.jsonl"


def claim_quota(username):
    with USERS_LOCK:
        users = load_users()
        info = users.get(username)
        if not info or info.get("admin"):
            return True
        quota = info.get("quota") or {}
        if quota.get("day") != today():
            quota = {"day": today(), "count": 0}
        if quota["count"] >= DAILY_SONG_LIMIT:
            return False
        quota["count"] += 1
        info["quota"] = quota
        save_users(users)
        return True


def may_download(username):
    info = load_users().get(username, {})
    return bool(info.get("admin") or info.get("downloads"))


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
    # Fantasie-Treffer kommen vor (echtes Beispiel: Ultra-Slowed-Video, erster Kandidat lieferte einen
    # völlig fremden Song mit Skew 0.048). Deshalb kein First-Hit, sondern Voting: derselbe Song muss
    # aus einer zweiten Stelle/Rate bestätigt werden. Verglichen wird NUR der Titel ohne
    # Klammer-Zusätze, weil dieselbe Nummer je Tempo-Stufe auf verschiedene Katalog-Einträge
    # matcht (Original vs. Sped-Up/Slowed-Bootlegs anderer "Artists"). Skew taugt nicht als
    # Einzeltreffer-Kriterium, echte und falsche liegen im selben Bereich, darum bekommt ein
    # Einzeltreffer eine gezielte Zweitprobe.
    def base_key(match):
        return _norm(re.sub(r"[(\[].*?[)\]]", "", match["title"]))

    candidates = []
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
                skews = (result.get("matches") or [{}])[0]
                if abs(skews.get("frequencyskew") or 0) > 0.08 or abs(skews.get("timeskew") or 0) > 0.3:
                    print(f"Shazam-Treffer verworfen (Skew {skews}): {match['subtitle']} - {match['title']}",
                          flush=True)
                    continue
                candidates.append((abs(skews.get("frequencyskew") or 0), rate, offset, match))
                # Nur verschiedene Tempo-Stufen zählen als unabhängige Stimmen. Derselbe
                # Fantasie-Treffer wiederholt sich an benachbarten Stellen desselben Audios
                # (echter Fall: "the reaper is so tuff" zweimal aus demselben verfremdeten Edit).
                if len({r for _, r, _, m in candidates if base_key(m) == base_key(match)}) >= 2:
                    break
            else:
                continue
            break
    if not candidates:
        return None
    winner_key, _ = Counter(base_key(m) for *_, m in candidates).most_common(1)[0]
    votes = len({r for _, r, _, m in candidates if base_key(m) == winner_key})
    skew, rate, offset, match = min((c for c in candidates if base_key(c[3]) == winner_key), key=lambda c: c[0])
    if votes < 2:
        # Zweitprobe möglichst weit weg vom Fundort (bei kurzen Videos mit leicht anderem
        # Tempo), ein echter Song läuft durchs ganze Video, ein Fantasie-Treffer zerfällt
        if duration > 34:
            probe_offset = 0.0 if offset > duration / 2 else max(0.0, duration - 22)
            probe_rate = rate
        else:
            probe_offset, probe_rate = offset, rate * 1.06
        with tempfile.TemporaryDirectory() as tmp:
            probe = f"{tmp}/probe.mp3"
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", str(probe_offset), "-t", "22",
                            "-i", str(clip),
                            "-af", f"asetrate=44100*{probe_rate},aresample=44100,loudnorm", probe],
                           capture_output=True, timeout=60)
            confirm = asyncio.run(Shazam().recognize(probe)).get("track")
        if not confirm or base_key(confirm) != winner_key:
            print(f"Einzeltreffer hielt der Zweitprobe nicht stand: {match['subtitle']} - {match['title']}",
                  flush=True)
            return None
    title = match["title"]
    hit = {"artist": match["subtitle"], "recognized": True}
    if rate > 1 and not re.search(r"slow|sped", title.lower()):
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


def bump_song(username, predicate):
    """Schiebt einen vorhandenen Eintrag mit frischem Zeitstempel nach oben, statt ihn zu duplizieren."""
    # load_songs nimmt selbst FILE_LOCK (nicht reentrant), also lesen wie /delete und /favorite
    # außerhalb des Locks und nur das Schreiben sperren
    songs = load_songs(username)
    hit = next((s for s in songs if predicate(s)), None)
    if not hit:
        return False
    songs.remove(hit)
    hit["saved_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    songs.append(hit)
    with FILE_LOCK:
        songs_path(username).write_text(
            "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in songs), encoding="utf-8")
    return True


def songs_payload(username):
    songs = [{**s, "original": s.get("original", is_original_sound(s["track"])),
              "name": song_name(s), "clip": clip_key(s["url"])} for s in reversed(load_songs(username))]
    pending = [{"url": u, **info} for u, info in PENDING[username].items()]
    info = load_users().get(username, {})
    return {"user": username, "admin": bool(info.get("admin")), "downloads": may_download(username),
            "quota": {"used": (info.get("quota") or {}).get("count", 0) if (info.get("quota") or {}).get("day") == today() else 0,
                      "limit": DAILY_SONG_LIMIT},
            "pending": pending, "songs": songs}


def worker():
    # ponytail: eine Queue, ein Worker, Jobs nur im RAM. Stirbt der Prozess mitten im Job, ist der Share weg.
    while True:
        username, url = JOBS.get()
        try:
            PENDING[username][url]["stage"] = "loading_video"
            song = extract(url)
            # Schon gespeichertes Video erneut geteilt -> Eintrag rutscht nach oben
            if bump_song(username, lambda s: s["url"] == song["url"]):
                continue
            try:
                clip = download_clip(song["url"])
            except Exception:
                clip = None
            if clip:
                # Shazam läuft immer, TikToks eigene Song-Metadaten sind oft falsch oder nur der Sound-Titel
                PENDING[username][url]["stage"] = "identifying"
                with SHAZAM_GATE:
                    hit = recognize(clip)
                if hit:
                    song.update(hit)
                elif song["original"]:
                    caption = caption_song(song["title"])
                    if caption:
                        song.update({"track": caption, "from_caption": True})
            PENDING[username][url]["stage"] = "saving"
            # Gleicher Song aus einem anderen Video landet nicht nochmal in der Liste,
            # der vorhandene Eintrag rutscht stattdessen nach oben
            if song.get("track") and bump_song(
                    username,
                    lambda s: _norm(s.get("track")) == _norm(song["track"])
                    and _norm(s.get("artist")) == _norm(song.get("artist"))):
                print(f"Duplikat nach oben geschoben: {username} {song.get('artist')} - {song['track']}",
                      flush=True)
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
    def lang(self):
        wanted = (parse_qs(urlsplit(self.path).query).get("lang") or [""])[0][:2].lower()
        if wanted in LANGS:
            return wanted
        for part in self.headers.get("Accept-Language", "").split(","):
            code = part.split(";")[0].strip()[:2].lower()
            if code in LANGS:
                return code
        return "en"

    def client_ip(self):
        # Hinter Cloudflare und dem Nginx Proxy Manager wäre client_address immer der Proxy,
        # damit würde ein Limit alle Nutzer zugleich treffen
        return (self.headers.get("CF-Connecting-IP")
                or self.headers.get("X-Forwarded-For", "").split(",")[0].strip()
                or self.client_address[0])

    def claim_signup(self):
        now = datetime.now(timezone.utc).timestamp()
        with SIGNUP_LOCK:
            recent = [s for s in SIGNUPS[self.client_ip()] if now - s < 3600]
            SIGNUPS[self.client_ip()] = recent
            if len(recent) >= SIGNUPS_PER_HOUR:
                return False
            recent.append(now)
            return True

    def reply(self, code, text, content_type="text/plain; charset=utf-8", no_store=False):
        # Bekannte Schlüssel werden übersetzt, JSON-Nutzlasten gehen unverändert durch
        body = (t(text, self.lang()) if text in I18N else text).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        if no_store:
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, data, content_type, filename=None, no_store=False):
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
        if no_store:
            self.send_header("Cache-Control", "no-store, must-revalidate")
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
            return self.reply(401, "not_signed_in")
        if path == "/add":
            url = tiktok_url(self.body())
            if not url:
                return self.reply(400, "no_tiktok_link")
            if not claim_quota(username):
                return self.reply(429, "daily_limit_reached")
            if url not in PENDING[username]:
                PENDING[username][url] = {"queued_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                                          "stage": "waiting"}
                JOBS.put((username, url))
            return self.reply(200, "queued")
        if path == "/save-similar":
            try:
                track = json.loads(self.body())
            except ValueError:
                return self.reply(400, "no_json")
            append_song(username, {
                "saved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "track": track.get("track"), "original": False, "similar": True, "favorite": True,
                "artist": track.get("artist"), "title": None, "url": track.get("url") or "",
                "preview": track.get("preview"), "artwork": track.get("artwork"),
            })
            return self.reply(200, "saved_to_favorites")
        if path == "/favorite":
            try:
                data = json.loads(self.body())
                saved_at, value = str(data["saved_at"]), bool(data["value"])
            except (ValueError, KeyError):
                return self.reply(400, "favorite_fields_missing")
            updated = [{**s, "favorite": value} if s["saved_at"] == saved_at else s
                       for s in load_songs(username)]
            with FILE_LOCK:
                songs_path(username).write_text(
                    "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in updated), encoding="utf-8")
            return self.reply(200, "favorite_updated")
        if path == "/delete":
            saved_at = self.body().strip()
            remaining = [s for s in load_songs(username) if s["saved_at"] != saved_at]
            with FILE_LOCK:
                songs_path(username).write_text(
                    "".join(json.dumps(s, ensure_ascii=False) + "\n" for s in remaining), encoding="utf-8")
            return self.reply(200, "entry_removed")
        if path == "/rename":
            try:
                new = str(json.loads(self.body())["new"]).strip().lower()
            except (ValueError, KeyError):
                return self.reply(400, "new_name_missing")
            if not re.fullmatch(r"[a-z0-9_.]{3,20}", new):
                return self.reply(400, "username_rules")
            with USERS_LOCK:
                users = load_users()
                if new in users:
                    return self.reply(409, "name_taken")
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
                return self.reply(400, "passwords_missing")
            if len(new) < 6:
                return self.reply(400, "password_too_short")
            with USERS_LOCK:
                users = load_users()
                info = users[username]
                if not compare_digest(hash_pw(old, info["salt"]), info["hash"]):
                    return self.reply(401, "old_password_wrong")
                info["salt"] = secrets.token_hex(16)
                info["hash"] = hash_pw(new, info["salt"])
                # Passwortwechsel wirft alle anderen Sitzungen raus, nur die eigene bleibt
                own = self.headers.get("X-Token") or (parse_qs(urlsplit(self.path).query).get("token") or [""])[0]
                info["tokens"] = [own]
                info.pop("token", None)
                save_users(users)
            return self.reply(200, "password_changed")
        if path == "/admin/delete-user":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            target = self.body().strip().lower()
            if target == username:
                return self.reply(400, "cannot_delete_self")
            with USERS_LOCK:
                users = load_users()
                if target not in users:
                    return self.reply(404, "unknown_user")
                users.pop(target)
                songs_path(target).unlink(missing_ok=True)
                save_users(users)
            return self.reply(200, "account_deleted")
        if path == "/admin/set-downloads":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            try:
                data = json.loads(self.body())
                target, value = str(data["user"]).strip().lower(), bool(data["value"])
            except (ValueError, KeyError):
                return self.reply(400, "no_json")
            with USERS_LOCK:
                users = load_users()
                if target not in users:
                    return self.reply(404, "unknown_user")
                users[target]["downloads"] = value
                save_users(users)
            return self.reply(200, json.dumps({"user": target, "downloads": value}), "application/json")
        if path == "/admin/reset-password":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            try:
                data = json.loads(self.body())
                target, new = str(data["user"]).strip().lower(), str(data["new"])
            except (ValueError, KeyError):
                return self.reply(400, "user_and_password_missing")
            if len(new) < 6:
                return self.reply(400, "password_too_short")
            with USERS_LOCK:
                users = load_users()
                if target not in users:
                    return self.reply(404, "unknown_user")
                users[target]["salt"] = secrets.token_hex(16)
                users[target]["hash"] = hash_pw(new, users[target]["salt"])
                users[target]["tokens"] = []
                users[target].pop("token", None)
                save_users(users)
            return self.reply(200, "password_reset")
        return self.reply(404, "not_found")

    def handle_auth(self, path):
        try:
            data = json.loads(self.body())
            name = str(data["user"]).strip().lower()
            password = str(data["pass"])
        except (ValueError, KeyError):
            return self.reply(400, "credentials_missing")
        if not re.fullmatch(r"[a-z0-9_.]{3,20}", name):
            return self.reply(400, "username_rules")
        if len(password) < 6:
            return self.reply(400, "password_too_short")
        with USERS_LOCK:
            users = load_users()
            if path == "/register":
                if name in users:
                    return self.reply(409, "username_taken")
                if not self.claim_signup():
                    return self.reply(429, "too_many_accounts")
                salt = secrets.token_hex(16)
                token = secrets.token_hex(24)
                users[name] = {"salt": salt, "hash": hash_pw(password, salt), "tokens": [token]}
                # Erster registrierter Nutzer erbt die Alt-Songs aus der Zeit vor den Konten
                legacy = BASE / "songs.jsonl"
                if len(users) == 1 and legacy.exists():
                    shutil.move(str(legacy), str(songs_path(name)))
                save_users(users)
                return self.reply(200, json.dumps({"token": token, "user": name}), "application/json")
            info = users.get(name)
            if not info or not compare_digest(hash_pw(password, info["salt"]), info["hash"]):
                return self.reply(401, "login_failed")
            token = secrets.token_hex(24)
            info["tokens"] = (user_tokens(info) + [token])[-MAX_SESSIONS:]
            info.pop("token", None)
            save_users(users)
            return self.reply(200, json.dumps({"token": token, "user": name}), "application/json")

    def do_GET(self):
        path = urlsplit(self.path).path
        # no_store, sonst kleben Browser und Cloudflare auf alten UI-Ständen fest
        if path == "/":
            return self.reply(200, (BASE / "index.html").read_text(encoding="utf-8"),
                              "text/html; charset=utf-8", no_store=True)
        if path in ("/favicon.png", "/logo.png"):
            return self.send_file((BASE / path.lstrip("/")).read_bytes(), "image/png")
        if path == "/privacy":
            return self.reply(200, (BASE / "privacy.html").read_text(encoding="utf-8"),
                              "text/html; charset=utf-8")
        if path == "/ios":
            return self.reply(200, (BASE / "ios.html").read_text(encoding="utf-8"),
                              "text/html; charset=utf-8", no_store=True)
        if path == "/i18n.json":
            return self.reply(200, json.dumps(I18N, ensure_ascii=False),
                              "application/json; charset=utf-8", no_store=True)
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
            return self.send_file(data, "application/zip", "syntracks_extension.zip", no_store=True)
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
            return self.reply(401, "not_signed_in")
        if path == "/songs":
            return self.reply(200, json.dumps(songs_payload(username), ensure_ascii=False), "application/json")
        if path == "/admin/user-songs":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            target = (parse_qs(urlsplit(self.path).query).get("user") or [""])[0]
            if target not in load_users():
                return self.reply(404, "unknown_user")
            return self.reply(200, json.dumps(songs_payload(target), ensure_ascii=False), "application/json")
        if path == "/admin/user-recommendations":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            target = (parse_qs(urlsplit(self.path).query).get("user") or [""])[0]
            if target not in load_users():
                return self.reply(404, "unknown_user")
            try:
                tracks = recommendations(target)
            except Exception as e:
                return self.reply(502, f"{t('recommendations_unavailable', self.lang())}: {e}")
            return self.reply(200, json.dumps({"recommendations": tracks}, ensure_ascii=False),
                              "application/json")
        if path == "/admin/users":
            if not load_users().get(username, {}).get("admin"):
                return self.reply(403, "not_admin")
            overview = [{"name": name, "admin": bool(info.get("admin")),
                         "downloads": bool(info.get("admin") or info.get("downloads")),
                         "songs": len(load_songs(name))} for name, info in sorted(load_users().items())]
            return self.reply(200, json.dumps({"users": overview}, ensure_ascii=False), "application/json")
        if path == "/recommendations":
            try:
                tracks = recommendations(username)
            except Exception as e:
                return self.reply(502, f"{t('recommendations_unavailable', self.lang())}: {e}")
            return self.reply(200, json.dumps({"recommendations": tracks}, ensure_ascii=False),
                              "application/json")
        if path == "/similar":
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "not_found")
            try:
                tracks = similar_tracks(match["artist"], song_name(match))
            except Exception as e:
                return self.reply(502, f"{t('deezer_unreachable', self.lang())}: {e}")
            return self.reply(200, json.dumps({"similar": tracks}, ensure_ascii=False), "application/json")
        if path in ("/full", "/download-mp3"):
            if not may_download(username):
                return self.reply(403, "downloads_locked")
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "not_found")
            try:
                full = download_full(match)
            except Exception as e:
                return self.reply(502, f"{t('song_unavailable', self.lang())}: {e}")
            fname = safe_name(f"{match['artist']} - {song_name(match)}.mp3") if path == "/download-mp3" else None
            return self.send_file(full.read_bytes(), "audio/mpeg", fname)
        if path == "/download-mp4":
            if not may_download(username):
                return self.reply(403, "downloads_locked")
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "not_found")
            try:
                video = download_video(match["url"])
            except Exception as e:
                return self.reply(502, f"{t('video_unavailable', self.lang())}: {e}")
            return self.send_file(video.read_bytes(), "video/mp4",
                                  safe_name(f"{match['artist']} - {song_name(match)}.mp4"))
        if path == "/preview":
            match = find_song(username, self.query_id())
            if not match:
                return self.reply(404, "not_found")
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
                return self.reply(502, f"{t('clip_unavailable', self.lang())}: {e}")
            return self.send_file((CLIPS / f"{clip_key(match['url'])}.mp3").read_bytes(), "audio/mpeg")
        return self.reply(404, "not_found")


def safe_name(name):
    return re.sub(r'[<>:"/\\|?*]', "_", name)


def sweep_cache():
    # Ohne das wächst clips/ mit jedem geteilten Video unbegrenzt weiter
    while True:
        cutoff = datetime.now(timezone.utc).timestamp() - CACHE_MAX_AGE_DAYS * 86400
        for path in CLIPS.iterdir():
            if path.is_file() and path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)
        threading.Event().wait(CACHE_SWEEP_HOURS * 3600)


if __name__ == "__main__":
    for _ in range(WORKERS):
        threading.Thread(target=worker, daemon=True).start()
    threading.Thread(target=sweep_cache, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", 8737), Handler).serve_forever()
