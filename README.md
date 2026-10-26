# SponsorCut / MegaCut

SponsorCut is an Android app that permanently removes SponsorBlock-marked segments from locally downloaded YouTube videos and audio using FFmpeg. It works on de-Googled phones with no Google Play Services.

MegaCut is the post-rebrand, broader offline media-processing platform built on the same core. Its purpose is to provide reusable offline conversion infrastructure so Android projects can use local device resources and FFmpeg without depending on big-tech online conversion services.

## Vision

MegaCut Core exists to create infrastructure for offline conversion where large technology platforms impose their own online conversion schemes.

It is meant to liberate many projects to use local device resources for easy mobile FFmpeg features that are otherwise difficult to implement without becoming a transcoding expert.

SponsorCut is the first major application built on this foundation.

Its first purpose is to cut out time-wasting segments for people who are fed up with ads and interruptions. It allows non-stop listening or viewing without breaking rhythm—for example at the gym, while commuting, studying, or doing chores.

MegaCut expands this into a broader offline media toolkit capable of:

- Extracting video.
- Extracting audio.
- Cutting media.
- Merging media.
- Applying filters to selected segments.
- Transcoding media.
- Using hardware acceleration where available.
- Operating offline.
- Avoiding Google Play Services and cloud conversion dependence.

The goal is to empower users to use their device’s often-neglected capabilities: hardware encoders, decoders, filters, local storage, and FFmpeg.

## Core principles

- Offline first.
- Local-first file handling.
- Privacy-respecting.
- De-Googled friendly.
- Hardware-aware.
- Transparent encoder selection and fallbacks.
- No silent quality degradation.
- Reusable infrastructure for other Android projects.

## Features

- Permanent cutting: segments are physically removed, not merely skipped.
- SponsorBlock category selection: all 11 categories supported; Sponsor checked by default.
- SponsorBlock API integration: segments pre-fetched by video ID.
- Readable segment preview using `h:mm:ss.ms` timestamps.
- Title and channel display via YouTube oEmbed.
- Three cut modes: Fast, HW-Accurate, and SW-Accurate.
- Audio-only support for `.m4a`, `.opus`, and `.mp3`.
- Background processing with foreground service and wake lock.
- Busy-state guard while a job is running.
- Cancel button to stop an active encode.
- De-Googled design: no Google Play Services, Firebase, or tracking.
- Smart YouTube ID memory for seven days.
- File browser for selecting or swapping media.
- Persistent settings for cut mode, categories, and output folder.
- Diagnostic log after each run.
- Container inference from ffprobe metadata.
- Performance diagnostics including charging/power state and processing-time equation.
- FFprobe-informed bitrate policy for Fast and HW modes.
- Conservative fallback profile if first accurate encode fails.

## How to use

If a job is already running, new share or direct intents are rejected:

```text
please wait for current job to conclude and try again...
```

### Method A: Share URL first

1. Share a YouTube URL from PipePipe or NewPipe to SponsorCut.
2. SponsorCut captures the video ID and fetches title and SponsorBlock segments.
3. Tap **Browse for video file** and select the downloaded file.
4. Tap **Process with this ID**.
5. Output is saved as `originalname_clean_<timestamp>.ext`.

### Method B: Share downloaded file

1. Share a video or audio file from a file manager to SponsorCut.
2. SponsorCut attempts to detect the YouTube ID from intent extras, clipboard, or seven-day memory.
3. Paste a URL or ID if needed.
4. Tap **Process with this ID**.

### Method C: Cold open

1. Open SponsorCut directly.
2. If a recent ID is remembered, it is pre-filled.
3. Browse for the media file.
4. Tap **Process with this ID**.

### Method D: Native intent for forks

```kotlin
val intent = Intent("com.sponsorcut.PROCESS_FILE").apply {
    putExtra("uri", localFile.uri.toString())
    putExtra("video_id", videoId)
    setPackage("com.sponsorcut")
}
startActivity(intent)
```

## Cut modes

| Mode        | Speed            | Accuracy             | Quality                                       | Notes                              |
|-------------|------------------|----------------------|-----------------------------------------------|------------------------------------|
| Fast        | Seconds          | Nearest keyframe     | Lossless                                      | Best for most use cases            |
| HW-Accurate | Moderate         | Exact frame boundary | Source-informed bitrate and safe pixel format | Uses MediaCodec hardware           |
| SW-Accurate | Moderate to slow | Exact frame boundary | Legacy resolution-based bitrate ladder        | Uses CPU `mpeg4`; always available |

