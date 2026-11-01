# SponsorCut

SponsorCut is an Android app for processing local media with FFmpegKit. It can remove selected SponsorBlock segments, import or edit segment lists, and extract audio or video. Media processing runs locally; source lookup and segment retrieval may use network services.

The app is designed to work without Google Play Services. It does not upload media for conversion.

## Features

- Accepts media from the Android file picker, file-manager intents, and supported share intents.
- Retrieves SponsorBlock segments and supports local TXT or JSON segment files.
- Lets users select categories and individual segments, add or edit segments, and export segment lists.
- Supports Fast stream-copy, SW-Accurate, and HW-Accurate processing modes.
- Supports processing both streams, audio-only extraction, and video-only extraction.
- Chooses compatible encoders, containers, and output arguments through `TranscodePolicy`.
- Inspects input metadata with FFprobeKit and reports processing diagnostics.
- Runs jobs in a foreground service with progress, cancellation, and output-folder selection.

## Processing overview

```text
MainActivity
  -> OutputPreferences (Auto choices remain unset)
  -> ProcessingService
       -> cache selected input
       -> FFProbeInspector -> canonical input metadata
       -> TranscodePolicy -> resolved output settings and ProcessingPlan
       -> FfmpegEngine -> FFmpegKit
       -> FileResolver -> destination
```

`TranscodePolicy` owns input-aware Auto decisions. The UI can request the policy's output-resolution preview for display; processing builds its plan through the same policy path. Displayed Auto values are not serialized as explicit user requests.

Video and audio bitrates are represented internally in bits per second. Auto audio re-encoding uses a plausible probed source bitrate at or above 32,000 bps; otherwise policy selects 128,000 bps. This fallback does not apply to stream-copy audio. Explicit audio values remain distinct, subject to the existing below-minimum policy behavior. Policy fallback provenance is separate from the AAC recovery profile used after an encode failure.

Frame-rate metadata retains rational nominal (`r_frame_rate`) and average (`avg_frame_rate`) values where available. Auto preserves input timing; explicit frame-rate requests are compared with the source using the policy's existing 0.01 tolerance.

For detailed behavior and constraints, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Build requirements

- Android SDK and Java 21.
- The project-local FFmpegKit archive at `app/libs/ffmpeg-kit.aar`.

The FFmpegKit archive is supplied by the project's packaging workflow or by the local development environment; it is not downloaded from Maven by this project. The app also declares the `smart-exception-java` dependency required by the archive.

Build a release APK with:

```bash
./gradlew assembleRelease
```

Release signing uses `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, and `KEY_PASS` when configured. Without `KEYSTORE_PATH`, the Gradle configuration uses the local Android debug keystore for signing.

## Repository map

```text
app/src/main/java/com/sponsorcut/
  MainActivity.kt          UI, user intent, segment editing and export
  ProcessingService.kt     foreground job orchestration and completion reporting
  FFProbeInspector.kt      input-media metadata inspection and merging
  TranscodePolicy.kt       output resolution, encoder policy and FFmpeg arguments
  FfmpegEngine.kt          FFmpegKit execution, segment processing and retries
  EncoderCatalog.kt        available encoder discovery and codec-family mapping
  FileResolver.kt          input caching and output destinations
  SegmentParser.kt         local TXT/JSON segment parsing and normalization
  PolicyModels.kt          output preferences, JSON and bitrate formatting
  SponsorBlockClient.kt    SponsorBlock API access
  MegaCutClient.kt         media-source and segment-set lookup
  core/model/JobModels.kt  job, stream-selection and execution-mode models
```

## License

MIT.
