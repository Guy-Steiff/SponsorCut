# SponsorCut — Architecture

A complete map of the app's components, data flows, design decisions, and techniques.

---

## High-Level Overview

SponsorCut is a single-Activity Android app (no fragments, no Compose, pure View system) backed by a foreground `Service` for long-running FFmpeg jobs. There is no database, no ViewModel, and no dependency injection framework — the design is deliberately minimal to stay compatible with F-Droid's reproducible build pipeline.

```
┌─────────────────────────────────────────────────────────────┐
│  User Input Layer                                           │
│  ─────────────────────────────────────────────────────────  │
│  Share intent (URL or file)  ─┐                             │
│  File browser (SAF picker)   ─┤──▶  MainActivity            │
│  Manual ID paste             ─┘      │   │                  │
│                                      │   │                  │
│  Network Layer                       │   │                  │
│  ─────────────────────────────  ◀────┘   │                  │
│  SponsorBlockClient (OkHttp)             │                  │
│  YouTube oEmbed title fetch              │                  │
│                                          ▼                  │
│  Processing Layer                                           │
│  ─────────────────────────────────────────────────────────  │
│  ProcessingService (foreground)                             │
│      │                                                      │
│      ├──▶  FFProbeInspector  (ffprobe → VideoInfo)          │
│      ├──▶  TranscodePolicy   (VideoInfo → ProcessingPlan)   │
│      └──▶  FfmpegEngine      (ProcessingPlan → output file) │
│                │                                            │
│                └──▶  FileResolver  (SAF write, MediaStore)  │
│                                                             │
│  Diagnostics                                                │
│  ─────────────────────────────────────────────────────────  │
│  DiagLog  (in-memory ring buffer, shown on demand)          │
└─────────────────────────────────────────────────────────────┘
```

---

## Component Reference

### `MainActivity`

**Role:** UI controller, intent dispatcher, settings persistence.

**Responsibilities:**
- Handles three entry paths: cold open, `ACTION_SEND` share intent, `com.sponsorcut.PROCESS_FILE` direct intent
- Rejects new `ACTION_SEND` / `com.sponsorcut.PROCESS_FILE` intents while a processing job is active, showing a temporary user message (`please wait for current job to conclude and try again...`) so in-flight jobs are not visually overwritten by new share payloads
- Extracts YouTube video ID from: intent extras, clipboard, URL patterns, 7-day SharedPreferences cache
- Fires a background `Thread` to pre-fetch SponsorBlock segments and oEmbed title before the user taps Process
- Renders fetched segment previews using high-precision `h:mm:ss.ms` timestamps for clear pre-run inspection
- Manages three UI "cards" (YouTube ID, video file) with state machine: `PENDING → CHECKING → OK / ERROR`
- Persists user settings in `SharedPreferences("sponsorcut_prefs")`:
  - `last_video_id` + `last_video_id_ts` — 7-day ID memory
  - `last_file_uri` + `last_file_name` — last selected file
  - `output_folder_uri` — optional custom output folder (SAF tree URI)
  - `frame_accurate` — SW-Accurate mode flag
  - `hw_accurate` — HW-Accurate mode flag
  - `cat_<category>` — per-category checkbox state for all 11 SponsorBlock categories
- Sends a `startForegroundService` / `startService` intent to `ProcessingService` with all required extras
- Registers a `BroadcastReceiver` for progress updates from the service (text + numeric progress)
- Acquires `FLAG_KEEP_SCREEN_ON` during processing
- On resume, if service-side processing is still active, re-enters processing UI mode (`setProcessingUi(active=true)`) to prevent stale idle UI while background work continues

**ID extraction flow:**
```
Share intent received
  └─ EXTRA_TEXT / EXTRA_STREAM / clip data
       └─ extractId() → regex match against known YouTube URL patterns
            └─ if blank: try clipboard
                 └─ if blank: try 7-day SharedPreferences cache (labeled ⚠️)
```

---

### `SponsorBlockClient`

**Role:** Fetches sponsor segment data from the SponsorBlock API.

**Technique — hash-prefix privacy:**
The SponsorBlock API uses a k-anonymity model. Instead of sending the full video ID:
1. SHA-256 hash the video ID
2. Send only the first 4 hex characters as the URL path (`/api/skipSegments/{hashPrefix}`)
3. The server returns all entries matching that prefix
4. Filter client-side for the exact video ID

This means SponsorBlock never learns which specific video you queried.

