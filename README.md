# SponsorCut

An Android app that permanently removes SponsorBlock-marked segments from locally
downloaded YouTube videos using FFmpeg. Works on de-Googled phones with no Google Play Services.

---

## Features

- **Permanent cutting** — segments physically removed from the file, not just skipped
- **SponsorBlock category selection** — choose which segment types to cut (Sponsor checked by default); all 11 categories supported, persisted between sessions
- **SponsorBlock API** — segments pre-fetched automatically by video ID; Process button disabled if none found
- **Readable segment preview** — fetched segments are shown as `h:mm:ss.ms` timestamps before processing
- **Title + channel display** — fetched from YouTube oEmbed API, shown in UI and pinned during processing
- **Three cut modes** — Fast (stream copy), HW-Accurate (device GPU via MediaCodec, single-pass), SW-Accurate (mpeg4 CPU, single-pass)
- **Audio-only support** — works on `.m4a`, `.opus`, `.mp3` downloads; saved to Music/SponsorCut
- **Background processing** — foreground service with notification, wake lock prevents CPU throttling
- **Busy-state guard** — new share/direct intents are rejected while a job is running (`please wait for current job to conclude and try again...`)
- **Cancel anytime** — ⏹ Cancel button stops the job mid-encode and restores the UI
- **De-Googled friendly** — no Google Play Services, no Firebase, no tracking
- **Smart ID memory** — captures YouTube ID from URL share, file share, clipboard; remembered 7 days
- **File browser** — always available to pick or swap the video file
- **All settings persisted** — cut mode, category selection, output folder saved between sessions
- **Diagnostic log** — 📋 Show log button after each run for debugging
- **Container inference** — output container is inferred from ffprobe metadata (not only filename extension)
- **Performance diagnostics** — logs charging/power state and full processing equation (`processing_time_s = a * (duration_time_s) + b`) when available

---

## How to Use

> If a processing job is already running, new share/direct intents are ignored to protect the active job state.
> The app shows: `please wait for current job to conclude and try again...`

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

| Mode               | Speed         | Accuracy                   | Quality                                                                      | Notes                                                                                   |
|--------------------|---------------|----------------------------|------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| **Fast** (default) | Seconds       | Nearest keyframe (~0.5–2s) | Lossless                                                                     | Best for most use cases                                                                 |
| **HW-Accurate**    | Moderate      | Exact frame boundary       | Source-informed bitrate + safe pix_fmt (fallback profile on failure)         | h264_mediacodec (Android GPU) — single-pass filter_complex, faster but device-dependent |
| **SW-Accurate**    | Moderate–slow | Exact frame boundary       | Legacy resolution-based bitrate ladder (lower quality, higher CPU usage)       | mpeg4 CPU encoder — single-pass filter_complex, no GPU needed, always available         |

> **Architecture note:** Both accurate modes use the same single-pass `filter_complex` approach:
> multiple seeked inputs (one per keep range) fed into `trim+setpts+concat` in a single FFmpeg command,
> so the encoder is initialised exactly once. The only difference is the encoder: `mpeg4` (CPU) vs `h264_mediacodec` (GPU).

> **Probe-driven note:** In accurate modes, audio/video settings attempt source-matched codec/bitrate/pixel-format choices first,
> then retry once with a conservative fallback profile if the first encode fails.

> **Bitrate policy note:** FAST and HW modes use an ffprobe-informed, clamped bitrate policy (prefer stream bitrate, then container bitrate, then conservative resolution fallback). SW mode uses a deterministic legacy resolution-based bitrate ladder and does NOT use ffprobe bitrate inference.
> This prevents low-bitrate sources from inflating to much larger outputs while keeping SW as a compatibility path.

> **Timestamp note:** The segment preview uses high-precision `h:mm:ss.ms`, while live processing progress uses
> compact `m:ss` / `h:mm:ss` labels for readability during frequent updates.

> **LGPL note:** SW-Accurate uses `mpeg4` which is built into every FFmpeg binary with no external
> library requirement — no libx264 (GPL) needed.

**HW-Accurate** uses Android's MediaCodec hardware H.264 encoder. Speed and compatibility vary by device:
- Qualcomm Snapdragon: generally excellent
- MediaTek / Exynos: variable — some devices produce subtly broken output
- Fallback: if HW-Accurate fails or is slow on your device, use SW-Accurate instead

