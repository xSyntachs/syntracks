# Syntracks

Finds the song behind a TikTok video and keeps it. Share a video, the server identifies the track (Shazam fingerprint with tempo correction for slowed versions, iTunes lookup, caption parsing) and your collection is available everywhere, as an Android app, in the browser and through a Chrome extension. Free accounts, open registration.

## Features

- Song identification from shared TikTok links, asynchronous with progress stages
- Recognises slowed and sped up versions through pitch corrected resampling
- Album art and 30 second studio previews from iTunes and Shazam
- Discover similar tracks through Deezer and keep them with one tap
- Genre statistics, search, source filters
- Six interface languages, English, German, Spanish, French, Portuguese and Turkish
- Light and dark theme

## Layout

| Folder | Contents |
|---|---|
| `app/` | Android app, Kotlin and Jetpack Compose, no third party dependencies beyond Compose |
| `server/` | Bridge server, one Python file (stdlib plus shazamio and yt-dlp), the web interface and the systemd unit |
| `setup/` | Handout package with the guide, the built APK and the Chrome extension |

## Setup

See [setup/GUIDE.md](setup/GUIDE.md) for the app, the browser and the Chrome extension.

Running the server, `server/bridge.py` needs Python 3.11 or newer, a virtual environment with `shazamio`, plus `yt-dlp` and `ffmpeg` on the PATH. It runs as a systemd service behind a TLS reverse proxy. Accounts live in `users.json`, songs are stored per user as JSONL.

All interface text comes from `server/i18n.json`. Adding a language means adding one code to every entry there and to the language list in `server/app.js`.

## Development

The release keystore is deliberately not in the repository.

## Licence

The code is public but protected. Using the finished app is allowed, copying, reusing or self hosting the code is not. Details in [LICENSE](LICENSE).