**Category filtering:**
Selected categories are passed in as `List<String>` and serialised as a JSON array query parameter (`?categories=["sponsor","intro",...]`). The API returns only segments belonging to those categories.

**Segment parsing:**
- Iterates over the response JSON array
- Matches `videoID` field against the queried ID (the hash-prefix response may contain multiple video entries)
- Extracts `segment[0]` (start) and `segment[1]` (end) for each segment object
- Note: `actionType` filtering was deliberately removed to support all 11 category types

---

### `FFProbeInspector`

**Role:** Runs `ffprobe` on the input file and returns a `VideoInfo` data class.

**VideoInfo fields (selected):**
| Field | Type | Purpose |
|---|---|---|
| `videoCodec` | `String` | Codec name (e.g. `h264`, `hevc`, `unknown`) |
| `pixFmt` | `String?` | Source pixel format (used to choose safer re-encode defaults) |
| `audioCodec` | `String?` | Audio codec name |
| `audioBitrate` | `Long?` | Source audio bitrate (bps) for source-matched audio re-encode |
| `fps` | `Float?` | Frame rate |
| `pixels` | `Int?` | Width × height (used for bitrate heuristics) |
| `durationSec` | `Double?` | Total duration in seconds |
| `videoBitrate` | `Long?` | Video stream bitrate (bps) |
| `containerBitrate` | `Long?` | Container bitrate fallback (bps) |
| `containerFormat` | `String?` | ffprobe `format_name` (used to infer output extension/muxer) |
| `isTsEncapsulated` | `Boolean` | MPEG-TS within MP4 container (e.g. Rumble downloads) |

`FFProbeInspector` merges data from both ffprobe paths (structured API + raw JSON) so policy gets the best combined signal for bitrate/pix_fmt/container decisions.

**TS encapsulation detection:**
Some video sources wrap MPEG-TS streams inside an MP4 container. These files have `codec_tag_string = "avc1"` but use Annex B NAL unit format. FFmpeg's fast seek (`-ss` before `-i`) produces timestamp drift on these files. `isTsEncapsulated` is detected via the `nb_programs > 0` or `format_name` containing `mpegts` heuristic.

---

### `TranscodePolicy`

**Role:** Pure decision layer — converts `VideoInfo` into a `ProcessingPlan`. Never reads files.

**Decision tree:**

```
Input: VideoInfo + user mode (fast / HW-accurate / SW-accurate)
                │
                ▼
Is audio-only? ──Yes──▶ canCopyVideo=true, canCopyAudio=based on codec
                │
               No
                │
                ▼
Is video codec copy-safe? (h264, hevc, vp8, vp9, av1, mpeg4)
  AND user chose Fast mode?
  ──Yes──▶ canCopyVideo=true
  ──No───▶ canCopyVideo=false → pick encoder:
                │
                ├─ HW-Accurate ──▶ h264_mediacodec, source/fallback bitrate, single-pass filter_complex (GPU)
                └─ SW-Accurate ──▶ mpeg4, same shared bitrate policy + pix_fmt, single-pass filter_complex (CPU)
```

**Probe-driven encoding policy (clarified):**
- Audio re-encode (in accurate modes) attempts source-matched codec + bitrate first (e.g. AAC/Opus/MP3/FLAC), then falls back to `aac 128k` if needed.
- FAST + HW modes use an ffprobe-informed, clamped bitrate policy (see `computeTargetBitrate()`): prefer video stream bitrate, then container bitrate, and only fall back to conservative resolution defaults when metadata is missing. The chosen source bitrate is clamped conservatively to avoid output inflation.
- SW mode is a fully-isolated legacy CPU encode path and uses a deterministic resolution-based bitrate ladder (`swLegacyBitrate()`). SW does NOT use ffprobe bitrate inference and follows an independent path for encoder args and diagnostics.
- Output container is inferred from ffprobe `format_name` and enforced via output extension + muxer.

**Bitrate fallback policy (only when no usable bitrate metadata exists):**
- ≥1080p → 1500 kbps
- ≥720p → 1000 kbps
- ≥480p → 600 kbps
- smaller → 400 kbps

**When bitrate metadata exists:**
- Preferred order: video stream bitrate → container/format bitrate.
- The chosen source bitrate is clamped with a conservative floor by resolution and a ceiling of `1.5 × source bitrate`.
- This keeps both accurate modes from exploding tiny WEBM/VP9 or other low-bitrate sources into multi-megabit H.264 outputs.

**`ProcessingPlan` fields:**

