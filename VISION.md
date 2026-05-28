# SponsorCut — Vision

SponsorCut permanently removes sponsor segments from locally downloaded YouTube videos on Android.
It operates entirely offline after download, requires no Google Play Services, and is designed for
de-Googled phones (GrapheneOS, CalyxOS, DivestOS, etc.).

---

## Current State (v1)

- Share a video file from PipePipe/NewPipe → SponsorCut removes sponsor segments via FFmpeg
- YouTube video ID captured from URL share, file share, clipboard, or 7-day memory cache
- Title + channel name fetched from YouTube oEmbed API; shown in UI and pinned during processing
- SponsorBlock segments pre-fetched on ID entry; Process button greyed out if none found
- Fast mode: stream copy (`-c copy`), no re-encode, output ≈ proportional to input size
- Frame-accurate mode: libx264 re-encode, exact frame cuts
- Automatic slow-seek fallback when fast seek fails (TS-wrapped / Rumble files)
- Wake lock keeps CPU running when screen turns off during long jobs
- Cancel button halts FFmpeg mid-job and restores UI
- Background foreground service with notification progress bar
- `FFProbeInspector` → `TranscodePolicy` → `FfmpegEngine` layered architecture
- Audio-only files (m4a, opus, mp3) supported; saved to Music/SponsorCut
- File picker available at all times; cold-open restores last used ID
- In-app diagnostic log (📋 Show log) for debugging FFmpeg failures

---

## Near-Term: Subtitle Support

### How it will work

YouTube exposes captions through an undocumented but stable API (exists since ~2012):

**Step 1 — discover available languages:**
```
GET https://www.youtube.com/api/timedtext?v=VIDEO_ID&type=list
```
Returns XML listing all available caption tracks with language codes and names.

**Step 2 — download a track:**
```
GET https://www.youtube.com/api/timedtext?v=VIDEO_ID&lang=en&fmt=vtt
```
Returns WebVTT directly. Supported formats: `vtt`, `srt`, `srv3` (XML).

**Fallback (~20-30% of videos):** newer auto-generated captions use signed URLs.
Fetch `youtube.com/watch?v=ID`, parse `captionTracks` from the embedded
`ytInitialPlayerResponse` JSON to get the real URL.

### Implementation plan

1. Fetch language list alongside SponsorBlock segments when an ID is entered
2. If captions available, show language picker (default: English)
3. Download selected VTT to cache
4. Two output options:
   - **Soft subtitles** (instant): embed VTT as subtitle track via `-c:s copy` — no re-encode,
     requires player support (VLC, most Android players)
   - **Burn-in** (slow): `-vf "subtitles=captions.vtt"` — forces re-encode, works in all players
5. Subtitle timestamps adjusted to match sponsor-cut output (segment offsets applied)

### Why native YouTube API over NewPipeExtractor

- No extra dependency
- No maintenance burden when YouTube changes internals (NewPipe breaks on every major YT update)
- Direct HTTP, no abstraction layer needed

---

## Medium-Term: Direct Download via NewPipeExtractor

```kotlin
implementation("com.github.TeamNewPipe:NewPipeExtractor:0.24.x")
```

One-tap pipeline from a YouTube URL:
```
URL input
  → resolve stream + caption URLs  (NewPipeExtractor)
  → download video + .vtt          (OkHttp, with progress)
  → FFProbeInspector               (inspect file)
  → SponsorBlockClient             (fetch sponsor segments)
  → FfmpegEngine                   (cut sponsors + embed/burn subs)
  → save output
```

---

## Architecture

| Layer | Responsibility |
|---|---|
| `FFProbeInspector` | Truth extraction — runs ffprobe, returns `VideoInfo` |
| `TranscodePolicy` | Decisions — consumes `VideoInfo`, returns `ProcessingPlan` |
| `FfmpegEngine` | Execution — consumes `ProcessingPlan`, produces output file |
| `SponsorBlockClient` | API — fetches and filters sponsor segments |
| `FileResolver` | I/O — URI → File, output target resolution, mime-type routing |
| `ProcessingService` | Orchestration — foreground service, wake lock, progress broadcast |
| `MainActivity` | UI — share/intent handling, status, ID memory, title/segment fetch |
| `DiagLog` | Diagnostics — in-memory log shown in UI after a run |

**Rule**: nothing outside `FFProbeInspector` calls FFprobe. Nothing outside `FfmpegEngine` calls FFmpeg.
Policy decisions live only in `TranscodePolicy`.

---

## Possible Future Directions

- **SponsorBlock category selection** — let user choose which categories to cut (currently: sponsor only)
- **Batch processing** — queue multiple files / URLs
- **Chapter-aware cutting** — use YouTube chapter metadata to label kept segments
- **Preview before save** — show timeline with segments highlighted before committing
- **NewPipe/PipePipe PR** — propose native `com.sponsorcut.PROCESS_FILE` intent integration upstream

---

*By: Guy Steiff — https://guysteiff.vercel.app/*
