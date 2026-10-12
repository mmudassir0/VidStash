# VidStash

**Save videos and audio to watch offline — free, open source, no ads, no tracking.**

VidStash is an Android app for downloading videos, playlists and channels, with a
built-in player, chapters, MP3 conversion and a tidy library. It reads YouTube with the
open-source [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), right on
your phone — there is no server in between.

> VidStash is not affiliated with, endorsed by or connected to YouTube or Google.
> Please download only content you have the right to keep (your own uploads, Creative
> Commons videos, or content whose creator allows it), and respect copyright where you live.

## Download

Get the latest APK from **[Releases](../../releases/latest)** or the
**[website](https://mmudassir0.github.io/VidStash/)** and install it (Android 7.0 or newer).
After that, VidStash updates itself: **Settings → App update**.

VidStash is available in **English and Urdu** (Settings → Language).

## Features

**Get videos**
- Paste a link, search, or tap **Share → VidStash** in the YouTube app for a quick pop-up
- Videos up to 4K, audio as M4A or MP3 (with title and cover art)
- Whole playlists and channels; **sync** later to get only new uploads
- **Download a section** (e.g. 1:20–4:05) or **split by chapters** into separate files
- SponsorBlock: cut out sponsor segments (optional)
- Subtitles saved as .srt

**Watch and listen**
- Built-in player: background audio, picture-in-picture, speed, sleep timer,
  chapters on the seek bar, double-tap to skip, continue where you left off
- **Watch online** without downloading

**Organise**
- Library with search, sort, folders and multi-select (play / share / delete)
- Choose where files go (any folder, including an SD card)
- Backup and restore; import videos already on your phone

**Your rules**
- Wi-Fi only, only while charging, night-only, daily mobile-data limit
- Home-screen widget, notification buttons, light and dark theme

## Privacy

No accounts, ads, analytics or tracking. VidStash talks only to YouTube (to get the videos
you ask for), SponsorBlock (if you turn it on) and GitHub (for updates). Everything else
stays on your phone.

## Report a problem

Use **Settings → Report a problem** in the app (it fills in your phone and app version), or
open an [issue](../../issues). If the app crashes, the crash screen has a **Report** button.

When YouTube changes something, downloads can stop working until the YouTube library is
updated. VidStash checks for a new library every day and publishes a fixed build
automatically; the app shows a banner while it waits.

## Building

Open the project in Android Studio (JDK 17) and run, or `./gradlew assembleGithubDebug`.

There are two flavours: `github` (the APK on Releases, with the built-in updater) and
`fdroid` (for app stores that deliver updates themselves; no updater and no install
permission). Outside GitHub Actions, set the build number with `-PbuildNumber=N`
(versionCode = 100 + N, versionName = 1.2.N, release tag `v1.2.N`).
Release builds are signed in GitHub Actions with the project key; local release builds fall
back to the debug key.

## Licence

VidStash is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License v3** — see [LICENSE](LICENSE). Bundled libraries and their
licences are listed in the app under **Settings → Open-source licences**.