| Field                      | Type             | Description                                                                              |
|----------------------------|------------------|------------------------------------------------------------------------------------------|
| `canCopyVideo`             | `Boolean`        | Use `-c:v copy`; skip re-encode                                                          |
| `canCopyAudio`             | `Boolean`        | Use `-c:a copy`; skip re-encode                                                          |
| `videoEncoderArgs`         | `List<String>`   | Primary video encode args derived from ffprobe                                           |
| `fallbackVideoEncoderArgs` | `List<String>`   | Conservative video fallback args                                                         |
| `audioEncoderArgs`         | `List<String>`   | Primary audio encode args derived from codec/bitrate                                     |
| `fallbackAudioEncoderArgs` | `List<String>`   | Conservative audio fallback (`aac 128k`)                                                 |
| `outputExtension`          | `String`         | Forced output extension inferred from container format                                   |
| `outputMuxer`              | `String?`        | Optional forced ffmpeg muxer (e.g. `mp4`, `matroska`)                                    |
| `complexity`               | `ComplexityTier` | LOW / MEDIUM / HIGH — used for UI messaging only                                         |
| `rationale`                | `String`         | Human-readable decision log                                                              |
| `useSlowSeek`              | `Boolean`        | Force slow seek (`-ss` after `-i`) for TS files                                          |
| `singlePassFilter`         | `Boolean`        | True for both SW- and HW-Accurate: use filter_complex single-pass instead of per-segment |

---

### `FfmpegEngine`

**Role:** Execution layer — takes a `ProcessingPlan` and cuts the file.

**Algorithm:**

```
Input: keep segments [lastEnd→segStart] derived from sponsor segments
         ┌─────┬─────┬──────────────────────┬───────────────────────────┐
         │ seg1│ seg2│   gap (keep)          │ seg3                      │
         └─────┴─────┴──────────────────────┴───────────────────────────┘

1. Invert segments → keepRanges (the parts to KEEP)
2. For each keepRange: extract to a temp part file via ffmpeg
3. Concatenate all parts via ffmpeg concat demuxer (-f concat -c copy)
4. Write final output via FileResolver
```

**Seek strategy:**
- Default: fast seek (`-ss` before `-i`) — positions before the target, faster but imprecise for encoding
- Slow seek (`-i` before `-ss`) — reads from start, used for TS-encapsulated files and as retry fallback
- If fast seek fails (non-zero rc) on a re-encode job, automatically retries with slow seek
- If source-matched encoder args fail, retries once with conservative fallback encoder args

**Progress estimation — linear regression model:**
Each segment's processing time is recorded as `(sourceDurationSec, processingTimeSec)`. A running linear regression `processingTime = a × duration + b × segments` is maintained. After 2+ data points, this predicts remaining time with increasing accuracy, displayed as `~Xm Ys left`.

**Timestamp formatting note:**
- `MainActivity` and `FfmpegEngine` intentionally keep separate time-format helpers.
- UI segment preview prefers `h:mm:ss.ms` precision.
- Live progress/ticker text prefers compact `m:ss` / `h:mm:ss` for readability under frequent updates.

**Concat strategy:**
- Single-part result: direct file copy (no concat overhead)
- Multi-part result: write a concat list file, run `ffmpeg -f concat -safe 0 -c copy` (with forced muxer when available)

**Encoder args by mode:**

| Mode        | Video args                                                         | Audio args                                        |
|-------------|--------------------------------------------------------------------|---------------------------------------------------|
| Fast        | `-c:v copy`                                                        | `-c:a copy`                                       |
| HW-Accurate | `-c:v h264_mediacodec -b:v {shared-policy-target} -pix_fmt {safe}` | source-matched codec/bitrate, fallback `aac 128k` |
| SW-Accurate | `-c:v mpeg4 -b:v {legacy-ladder-target} -pix_fmt {source-aware}`   | source-matched codec/bitrate, fallback `aac 128k` |

Both SW-Accurate and HW-Accurate use the **same single-pass `filter_complex` architecture**:
multiple seeked inputs (one per keep range) → `trim+setpts+concat` → single encoder init.
The only difference is the encoder: `mpeg4` (CPU) vs `h264_mediacodec` (Android MediaCodec GPU).

---

### `ProcessingService`

**Role:** Android foreground `Service` — isolates the long-running FFmpeg job from the Activity lifecycle.