Both accurate modes use a single-pass `filter_complex` approach:

- Multiple seeked inputs, one per keep range.
- `trim`, `setpts`, and `concat` in one FFmpeg command.
- Encoder initialized once.
- Difference is encoder: `mpeg4` CPU versus MediaCodec hardware.

Fast and HW modes use ffprobe-informed, clamped bitrate policy.

SW mode uses a deterministic legacy resolution-based bitrate ladder and does not use ffprobe bitrate inference.

HW-Accurate performance varies by device and power state. Some devices run faster while charging due to governor or thermal behavior.

The app recommends Fast mode for long videos over 30 minutes and audio-only files.

## SponsorBlock categories

All 11 SponsorBlock categories are available.

Only Sponsor is checked by default.

- Sponsor
- Self-promotion
- Interaction reminder
- Intro / intermission
- Outro / end cards
- Preview / recap
- Filler / tangent / joke
- Non-music section
- Highlight / POI
- Exclusive access
- Chapter markers

Selections persist in SharedPreferences.

## FFmpegKit backend

SponsorCut uses `ffmpeg-kit` packaged as a local Android Archive:

```text
app/libs/ffmpeg-kit.aar
```

This AAR must be present before building.

It is built by F-Droid through the following source repository:

```text
InfinityLoop1308/ffmpeg-kit
```

For local development, obtain `ffmpeg-kit.aar` from one of these sources:

- A PipePipe/NewPipe build environment.
- F-Droid build outputs.
- Building `InfinityLoop1308/ffmpeg-kit` directly.

Place the binary here:

```text
/Users/gsm/projects/sponsorcut/app/libs/ffmpeg-kit.aar
```

The F-Droid build flow uses an LTS FFmpegKit build with Android MediaCodec, zlib, GMP, and OpenSSL enabled, then copies the produced AAR into `libs/ffmpeg-kit.aar`. [256][259]

Typical build output location:

```text
ffmpeg-kit-src/prebuilt/bundle-android-aar-lts/ffmpeg-kit/ffmpeg-kit.aar
```

Copy it into the app:

```bash
mkdir -p app/libs
cp /path/to/ffmpeg-kit.aar app/libs/ffmpeg-kit.aar
```

SW-Accurate uses `mpeg4`, which is built into FFmpeg and does not require `libx264`.

HW-Accurate uses Android MediaCodec hardware encoders. Compatibility and quality vary by vendor and device.

### Audio encoding

Custom audio encoding offers AAC, FLAC, Opus, MP3, and Vorbis when available in
the installed FFmpegKit build. Opus and Vorbis use FFmpeg's native encoders
named `opus` and `vorbis` (not `libopus` or `libvorbis`). This build marks both
encoders experimental, so SponsorCut enables them with FFmpeg's `-strict -2`
option when re-encoding. Stream-copy jobs are unchanged.

The native Opus encoder accepts mono input. The native Vorbis encoder supports
stereo only, so SponsorCut requests stereo output with `-ac 2`; if a segmented
Vorbis job still fails, stereo conversion may need to be applied to each audio
filter branch before concatenation. AAC remains the broadly compatible
general-purpose choice, FLAC is lossless but can create much larger files, and
Opus is a good compressed-audio option. The selected audio container determines
the output muxer and extension.

## Repository layout

This is a partial known-source map.

Generate the authoritative full tree with:

```bash
cd /Users/gsm/projects/sponsorcut
find . \
  -path '*/.git/*' -prune -o \
  -path '*/.gradle/*' -prune -o \
  -path '*/build/*' -prune -o \
  -path '*/.idea/*' -prune -o \
  -type f -print | sort
```

Known structure:

