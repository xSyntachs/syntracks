# TikTok Songs

Merkt sich die Musik aus TikTok-Videos. Video teilen, der Server erkennt den Song (Shazam-Fingerprint mit Tempo-Korrektur für Slowed-Versionen, iTunes-Abgleich, Caption-Parsing) und die Sammlung ist überall verfügbar, als Android-App, im Browser und per Chrome-Erweiterung. Multi-User mit eigenen Konten.

## Features

- Song-Erkennung aus geteilten TikTok-Links, asynchron mit Fortschritts-Stufen
- Erkennt auch Slowed/Sped-Up-Versionen (asetrate-Varianten gegen Pitch-Verschiebung)
- Album-Cover und 30-s-Studio-Previews von iTunes/Shazam, Volllänge über YouTube
- Ähnliche Songs entdecken (Deezer) und mit einem Tap in die eigene Liste übernehmen
- Genre-Statistik ("Dein Geschmack"), Suche, Quellen-Filter
- MP3- und MP4-Download
- Wochen-Rückblick als Benachrichtigung (App)
- Multi-User mit Registrierung, Admin-Konten-Verwaltung

## Aufbau

| Ordner | Inhalt |
|---|---|
| `app/` | Android-App, Kotlin + Jetpack Compose, keine Fremd-Dependencies außer Compose |
| `server/` | Bridge-Server, eine Python-Datei (Stdlib + shazamio + yt-dlp), plus Web-UI (`index.html`) und systemd-Unit |
| `setup/` | Weitergabe-Paket: Anleitung, fertige APK, Chrome-Erweiterung |

## Setup

Siehe [setup/ANLEITUNG.md](setup/ANLEITUNG.md) für App, Browser und Chrome-Erweiterung.

Server-Betrieb: `server/bridge.py` braucht Python 3.11+, ein venv mit `shazamio`, dazu `yt-dlp` und `ffmpeg` im PATH. Läuft als systemd-Dienst hinter einem Reverse-Proxy mit TLS. Nutzerkonten liegen in `users.json`, Songs pro Nutzer als JSONL.

## Entwicklung

Build-Kommandos, Release-Pflichten und Stolperfallen stehen in [CLAUDE.md](CLAUDE.md). Der Release-Keystore ist bewusst nicht im Repo.

## Lizenz

Der Code ist öffentlich einsehbar, aber geschützt. Nutzung der fertigen App ist erlaubt, Kopieren, Weiterverwenden oder Selbst-Hosten des Codes nicht. Details in [LICENSE](LICENSE).