**Why a Service (not a coroutine or WorkManager)?**
- FFmpeg jobs can run for 30+ minutes on long videos
- The Activity may be destroyed mid-job (screen off, other apps)
- A foreground service with a notification keeps the process alive and prevents the CPU wake lock from being revoked
- WorkManager is designed for deferrable background tasks; a foreground service is the correct primitive for user-initiated, time-sensitive work

**Cross-component busy flag:**
- `ProcessingService` exposes `isProcessingActive` (`@Volatile`) as a lightweight in-process guard.
- Set to `true` when processing thread starts and reset to `false` in `finally`.
- `MainActivity` checks this flag before handling new share/direct intents, and rejects them while the current job is running.

**Intent extras consumed:**
| Extra | Type | Description |
|---|---|---|
| `uri` | `String` | Input file URI |
| `video_id` | `String` | YouTube video ID |
| `id_source` | `String` | Debug: where the ID came from |
| `output_folder_uri` | `String?` | Optional output folder (SAF tree URI) |
| `frame_accurate` | `Boolean` | SW-Accurate mode |
| `hw_accurate` | `Boolean` | HW-Accurate mode |
| `categories` | `ArrayList<String>` | SponsorBlock categories to cut |

**Processing sequence:**
```
1. Acquire CPU wake lock (max 2h)
2. Copy input URI → cache file (FileResolver.uriToFile)
3. FFProbeInspector.inspect → VideoInfo
4. TranscodePolicy.plan → ProcessingPlan
5. SponsorBlockClient.fetchRich(videoId, categories) → segments
6. FfmpegEngine.process → temp output file
7. FileResolver.writeToOutputTarget → final destination
8. Broadcast DONE + show notification
9. Release wake lock
```

**Broadcast protocol (`com.sponsorcut.PROGRESS`):**
- Text progress: `EXTRA_PROGRESS_TEXT` (String)
- Numeric progress: `EXTRA_PROGRESS_CURRENT` + `EXTRA_PROGRESS_TOTAL` (Int) → drives ProgressBar
- Terminal states: `EXTRA_DONE`, `EXTRA_ERROR`, `EXTRA_CANCELLED` (Boolean)

**Cancel flow:**
- `ACTION_CANCEL` intent → `FFmpegKit.cancel()` → FFmpeg returns rc=255
- Engine detects `ReturnCode.isCancel()` → throws `"CANCELLED"` → service broadcasts `EXTRA_CANCELLED`

---

### `FileResolver`

**Role:** Abstracts all Android storage API complexity.

**Key operations:**
- `uriToFile()` — copies a content:// URI to a cache `File` for FFmpeg (which cannot read content URIs directly)
- `getDisplayName()` — queries `MediaStore.MediaColumns.DISPLAY_NAME` from a content URI
- `outputFileNameFromSource()` — appends `_clean_<timestamp>` to the source name
- `createOutputTarget()` — decides where to write based on MIME type:
  - Audio files → `Music/SponsorCut/` via `MediaStore.Audio.Media`
  - Video files → `Movies/SponsorCut/` via `MediaStore.Video.Media`
  - Custom folder → SAF `DocumentFile` tree
- `writeToOutputTarget()` — streams the temp file to the chosen target via `OutputStream`

---

### `DiagLog`

**Role:** In-memory append-only log buffer. Thread-safe. Shown in the UI after each run.

Stores tagged entries as `[TAG] message` strings, capped at a fixed size. Cleared at the start of each job. The UI's "📋 Show diagnostic log" button dumps the entire buffer.

---

## Data Flow Diagram

```
User taps Process
      │
      ▼
MainActivity.startProcessing()
  ├─ reads: pendingUri, videoId, selectedCategories(), radioAccurate/radioHwAccurate
  └─ starts ProcessingService via Intent
            │
            ▼
      FileResolver.uriToFile()  ──▶  /cache/input_xxx.ext
            │
            ▼
      FFProbeInspector.inspect()  ──▶  VideoInfo
            │
            ▼
      TranscodePolicy.plan(info, frameAccurate, hwAccurate)  ──▶  ProcessingPlan
            │
            ▼
      SponsorBlockClient.fetchRich(videoId, categories)  ──▶  List<SponsorSegmentInfo>
            │
            ▼
      FfmpegEngine.process(input, tempOutput, segments, plan)
        ├─ for each keepRange:
        │    ffmpeg -ss {start} -t {dur} -i input [encoder args] part_N.ext
        └─ ffmpeg -f concat -c copy  ──▶  concat_out.ext
            │
            ▼
      FileResolver.writeToOutputTarget()  ──▶  final file
            │
            ▼
      Broadcast DONE  ──▶  MainActivity updates UI + shows donation footer
```