```text
/Users/gsm/projects/sponsorcut/
├── .github/
│   └── workflows/
│       └── release.yml
├── .kotlin/
│   └── sessions/
├── app/
│   ├── libs/
│   │   └── ffmpeg-kit.aar
│   └── src/
│       └── main/
│           ├── assets/
│           │   └── ic_launcher.svg
│           ├── java/
│           │   └── com/
│           │       └── sponsorcut/
│           │           ├── core/
│           │           │   └── model/
│           │           │       └── JobModels.kt
│           │           ├── model/
│           │           │   └── SponsorSegment.kt
│           │           ├── DiagLog.kt
│           │           ├── DropdownTable.kt
│           │           ├── EncoderCatalog.kt
│           │           ├── FFProbeInspector.kt
│           │           ├── FfmpegEngine.kt
│           │           ├── FileResolver.kt
│           │           ├── MainActivity.kt
│           │           ├── PolicyModels.kt
│           │           ├── ProcessingService.kt
│           │           ├── SegmentParser.kt
│           │           ├── SponsorBlockClient.kt
│           │           ├── SponsorCutApp.kt
│           │           └── TranscodePolicy.kt
│           ├── res/
│           │   └── drawable/
│           │       └── ic_launcher.xml
│           └── AndroidManifest.xml
├── fastlane/
│   └── metadata/
│       └── android/
│           └── en-US/
│               ├── changelogs/
│               ├── images/
│               ├── full_description.txt
│               ├── short_description.txt
│               └── title.txt
├── gradle/
│   └── wrapper/
├── metadata/
│   └── com.sponsorcut/
│       └── en-US/
├── scripts/
│   └── build-ffmpeg-kit.sh
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── local.properties
├── com.sponsorcut.yml
├── sponsorcut-release.jks
├── ARCHITECTURE.md
├── ARCHITECTURAL_CHANGE.md
├── TRANSCODEPOLICY_ARCHITECTURE.md
├── TRANSCODEPOLICY_V2_SUMMARY.md
├── VISION.md
├── README.md
```

## Key source files

### MainActivity.kt

Primary UI and user-facing state.

Important areas:

- Quality dropdown.
- CRF visibility.
- CRF input.
- Encoder selection.
- Auto encoder resolution.
- H.264 and HEVC CRF behavior.

### TranscodePolicy.kt

Decides how user choices become transcoding behavior.

Important areas:

- CRF support.
- Bitrate fallback.
- Encoder selection.
- Audio policy.
- Video policy.
- FFmpeg argument construction.

### EncoderCatalog.kt

Defines encoder options.

### FFProbeInspector.kt

Inspects input media using FFprobe.

Current ffprobe command after patch:

```text
-v quiet -print_format json -show_streams -show_format -show_entries stream=index,codec_type,codec_name,width,height,r_frame_rate,bit_rate ${file.absolutePath}
```

### FfmpegEngine.kt

Executes FFmpeg work.

### ProcessingService.kt

Runs background processing.

## Current critical issues

### Telegram JSON ffprobe

Telegram-related media may expose implausibly low audio metadata, such as:

```text
AAC 44100Hz 5kbps
```

Do not assume the reported bitrate is reliable. When audio is re-encoded, a
missing or below-32-kbps probed bitrate uses a 128-kbps fallback, shown as
`128k - fallback`. The marker is based on the original inspected bitrate, and
an explicit user-selected bitrate is preserved. This protects against
inheriting implausibly low metadata; it does not diagnose why the probe reported
it. Audio stream copy does not use this re-encoding fallback.

### H.264 CRF absence

H.264 CRF does not appear in Quality dropdown when using H.264 hardware.

Likely cause:

`MainActivity` uses `selectedVideo?.name` to decide CRF support, while Auto mode resolves the actual encoder later as `h264_mediacodec`.

Required fix:

- Use resolved/effective encoder for CRF support.
- If Auto resolves to `h264_mediacodec`, CRF must appear.
- Inspect both `supportsCrf` blocks in `MainActivity`.
- Inspect `supportsCrf` logic in `TranscodePolicy`.
- Do not merely add `h264_mediacodec` to another set if lookup variable is wrong.

### HEVC CRF failure

HEVC CRF appears but is broken.

FFmpeg log:

```text
[hevc_mediacodec] Use 1 as the default MediaFormat i-frame-interval, please set gop_size properly (>= fps)
[amediacodec] java.lang.IllegalArgumentException
[hevc_mediacodec] MediaCodec configure failed, Generic error in an external library
Error initializing output stream: Error while opening encoder for output stream #0:0
```

Important:

Do not remove HEVC CRF from the UI.

