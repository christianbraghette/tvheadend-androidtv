# Tvheadend TV Input for Android TV

A native Android TV **TV Input Framework (TIF)** source for [tvheadend](https://tvheadend.org/).
Tvheadend channels and EPG are written into the system `TvProvider`, so they show up as a
regular TV source in Live Channels and in any other app that lists TV inputs.

## How it works

| Component | Role |
|---|---|
| `TvhInputService` / `TvhSession` | The TIF input. Plays a channel with Media3 ExoPlayer, over HTTP (`/stream/channel/{uuid}`, MPEG-TS) or HTSP, and reports audio/video/subtitle tracks to the TV app. |
| `htsp/` | HTSP client: binary message codec, an authenticated connection shared by a session, and a `DataSource` + `Extractor` that feed subscription packets to ExoPlayer. |
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

The app has no launcher icon: add the source from your TV app's channel source settings
(e.g. Mochi Live TV or Live Channels), enter the server address and credentials, pick the
streaming protocol and choose *Connect and sync*.

- **HTTP**: streams MPEG-TS with the chosen streaming profile (default `pass`, the original stream).
- **HTSP** (port 9982): one persistent connection per session, so channel changes skip the HTTP
  connection and authentication. The tvheadend user needs HTSP streaming rights.

Channel and EPG sync always use the HTTP API, so the user also needs *Web interface* rights.

## License

Copyright (C) 2026 Christian Braghette

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE) for the full text.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

## Roadmap

- Timeshift over HTSP via `onTimeShift*` session callbacks
- Show HTSP subscription status (no free tuner, scrambled) as proper messages
- DVR recordings exposed as `RecordedPrograms`
- Radio channel detection (`SERVICE_TYPE_AUDIO`)
- Channel tags as genre / filter