---

## Threading Model

| Thread                            | What runs there                                                          |
|-----------------------------------|--------------------------------------------------------------------------|
| Main (UI) thread                  | All View updates, BroadcastReceiver callbacks                            |
| `Thread { }` in MainActivity      | Pre-fetch segments + oEmbed title (before job starts)                    |
| `Thread { }` in ProcessingService | Entire FFmpeg pipeline (FFProbeInspector, TranscodePolicy, FfmpegEngine) |
| FFmpegKit internal threads        | Actual ffmpeg/ffprobe execution                                          |

All UI updates from background threads use `runOnUiThread {}`.

---

## SharedPreferences Schema

All stored in `Context.getSharedPreferences("sponsorcut_prefs", MODE_PRIVATE)`:

| Key                    | Type    | Default | Description                             |
|------------------------|---------|---------|-----------------------------------------|
| `last_video_id`        | String  | `""`    | Last successfully used YouTube video ID |
| `last_video_id_ts`     | Long    | `0`     | Unix ms timestamp of last ID save       |
| `last_file_uri`        | String  | `null`  | Last used input file URI                |
| `last_file_name`       | String  | `null`  | Display name of last input file         |
| `output_folder_uri`    | String  | `null`  | Custom output folder SAF tree URI       |
| `frame_accurate`       | Boolean | `false` | SW-Accurate mode selected               |
| `hw_accurate`          | Boolean | `false` | HW-Accurate mode selected               |
| `cat_sponsor`          | Boolean | `true`  | SponsorBlock: Sponsor (default on)      |
| `cat_selfpromo`        | Boolean | `false` | SponsorBlock: Self-promotion            |
| `cat_interaction`      | Boolean | `false` | SponsorBlock: Interaction reminder      |
| `cat_intro`            | Boolean | `false` | SponsorBlock: Intro                     |
| `cat_outro`            | Boolean | `false` | SponsorBlock: Outro                     |
| `cat_preview`          | Boolean | `false` | SponsorBlock: Preview                   |
| `cat_filler`           | Boolean | `false` | SponsorBlock: Filler                    |
| `cat_music_offtopic`   | Boolean | `false` | SponsorBlock: Non-music section         |
| `cat_poi_highlight`    | Boolean | `false` | SponsorBlock: Highlight                 |
| `cat_exclusive_access` | Boolean | `false` | SponsorBlock: Exclusive access          |
| `cat_chapter`          | Boolean | `false` | SponsorBlock: Chapter markers           |

---

## External Dependencies

| Library                              | Version                     | Purpose                                   |
|--------------------------------------|-----------------------------|-------------------------------------------|
| `ffmpeg-kit.aar`                     | LTS (InfinityLoop1308 fork) | FFmpeg + ffprobe execution on Android     |
| `com.arthenica:smart-exception-java` | 0.2.1                       | Required transitive dep of ffmpeg-kit     |
| `androidx.core:core-ktx`             | 1.13.1                      | Kotlin Android extensions                 |
| `androidx.documentfile:documentfile` | 1.0.1                       | SAF DocumentFile for custom output folder |
| `com.squareup.okhttp3:okhttp`        | 4.12.0                      | SponsorBlock API HTTP client              |

No analytics, no crash reporting, no Google Play Services.

---

## F-Droid Build Notes

SponsorCut is distributed on F-Droid. Key build constraints:

- **ffmpeg-kit must be built from source** — F-Droid prohibits pre-built native binaries. The `Builds` entry in `metadata/com.sponsorcut.yml` uses `InfinityLoop1308-ffmpeg-kit` as a `srclib` and builds the AAR during the F-Droid build job.
- **Signing** — The release APK is signed with a developer key. `AllowedAPKSigningKeys` in the YML pins the expected certificate SHA-256 so F-Droid can verify authenticity.
- **`Binaries:` field** — GitHub Releases APKs are also verified by F-Droid against the signing key.
- **Reproducible build** — The NDK version (`25.2.9519653`) and CMake version are pinned to ensure bit-identical native output across builds.

---

## HW-Accurate Mode — Device Compatibility Notes

`h264_mediacodec` is Android's hardware H.264 encoder accessed via the MediaCodec API through ffmpeg-kit. Behaviour varies by SoC:

