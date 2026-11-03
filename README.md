# Tvheadend TV Input for Android TV

A native Android TV **TV Input Framework (TIF)** source for [tvheadend](https://tvheadend.org/).
Tvheadend channels and EPG are written into the system `TvProvider`, so they show up as a
regular TV source in Live Channels and in any other app that lists TV inputs.

## How it works

| Component | Role |
|---|---|
| `TvhInputService` / `TvhSession` | The TIF input. Tunes a channel by playing `/stream/channel/{uuid}` (MPEG-TS) with Media3 ExoPlayer, and reports audio/video/subtitle tracks to the TV app. |
| `TvProviderSync` | Mirrors `/api/channel/grid` and `/api/epg/events/grid` into `TvContract.Channels` / `TvContract.Programs`, including channel logos. |
| `SyncWorker` | WorkManager job that refreshes channels and EPG every 6 hours. |
| `SetupActivity` | Server settings. The system TV app opens it when the source is added; it is also in the launcher. |
| `TvhAuthenticator` | HTTP Basic and Digest (MD5 / SHA-256) auth, matching tvheadend's auth modes. |

## Build and install

```sh
./gradlew assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the **Tvheadend** app from the launcher (or add the source from Live Channels → Settings →
Channel sources), enter the server address and credentials, and choose *Connect and sync*.

The tvheadend user needs *Web interface* and *Streaming* rights. The streaming profile
defaults to `pass` (the original stream, unmodified).

## License

Copyright (C) 2026 Christian Braghette

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE) for the full text.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

## Roadmap

- HTSP protocol (faster zapping, timeshift, server-side subscription status)
- Timeshift support via `onTimeShift*` session callbacks
- DVR recordings exposed as `RecordedPrograms`
- Radio channel detection (`SERVICE_TYPE_AUDIO`)
- Channel tags as genre / filter
