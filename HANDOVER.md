# Session-Prompt Syntracks

Der Block unten ist zum Kopieren in eine neue Session.

---

Projekt Syntracks in `C:\Users\lrumk\tiktok-song-app`. Findet den Song aus geteilten
TikToks. Server erkennt per yt-dlp, Shazam und iTunes. Vier Oberflächen: Web, Android,
Chrome-Erweiterung, iPhone-Kurzbefehl.

**Aufgabe.** `app/src/main/java/de/xsyntachs/tiktoksongs/MainActivity.kt` hat 1700 Zeilen und
enthält alles. Teile sie auf, ohne Verhalten zu ändern. Vorschlag: `Theme.kt` für Farben,
Formen und `AuroraBackground`, `SongList.kt` für Liste, `Pager`, `SongCard` und `PendingCard`,
`Overlays.kt` für Dialoge und `OverlayFrame`, `Admin.kt` für die Kontenverwaltung. Nach jedem
Schnitt bauen und auf dem Handy prüfen, nicht am Ende alles auf einmal.

**Infrastruktur.**
- Server `root@ssh.xsyntachs.de`, Code in `/opt/tiktok-bridge/`, systemd-Dienst
  `tiktok-bridge`, lauscht auf 127.0.0.1:8737. Deploy von Hand per scp, danach
  `chown scp:scp` und `systemctl restart tiktok-bridge`. SSH-Key `~/.ssh/id_ed25519`.
- Öffentlich als **https://syntracks.app** über Cloudflare-Proxy auf 37.44.215.95, Host im
  Nginx Proxy Manager unter proxy.xsyntachs.de. Die alte Adresse syntracks.xsyntachs.de läuft
  weiter und darf nicht abgeschaltet werden, in jedem iPhone-Kurzbefehl steht sie fest drin.
- Repo `github.com/xSyntachs/syntracks`, Branch main. Die Pipeline baut bei Änderungen an
  `app/**` oder `setup/chrome_extension/**`, versionCode ist `200 + Laufnummer`.
  Server-Deploys laufen weiter von Hand.
- Zugangsdaten für Google und Discord in `/opt/tiktok-bridge/oauth.json`, chmod 600, nicht im
  Repo. Der Discord-Schlüssel muss noch rotiert werden.

**Stand.** Registrierung ist offen, das Invite-System ist entfernt. Tageslimit 50 Songs pro
Konto, fünf Neuanmeldungen pro Stunde und IP, drei Worker mit serieller Shazam-Drossel, Cache
wird nach 14 Tagen geräumt. Alle Texte von Server und Web kommen aus `server/i18n.json`, 197
Einträge in Englisch, Deutsch, Spanisch, Französisch, Portugiesisch, Türkisch. Web und iPhone
tragen das Mixtape-Design, gelber Kopf `#E9E64A`, Grund `#121212`, Zeilen `#1E1E1B`, Linien
`#2A2A26`, flache Flächen, 5px Ecken, Bricolage Grotesque und Space Grotesk, hell und dunkel.
Anmeldung über Google und Discord läuft. Volllängen-Downloads antworten mit 403, solange das
Konto kein `downloads`-Flag hat. Im Code stehen keine Kommentare, das ist so gewollt.

Die Android-App hat bereits neues Icon, gelben Kopf, flache Farben, Seitenblättern statt
Scrollen und flache Badges. Sie ist noch komplett deutsch, während Web und iPhone sechs
Sprachen können. Die Chrome-Erweiterung trägt noch das alte Design.

**Fallstricke, alle heute real aufgetreten.**
- Gradle liefert aus dem Cache und meldet trotzdem Erfolg. Bei Zweifel `--rerun-tasks`.
- `adb install` scheitert still, wenn der versionCode unter dem installierten liegt. Auf dem
  Handy liegt gerade Testversion 1005, für lokale Tests also höher zählen.
- Cloudflare erfindet vier Stunden Cache, wenn eine Antwort kein `no-store` trägt.
- Bei Änderungen an mehreren Stellen einer Kotlin-Datei nicht mit Skripten arbeiten, das hat
  zweimal die Klammerbilanz gerissen. Direkt editieren.
- Das Handy hängt per WLAN-ADB auf `192.168.0.111:39773`, gekoppelt. Screenshots mit
  `adb -s 192.168.0.111:39773 exec-out screencap -p`.

Bauen: `VERSION_CODE=1010 VERSION_NAME="6.5-t9" "C:\Users\lrumk\.gradle\wrapper\dists\gradle-8.14.1-bin\baw1sv0jfoi8rxs14qo3h49cs\gradle-8.14.1\bin\gradle.bat" assembleRelease`

**Danach offen.** App auf sechs Sprachen, Erweiterung aufs neue Design, TikTok-Kanal nach
`marketing/tiktok.md`, Discord-Secret rotieren.