| SoC Family          | Typical behaviour                                              |
|---------------------|----------------------------------------------------------------|
| Qualcomm Snapdragon | Excellent — reliable, fast, good quality                       |
| MediaTek Dimensity  | Good on recent chips; older chips may produce container issues |
| Samsung Exynos      | Variable — some models have known MediaCodec quirks            |
| Google Tensor       | Generally good                                                 |

**Known limitations:**
- MediaCodec encoders accept bitrate parameters as configuration constraints; they do not interpret bitrate as a quality target. Device encoder implementations may exhibit different quality/behavior at equivalent bitrate settings.
- Some devices do not support `yuv420p` input to MediaCodec and will fall back to a software path internally (still reported as `h264_mediacodec`).
- If the job fails (rc ≠ 0), retry with SW-Accurate mode.

---

*See also: [VISION.md](VISION.md) for planned features.*

TRANSCODEPOLICY – SINGLE DECISION GRAPH ENFORCEMENT RULESET

PURPOSE
This document defines the non-negotiable architecture rules for TranscodePolicy.
Its goal is to ensure deterministic, drift-proof encoding decisions across SW (frameAccurate) and HW/FAST (MediaCodec) paths.

CORE PRINCIPLE: SINGLE DECISION GRAPH
All codec compatibility and copy/transcode decisions MUST originate from a single deterministic function:
    deriveCoreDecisions()

This function is the ONLY place where:
- codec compatibility is evaluated
- force re-encode conditions are applied
- canCopyVideo is computed
- canCopyAudio is computed

NO EXCEPTIONS:
- No recomputation of canCopyVideo or canCopyAudio anywhere else
- No re-derivation of codec rules in plan(), buildSwPlan(), or helpers
- No “shortcut logic” based on VideoInfo fields outside CoreDecisions

DATA CONTRACT

CoreDecisions is the ONLY decision payload passed downstream:

CoreDecisions:
- isAudioOnly
- videoCodecKnownBad
- videoIsCopyable
- audioCodecKnownBad
- forceReEncode
- canCopyVideo   ← FINAL CANONICAL VALUE
- canCopyAudio   ← FINAL CANONICAL VALUE

ENFORCEMENT RULE: OUTPUT-LEVEL TRUTH
All downstream functions MUST treat CoreDecisions as immutable truth.

Allowed usage:
- decisions.canCopyVideo
- decisions.canCopyAudio

Forbidden patterns:
- Recomputing:
    videoIsCopyable && !forceReEncode
- Accessing raw fields to rebuild decisions
- Duplicating codec rules in any function outside deriveCoreDecisions()

FUNCTION RESPONSIBILITIES

deriveCoreDecisions()
- ONLY function allowed to implement policy logic
- Must compute all derived booleans including final decisions
- Must remain pure and deterministic

plan()
- Consumer only
- May branch on frameAccurate / hwAccurate mode selection
- MUST NOT compute any codec/copy logic

buildSwPlan()
- Consumer only
- Must NOT recompute or override decisions
- Must use CoreDecisions verbatim

inferOutputContainer()
- Pure policy consumer of CoreDecisions
- MUST NOT infer codec compatibility or copy rules

BITRATE POLICY SEPARATION
- computeTargetBitrate(): HW/FAST only
- swLegacyBitrate(): SW only
- No cross-usage permitted

ARCHITECTURAL GUARANTEE

If any of the following occurs, it is a critical architecture violation:
- canCopyVideo computed outside deriveCoreDecisions()
- canCopyAudio computed outside deriveCoreDecisions()
- codec rules duplicated in any downstream function
- SW and HW paths diverging in decision logic

DESIGN INVARIANT

Given identical FFProbeInspector.VideoInfo input:
→ deriveCoreDecisions() MUST produce identical CoreDecisions
→ SW and HW paths MUST consume identical canCopyVideo/canCopyAudio
→ No downstream function may alter or reinterpret decisions

ANTI-DRIFT GUARANTEE

All policy changes MUST follow this strict mapping:
- Copy/transcode logic change → modify deriveCoreDecisions() ONLY
- Container logic change → modify inferOutputContainer() ONLY
- Bitrate logic change → modify computeTargetBitrate() or swLegacyBitrate() ONLY

Any deviation creates undefined behavior and is not permitted.

ARCHITECTURAL INTENT

This system is designed to ensure:
- zero decision drift between SW and HW pipelines
- single-source auditability of encoding decisions
- deterministic reproducibility of transcoding behavior
- elimination of duplicated policy logic across execution paths