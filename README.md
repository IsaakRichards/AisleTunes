<p align="center">
  <img src="art/icon-512.png" width="160" alt="AisleTunes icon: a golden shopping cart with music notes rising out of it">
</p>

<h1 align="center">AisleTunes</h1>

<p align="center">One button. Walmart &amp; Sam's Club Radio, live, anywhere.</p>

<p align="center">
  <a href="../../releases/latest"><b>⬇ Download the latest APK</b></a>
</p>

---

AisleTunes is a tiny Android app that tunes straight into the in-store radio network heard in every Walmart and Sam's Club. Tap the golden button and you're in the aisles.

## Features

- **One tap to tune in** to the official live stream
- **Now playing**: song title, artist, album, year and cover art
- **Recently played**: the last six songs with the time each one aired
- **Background playback** with lock-screen, notification and Bluetooth controls
- **Always live**: after a long pause it rejoins the broadcast instead of playing old audio

## Install

1. Open the [latest release](../../releases/latest) on your phone.
2. Download `AisleTunes-x.y.z.apk` and open it.
3. If Android asks, allow installs from your browser.

Requires Android 8.0 or newer.

## How it works

- Audio: the station's public stream, played with AndroidX Media3 (ExoPlayer) in a media session service.
- Song info: the station's live metadata feed, with the stream's own ICY titles as a fallback.
- Album, year and cover art: Apple's public iTunes Search API. During DJ talk or songs it can't match, the station's name is shown instead.

A browser version of the same player lives in [`web/index.html`](web/index.html).

## Building

Every push to `main` builds an APK in GitHub Actions. To ship a new release, bump the number in `version.txt` (for example `1.0.1`) and push: the workflow publishes `v1.0.1` with the APK attached. Pushing a tag like `v1.0.1` works too.

```sh
./gradlew assembleRelease
```

### Signing

Builds are signed with the project keystore in `keystore/` so each release installs over the last one. For a private key, add these repository secrets and the workflow will use them instead:

| Secret | Value |
| --- | --- |
| `AISLETUNES_KEYSTORE_BASE64` | `base64 -w0 your.keystore` |
| `AISLETUNES_KEYSTORE_PASSWORD` | keystore password |
| `AISLETUNES_KEY_ALIAS` | key alias |
| `AISLETUNES_KEY_PASSWORD` | key password |

Switching keys means uninstalling the old build once.

## Disclaimer

AisleTunes is an unofficial fan project. It is not affiliated with, endorsed by, or sponsored by Walmart Inc. or Sam's Club. All station audio and names belong to their owners.
