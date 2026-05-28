# SponsorCut

An Android app that permanently removes SponsorBlock-marked sponsor segments from locally
downloaded YouTube videos using FFmpeg. Works on de-Googled phones with no Google Play Services.

---

## Features

- **Permanent cutting** — sponsor segments physically removed from the file, not just skipped
- **SponsorBlock API** — segments pre-fetched automatically by video ID; Process button disabled if none found
- **Title + channel display** — fetched from YouTube oEmbed API, shown in UI and pinned during processing
- **FFmpeg-powered** — fast stream copy (no re-encode) or frame-accurate (re-encode) mode
- **Rumble / TS-wrapped video support** — automatic slow-seek fallback for non-standard containers
- **Audio-only support** — works on `.m4a`, `.opus`, `.mp3` downloads; saved to Music/SponsorCut
- **Background processing** — foreground service with notification, wake lock prevents CPU throttling
- **Cancel anytime** — ⏹ Cancel button stops the job mid-encode and restores the UI
- **De-Googled friendly** — no Google Play Services, no Firebase, no tracking
- **Smart ID memory** — captures YouTube ID from URL share, file share, clipboard; remembered 7 days
- **File browser** — always available to pick or swap the video file
- **Cut mode persisted** — Fast/Frame-accurate choice saved between sessions
- **Diagnostic log** — 📋 Show log button after each run for debugging

---

## How to Use

### Method A — Share URL first, then browse to file (typical PipePipe/NewPipe flow)

1. In PipePipe/NewPipe, **share the YouTube video URL** → choose SponsorCut
   - SponsorCut captures the video ID, fetches the title and SponsorBlock segments
2. Tap **📁 Browse for video file…** and navigate to your downloaded file
3. Verify the auto-filled ID, tap **Process with this ID**
4. Output saved as `originalname_clean_<timestamp>.ext`

### Method B — Share the downloaded file directly

1. Share the video/audio file from your file manager → choose SponsorCut
2. SponsorCut tries to detect the YouTube ID from:
   - Intent extras (if your player includes it)
   - Clipboard (if you copied a YouTube URL recently)
   - 7-day ID memory (pre-filled with **⚠️ from memory — verify!** label)
3. If no ID is found, tap **💡 How to get the YouTube ID** for instructions
4. Paste the URL or ID, tap **Process with this ID**

### Method C — Cold open

1. Open SponsorCut directly
2. If a recent ID is remembered, it's pre-filled with Browse and Process buttons ready
3. Tap **📁 Browse for video file…** to select the file, then **Process with this ID**

### Method D — Native integration intent (for forks)

```kotlin
val intent = Intent("com.sponsorcut.PROCESS_FILE").apply {
    putExtra("uri", localFile.uri.toString())
    putExtra("video_id", videoId)
    setPackage("com.sponsorcut")
}
startActivity(intent)
```

---

## Cut Modes

| Mode | Speed | File size | Accuracy |
|---|---|---|---|
| **Fast** (default) | Very fast | Proportional to cut | Nearest keyframe (±GOP, typically <2s) |
| **Frame-accurate** | Slow (re-encode) | CRF quality-based | Exact frame boundary |

For TS-wrapped files (e.g. Rumble), frame-accurate mode automatically retries with slow seek if fast seek fails.

---

## Subtitle Support (coming soon)

See [VISION.md](VISION.md) for the planned subtitle download and burn-in workflow using YouTube's
native caption API (`timedtext`).

---

## Building from Source

### Prerequisites

- JDK 17+ and Android SDK (compileSdk 34, minSdk 26)
- `mobile-ffmpeg-full-gpl-4.4.LTS.aar` placed at `app/libs/`
  ([download from mobile-ffmpeg releases](https://github.com/tanersener/mobile-ffmpeg/releases/tag/v4.4.LTS))

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
FFProbeInspector   ← runs ffprobe, returns VideoInfo (codec, fps, isTsEncapsulated, …)
      |
TranscodePolicy    ← decides copy vs re-encode, preset, slow-seek flag
      |
FfmpegEngine       ← executes plan; auto-retries with slow seek on rc=1
```

| Component | Role |
|---|---|
| `SponsorBlockClient` | Hash-prefix API, segment filtering |
| `FileResolver` | URI→File, output target, mime-type routing (video vs audio MediaStore) |
| `ProcessingService` | Foreground service, wake lock, cancel, progress broadcast |
| `MainActivity` | UI, ID memory, oEmbed title fetch, segment pre-fetch |
| `DiagLog` | In-memory diagnostic log buffer |

See [VISION.md](VISION.md) for full roadmap.

---

## License

MIT

---

*By: Guy Steiff — https://guysteiff.vercel.app/*
