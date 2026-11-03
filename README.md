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
- Uses typed spinner rows with stable option identities, original values, and display metadata so input-aware labels and ordering do not create duplicate choices.
- Restricts output controls to settings applicable to the selected processing mode, and locks interactive controls while a job is active.
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

Output spinner rows separate identity from presentation: each row keeps a stable ID and original policy value while the label and ordering can reflect the current input, fallback, or explicit selection. The effective input/fallback option is moved to the top and decorated rather than added as a duplicate. Video container choices retain canonical MP4, MKV, MOV, AVI, and WebM entries; incompatible options remain visible but disabled.

FAST is stream-copy/remux mode, not a re-encode mode. Codec, quality, bitrate, frame-rate, resolution, preset, and audio sample-rate settings are disabled while FAST is selected. A compatible container choice can remain available. While a processing job is active, output settings and other job inputs are disabled; cancellation remains available. Segmentless and segment-removal operations share execution-mode progress labels, with elapsed progress retained during single-pass processing.

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
.
├── README.md
├── ARCHITECTURE.md
├── TRANSCODEPOLICY_ARCHITECTURE.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   ├── wrapper/
│   └── gradle-daemon-jvm.properties
└── app/
    ├── build.gradle.kts
    ├── libs/ffmpeg-kit.aar
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── ic_launcher.svg
        │   ├── THIRD_PARTY_NOTICES.md
        │   └── third-party-licenses/
        ├── res/
        │   ├── drawable/ic_launcher.xml
        │   └── values/strings.xml
        └── java/com/sponsorcut/
            ├── MainActivity.kt
            ├── ProcessingService.kt
            ├── SponsorCutApp.kt
            ├── FFProbeInspector.kt
            ├── TranscodePolicy.kt
            ├── FfmpegEngine.kt
            ├── EncoderCatalog.kt
            ├── DropdownTable.kt
            ├── FileResolver.kt
            ├── SegmentParser.kt
            ├── PolicyModels.kt
            ├── DiagLog.kt
            ├── SponsorBlockClient.kt
            ├── MegaCutClient.kt
            ├── model/SponsorSegment.kt
            └── core/model/JobModels.kt
```

## Future 2.0 architecture direction

For a Kotlin 2.0 refresh, use Google's **Now in Android (NIA)** sample as an architecture reference alongside the official Android app-architecture guidance. The relevant scheme is unidirectional data flow: UI renders immutable `ViewModel` state, user actions enter as events, and state updates flow back through lifecycle-aware `StateFlow` collection. Use Kotlin coroutines with structured concurrency for asynchronous work, repositories for data access, and lifecycle-aware UI state rather than managing raw threads and spinner locks in the Activity. Keep a foreground service for long-running, user-visible media processing; it can own the job while coroutine-based components manage cancellable work.

NIA is a sample/reference application rather than a drop-in SponsorCut template. Select a current Android Studio Kotlin template matching the chosen UI toolkit, then apply those architecture patterns incrementally. This is a future direction; the current app uses a Views-based `MainActivity` and foreground `ProcessingService`.

## License

MIT.