Route HEVC CRF jobs to `libx265` if bundled.

Keep `hevc_mediacodec` for Bitrate jobs.

If `libx265` unavailable, show clear validation error and disable Start.

Do not silently downgrade CRF to bitrate.

### Telegram video bitrate

Reported input video bitrate is 14 kbps.

Requested 14 kbps target resulted in 1292 kbps output.

This may be a separate bitrate-policy or encoder-floor issue.

Investigate:

- How app derives video target bitrate.
- Whether it uses source bitrate as target.
- Whether hardware encoder imposes minimum bitrate.
- Whether app clamps or floors bitrate.
- Whether app logs requested versus actual bitrate.

## CRF policy

CRF must be a multiple-choice dropdown.

Use all integer CRF values 0 through 51 inclusive for x264/x265-style CRF.

Do not use free text.

Do not use curated subset unless user later explicitly requests.

When CRF selected:

- Show CRF value dropdown.
- Hide bitrate input.
- Route job to CRF-capable encoder.
- Do not pass CRF to encoder that cannot support it.
- Do not silently downgrade CRF to bitrate.

When Bitrate selected:

- Show bitrate input.
- Hide CRF value dropdown.
- Use existing bitrate path.

## Encoder policy

### H.264

Preferred behavior:

- If hardware H.264 supports true CQ/CRF, use it.
- If not, route CRF jobs to `libx264` if bundled.
- Clearly indicate software H.264 is used.
- Keep `h264_mediacodec` for Bitrate jobs.
- If `libx264` unavailable, disable Start with clear explanation.
- Do not silently downgrade CRF to bitrate.

### HEVC

Preferred behavior:

- Route CRF jobs to `libx265` if bundled.
- Keep `hevc_mediacodec` for Bitrate jobs.
- Clearly indicate software HEVC is used.
- If `libx265` unavailable, disable Start with clear explanation.
- Do not silently downgrade CRF to bitrate.
- Do not remove HEVC CRF from UI.

## Auto encoder resolution

Determine whether Auto resolution is available before transcoding begins.

If resolved encoder only computed inside `TranscodePolicy`, expose resolver so Quality dropdown updates immediately.

CRF dropdown decision must use resolved encoder, not `selectedVideo?.name` alone.

When CRF selected but resolved encoder cannot support it:

- Automatically route to CRF-capable software encoder when available.
- Clearly show actual encoder used.
- If no CRF-capable encoder available, disable Start with explanation.
- Never silently downgrade CRF to bitrate.

## Diagnostic greps

Run these before patching:

```bash
cd /Users/gsm/projects/sponsorcut
grep -RIn "supportsCrf" app/src/main/java
grep -RIn "crfInput" app/src/main/java
grep -RIn "qualityOptions" app/src/main/java
grep -RIn "bit_rate" app/src/main/java
grep -RIn "sample_rate" app/src/main/java
grep -RIn "audioBitrate" app/src/main/java
grep -RIn "libx264\|libx265" app/src/main/java
grep -RIn "h264_mediacodec\|hevc_mediacodec" app/src/main/java
grep -RIn "print_format\|show_streams\|show_format" app/src/main/java
```

## Build

Debug build:

```bash
cd /Users/gsm/projects/sponsorcut
./gradlew :app:assembleDebug
```

Release build:

```bash
cd /Users/gsm/projects/sponsorcut
./gradlew :app:assembleRelease
```

Release signing environment variables:

```text
KEYSTORE_PATH=/path/to/keystore.jks
KEYSTORE_PASS=...
```

## Non-negotiable rules

- Do not remove HEVC CRF from UI.
- Do not silently downgrade CRF to bitrate.
- Do not assume ffprobe command patch alone fixed Telegram issue.
- Do not treat 44100 Hz or 5 kbps as confirmed source facts.
- Do not confuse `format.bit_rate` with audio stream `bit_rate`.
- Do not assume `streams[0]` is audio.
- Do not silently use fallback values without logging reason.
- Do not use `selectedVideo?.name` alone if Auto resolves different encoder.
- Do not pass CRF to encoder that cannot support it.
- Do not use free-text CRF field.
- Always inspect actual Kotlin code before patching.
- Prefer small exact copy-paste patches based on real file contents.

## License

MIT.