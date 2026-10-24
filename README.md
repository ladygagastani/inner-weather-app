# Inner Weather (Android)

The standalone phone app for Inner Weather Log. The Daylight app runs from the phone and keeps its records on the phone.
This repo holds **code only**: no records, no signing key.

- `web/app.html`: Daylight. `web/runtime-core.js` + `web/boot.js`: the on-phone stand-in for the claude.ai runtime
  (database in `files/store.json` with daily backups, downloads to Downloads, Ask Claude with your own Anthropic API key).
- `android/`: the Android shell (edge to edge, update check before every start).
- `feed/`: what installed apps read on every start (`version.json`). A newer `web` updates Daylight in place; a newer `apk` offers a one-tap install.
- `build.sh`, `publish-feed.sh`: build and release (see the comments at the top of each).
