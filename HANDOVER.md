# Session prompt for Syntracks

Copy the block below into a new session.

---

Project Syntracks in `C:\Users\lrumk\tiktok-song-app`. It finds the song behind a shared
TikTok. The server identifies it with yt-dlp, Shazam and iTunes. Four surfaces: web,
Android, Chrome extension, iPhone shortcut.

**Infrastructure.**
- Server `root@ssh.xsyntachs.de`, code in `/opt/tiktok-bridge/`, systemd unit
  `tiktok-bridge`, listening on 127.0.0.1:8737. Deploy by hand over scp, then
  `chown scp:scp` and `systemctl restart tiktok-bridge`. SSH key `~/.ssh/id_ed25519`.
- Public as **https://syntracks.app** behind the Cloudflare proxy on 37.44.215.95, host
  entry in the Nginx Proxy Manager at proxy.xsyntachs.de. The old address
  syntracks.xsyntachs.de stays alive and must not be switched off, every iPhone shortcut
  has it baked in.
- Repo `github.com/xSyntachs/syntracks`, branch main. The pipeline builds on changes under
  `app/**` or `setup/chrome_extension/**`, versionCode is `200 + run number`. Server
  deploys stay manual.
- Google and Discord credentials live in `/opt/tiktok-bridge/oauth.json`, chmod 600, never
  in the repo. The Discord secret still needs rotating.

**State.** Registration is open, the invite system is gone. Fifty songs per account per
day, five signups per hour and IP, three workers behind a serial Shazam gate, the cache is
swept after 14 days. Every server and web string comes from `server/i18n.json`, six
languages (en, de, es, fr, pt, tr). All surfaces carry the mixtape design, yellow masthead
`#E9E64A`, base `#121212`, rows `#1E1E1B`, lines `#2A2A26`, flat surfaces, 5px corners,
Bricolage Grotesque and Space Grotesk, light and dark. Google and Discord sign-in work.
Full-length downloads answer 403 unless the account carries the `downloads` flag. The code
holds no comments, that is deliberate.

The Android app is split across `MainActivity.kt`, `Theme.kt`, `SongList.kt`, `Overlays.kt`
and `Admin.kt`. Pages turn by swiping, the numbered pager stays in sync. The Chrome
extension carries the mixtape design and reads the video id from the most visible item, so
it works inside the feed where the address bar holds no video address.

**Traps, all of them hit for real.**
- Gradle serves from cache and still reports success. When in doubt, `--rerun-tasks`.
- `adb install` fails silently when the versionCode sits below the installed one. The phone
  currently holds test version 1018, count higher for local tests.
- Cloudflare invents four hours of cache whenever a response carries no `no-store`.
- Do not script edits across several places in a Kotlin file, that broke the brace balance
  twice. Edit directly.
- The phone hangs on wireless ADB at `192.168.0.111:39773`, already paired. Screenshots via
  `adb -s 192.168.0.111:39773 exec-out screencap -p`.
- Bump the `?v=` number in `index.html` and `ios.html` on every web deploy, otherwise
  Cloudflare keeps serving the old assets.

Build: `VERSION_CODE=1019 VERSION_NAME="6.5-t19" "C:\Users\lrumk\.gradle\wrapper\dists\gradle-8.14.1-bin\baw1sv0jfoi8rxs14qo3h49cs\gradle-8.14.1\bin\gradle.bat" assembleRelease`

**Still open.** TikTok channel following `marketing/tiktok.md`, rotate the Discord secret,
Spotify playlist export.