Observed in real use: HW-Accurate performance can vary significantly by power state (for example, the same job may run much faster while charging). This is expected on some Android devices due to governor/thermal behavior.

The app automatically recommends Fast mode for long videos (>30 min) and audio-only files.

---

Mode clarifications (user-facing)

- FAST mode: optimized automatic processing that prefers stream/container copy where possible and falls back to conservative re-encode settings when needed.
- HW mode: hardware-accelerated encoding (MediaCodec) for faster, lower-CPU runs. Note that hardware encoders enforce constraints and may clamp bitrate — they are not a quality optimizer.
- SW mode: legacy CPU encoding using a deterministic resolution-based bitrate ladder. SW mode may produce lower visual quality and uses significantly more CPU; recommended only for compatibility or testing.

Warning: SW mode may produce lower visual quality and higher CPU load. Prefer FAST or HW when possible.


## SponsorBlock Categories

All 11 SponsorBlock categories are available in the UI. Only **Sponsor** is checked by default:

| Category                | Description                               |
|-------------------------|-------------------------------------------|
| Sponsor                 | Paid promotion / sponsorship segment      |
| Self-promotion          | Unpaid plug for the creator's own content |
| Interaction reminder    | Subscribe / like / bell reminders         |
| Intro / intermission    | Opening animation or scene transition     |
| Outro / end cards       | End screen with subscribe buttons         |
| Preview / recap         | Summary of previous content               |
| Filler / tangent / joke | Off-topic content                         |
| Non-music section       | Talking in an otherwise music video       |
| Highlight / POI         | Point-of-interest marker                  |
| Exclusive access        | Members-only / paywalled segment marker   |
| Chapter markers         | Chapter boundary markers                  |

Selections are persisted in SharedPreferences and the segment preview re-fetches automatically when you change a checkbox.

---

## FFmpeg Backend

SponsorCut uses **ffmpeg-kit** packaged as a local AAR (`app/libs/ffmpeg-kit.aar`), built by F-Droid via the
[InfinityLoop1308/ffmpeg-kit](https://github.com/InfinityLoop1308/ffmpeg-kit) srclib.

The AAR must be present in `app/libs/ffmpeg-kit.aar` before building. For local development, obtain it from:
- A PipePipe/NewPipe build environment
- The F-Droid build outputs for those apps
- Building [InfinityLoop1308/ffmpeg-kit](https://github.com/InfinityLoop1308/ffmpeg-kit) directly

---

## Building from Source

```bash
# Debug build (signed with debug key)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# Release build (uses debug key locally; CI uses KEYSTORE_PATH env var)
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Release signing via environment variables (used by GitHub Actions):
```
KEYSTORE_PATH=/path/to/keystore.jks
KEYSTORE_PASS=...

# SponsorCut

SponsorCut is an Android utility that permanently removes SponsorBlock-marked segments from locally downloaded YouTube video/audio files using FFmpeg (via ffmpeg-kit). It targets privacy-respecting environments (no Google Play Services) and is suitable for F‑Droid distribution.

Prerequisites
- JDK 11+ (use the bundled Gradle wrapper)
- Android SDK and build tools matching the project's compileSdk
- `app/libs/ffmpeg-kit.aar` must be present for local builds (see ARCHITECTURE.md / FFmpeg Backend)

Quick start
```bash
# Build a debug APK
./gradlew assembleDebug

# Build a release APK
./gradlew assembleRelease
```

Repository layout (key items)
- `app/` — Android application module
- `app/libs/ffmpeg-kit.aar` — required local FFmpeg AAR
- `metadata/com.sponsorcut/` — F-Droid metadata
- `scripts/` — helper scripts (e.g. `build-ffmpeg-kit.sh`)

How it works (short)
- The app accepts shared URLs or files, pre-fetches SponsorBlock segments and video metadata, then starts a foreground `ProcessingService` which runs `ffprobe` and `ffmpeg` steps (via ffmpeg-kit) to cut segments and write a finalized file.
- A `TranscodePolicy` decides whether to stream-copy or re-encode and picks safe encoder parameters using `ffprobe` data to avoid inflating file size.

See `ARCHITECTURE.md` for a detailed overview of components, the processing pipeline, and design rationale.

License
- MIT

Author
- Guy Steiff
