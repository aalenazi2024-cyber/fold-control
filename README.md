# Fold Control

Device-owner app for Steve's Galaxy Z Fold8. It owns the phone from first boot, keeps Samsung's pre-installed apps hidden, and silently installs and updates apps published as GitHub releases.

## How it fits together

- **Push to `main`** → GitHub Actions builds a signed APK and publishes it as release `vN` (`fold-control.apk`).
- **`config.json`** → read by the phone every 15 minutes. Edit it to change what's hidden (`hide`), bring apps back (`show`), or add apps to auto-install (`apps`: `{"repo": "owner/name", "asset": "app.apk"}`, public repos). Editing config does not trigger a rebuild.
- **Phone** → syncs every 15 min on any network; installs updates to itself and listed apps with no prompt.

## First-time setup (factory-fresh phone only)

1. Power on. On the very first welcome screen, tap the same spot 6 times.
2. Connect to Wi-Fi when asked, then scan `provisioning/setup-qr.png`.
3. Let it download and finish; continue setup and sign into Google.
4. Open **Fold Control** → **Clean phone**.

To remove device ownership: factory reset.

## Signing

`signing/keystore.p12.enc` is the APK signing key, AES-256 encrypted. The password lives only in the `KEYSTORE_PASSWORD` repo secret. The QR code pins the key's checksum (`signing/signature-checksum.txt`); the build fails if they ever differ. Never change the key: the phone will refuse updates signed by a different one.

## Door Control (`door/`)

Second app in this repo: a direct LAN client for the villa's Hikvision intercom (door stations, face stations, indoor monitor) over ISAPI with Digest auth. Every release also publishes `door-control.apk`, and `config.json` tells the phone to install it.

- **Home screen**: saved devices with one-tap Open per lock relay.
- **Device screen**: Open / Close / Hold open / Hold locked, each followed by a 12 s watch of the lock and door-contact state; Status, Events (last 24 h), Config readout, Snapshot, and a raw ISAPI console (GET into body, edit, PUT back).
- **Share log** sends the full trace for review.

Passwords are encrypted with an Android Keystore key. The phone must be on the villa network (or reach it over Tailscale).

## Triage (`triage/`)

Third app: a notification gatekeeper for Outlook and Teams that runs entirely on the phone. No Microsoft sign-in, no admin consent, no cloud: it reads only what Outlook/Teams already put in their notifications (Android *Notification access*, granted on the phone).

- **Needs you** (VIP, Teams @mention, urgent words, senders you usually open, or the local model's call) → Outlook's notification is replaced by one alert with a summary and **Snooze** (5 min · 30 min · 1/2/3 h · this evening · tomorrow 9 am), **Reply** (copies the drafted reply and opens the thread) or **Open**, and **Done**. Swipe = ignore, and Triage learns from it.
- **FYI / noise** → removed silently into one collapsed, silent digest; noise gets Outlook's own *Mark as read* pressed when offered. Long-press anything in *Filtered* to make that sender always alert.
- **Hidden content** (company policy shows only "New message") → left untouched.
- **Optional local model** (Settings): any OpenAI-compatible URL — llama.cpp in Termux on the phone (`http://127.0.0.1:8080/v1`) or the workstation over Tailscale — for better judgement, real summaries and reply drafts. Blank = rules only.

Every release publishes `triage.apk`; `config.json` tells the phone to install it. First run: open Triage → allow notification access, alerts and exact alarms.

## Shelf (`shelf/`)

Fourth app: an offline library for flights. It downloads YouTube/TikTok videos and audio with yt-dlp (bundled, self-updating) into collections and plays them in order, video full screen and audio in the background with the screen off.

- **What to download** lives in `shelf/catalog.json` (searches, channels, playlists, profiles, and a Quran reciter from mp3quran.net). The phone re-reads it every 20 minutes; editing it needs no rebuild. `stopAt` ends automatic downloading; `maxGB` / `minFreeGB` cap storage.
- **Background**: Fold Control wakes Shelf after installing it, so downloading starts without opening the app. Runs on Wi-Fi only, in 8-minute chained jobs; opening the app switches to a faster foreground download with a progress notification.
- **Add your own**: share any YouTube or TikTok link to Shelf, or tap *Add link* (videos, playlists, or a TikTok profile).
- **Log** button shows what downloaded and what failed.
