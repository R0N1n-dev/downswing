# Downswing

A download manager for Android (and the browser preview): paste or share a link, pick a quality, watch it move through the queue. Video and audio come from yt-dlp, direct files and torrents from aria2, both running through the Capacitor plugins in `android/`.

```bash
bun install       # dependencies
bun run dev       # browser preview on :5173
bun run build     # production build into dist/
bun run sync      # build + cap sync android
bun run build-debug   # full debug APK (gradlew assembleDebug)
```

## Design system

The UI follows the pastel neo-brutalist signature spec (the “File Downloader — Signature System” sheet):

- **Paper and ink.** A `#EFEAE0` ground with a faint dot grid, `#FFFCF5` cards, 3 px `#0E0E10` borders and hard offset shadows (`4px 4px 0`). Dark mode keeps the structure with cream ink and accent-tinted shadows.
- **Type.** Archivo Black for titles, percentages and labels; Space Grotesk for everything else. Both load from `@fontsource` in `src/main.js`.
- **Pastel meaning.** Mint = progress, lavender = video, peach = audio, blush = image/error, butter = waiting, sky = doc, lime = done. Every card says what it holds before you read the name.
- **Signature moves.** Tape-style day headers (`.ghead`), a peel corner on the primary card (`.hero`, `.d-stage`), a progress dial (`.ring`), sticker buttons and chips.
- **Motion.** Every transition and keyframe runs at **0.2 s or less** (`--t` in `src/style.css`), so state changes slide, lift or pop instead of appearing out of nowhere. The screen itself renders in place — no entrance slide to jitter on launch, and nothing staggers in while the boot sticker is up (`html.booting`, cleared by `main.js`). `prefers-reduced-motion` turns all of it off.
- **Opening.** `index.html` paints the saved theme and a full-screen sticker of the launcher mark **before** the bundle loads, so a cold start is one ground colour from the Android window through to the queue — no flash, no font swap, no rows shuffling into place. Vue mounts underneath the sticker, which then waits for the webfonts **and** for the first render's slide-ins to finish (0.28 s minimum hold so it never blinks, capped at 0.7 s) before fading over 0.18 s — so the reveal is always a still frame, never the tail of an animation. The window colour lives in `android/app/src/main/res/values/colors.xml` (`boot_ground`) and matches `--bg` in dark.
- **Legibility.** Text colours are contrast-checked rather than palette-mixed: muted grey is 6.4:1 on paper, link/accent text is forced to ≥4.5:1 for every palette, and disabled controls stay at 60% opacity.
- **Colour themes.** The palettes in `src/lib/themes.js` still drive the accent (buttons, active tabs, badges) while the paper stays paper.

Icons are [HugeIcons](https://www.hugeicons.com/) (`@hugeicons/core-free-icons` rendered through `HugeiconsIcon`), used everywhere instead of the previous set.

## Features

- **Share-sheet intake action sheet** — a link shared from any app opens a bottom sheet with the host and detected type, and three choices: *Download now* (straight into the queue with your defaults), *Options* (the Add screen, prefilled) or *Copy*. Android's back button closes the sheet before it leaves the app.
- **Back button, end to end** — one gesture walks the stack in order: the lock screen holds every press, the drawer and the share sheet close first, section tabs and the Add/Details screens step back to the queue, and the queue itself asks before quitting. With work in flight it offers **Pause & exit** (stops the lane, keeps the partial files), **Exit without pausing** (downloads carry on in the foreground service) or **Keep downloading**; an idle queue gets a plain *Exit Downswing?* confirm. Pressing back on an open confirm dismisses it instead of exiting, so a stray press never closes the app. The rule set lives in `src/lib/back.js`; the confirm is `components/ConfirmSheet.vue`, drawn in paper and ink like the share sheet.
- **Queue** — up to 6 simultaneous downloads, reorderable queue, pause/resume/retry, skip-the-line start, per-download and global speed limits, auto-retry with backoff for transient failures only.
- **Controls on every unfinished row** — pause (or resume/retry) and cancel sit on the row itself, plus a cancel button on the hero card. Cancel stops the job, drops the record and offers **Undo** in the snackbar. Batch delete and *Remove from list* undo the same way.
- **Add screen** — clipboard auto-detect, multi-link paste, `.txt` import, `.torrent` import with per-file selection, a link preview that probes size and content type before you commit, and YouTube search.
- **Quality picker per source** — 240p–1080p for sites that offer a ladder; Instagram posts, reels and stories serve a single rendition, so the picker is hidden and Downswing states that it takes the best available.
- **Batch select** — *Select* on Done/Failed turns rows into checkboxes, with a batch bar showing count and total size, delete in one tap, and an **Undo** snackbar. The same undo appears after *Remove from list* in Details.
- **Pause all / Resume all** — one tap under the tabs to stop or restart every lane; the same `pauseAll()` sits behind the exit sheet's *Pause & exit*.
- **Library** — day-grouped history, file-type filter chips, search across everything, CSV export through the Android share sheet, SHA-256 verification, rename, open and share finished files.
- **Network settings** — Wi-Fi only and charging-only live under Settings → Network (the bottom bar no longer carries a toggle); queued downloads wait for the right conditions.
- **Instagram login cookie** — Settings → Instagram takes a raw `Cookie:` header so private, age-restricted or region-locked posts download. It is stored on device only and sent only to instagram.com (yt-dlp `--add-header Cookie:…`).
- **Live notification** — the Android foreground notification mirrors the queue headline (`2 active · 4.1 MB/s · 3 queued`) as it changes, and completion notices carry size and quality.
- **Navigation drawer** — mobile-first: a hamburger in the header slides in the section list (plus Settings), closes on a scrim tap, a nav tap or Android's back button. There is no bottom bar; Settings is also a gear in the header.
- **Everything else** — home-screen widget jobs are adopted and polled, completion/failure notifications with optional vibration, biometric app lock, storage and success-rate stats, light/dark themes with 13 palettes picked from a swatch grid, and a theme segmented control.
- **Launcher icon** — the sticker bolt (butter ground, open ink ring, mint bolt with ink outline and hard shadow). Adaptive vector for API 26+, themed-icon monochrome layer, and legacy PNGs at five densities rendered by `tools/gen_icons.py`; `public/favicon.svg` carries the same mark.

## Layout

```
src/
  App.vue            shell, lock screen, share action sheet, snackbar
  components/        SideNav (drawer), Dock (status strip, only while the queue moves), DownloadRow, FileTile, ShareSheet, ConfirmSheet, Switch, Stepper, FolderAccess
  views/             Downloads (all sections), AddDownload, Details, Settings
  stores/            downloads (queue engine), settings (persisted preferences)
  lib/               detect, format, sections, share intake, toast, themes, options, lock
  services/          yt-dlp, aria2, native Downswing plugin, notifications
```
