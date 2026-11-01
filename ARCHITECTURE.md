# SponsorCut Architecture

Last reviewed: 2026-11-01

This document describes the current implementation. The source code is authoritative where behavior changes.

## System flow

```text
Media picker / share or view intent
  -> MainActivity
       -> inspect selected media
       -> select segment source and output preferences
       -> request a TranscodePolicy preview for Auto controls
       -> serialize requested preferences into the processing intent
  -> ProcessingService
       -> copy input URI into app cache
       -> FFProbeInspector.inspect()
       -> TranscodePolicy.plan()
       -> FfmpegEngine.process()
       -> FileResolver writes final output
       -> completion summary and notification
```

`MainActivity` owns user interaction and requested choices. It does not own Auto bitrate or frame-rate policy. A displayed Auto value is presentation only; nullable request fields remain null through `OutputPreferences` JSON. `ProcessingService` re-inspects the cached input and constructs the execution plan from those preferences and the same policy.

## Components

### MainActivity

`MainActivity` handles Android picker/share/view/direct-file intents, media selection, mode and stream selection, output options, segment-source selection, segment editing, and the processing intent.

After inspection and whenever relevant output controls change, it asks `TranscodePolicy.resolveForUi()` for a preview. The preview uses the same policy planning path as processing and supplies resolved values and provenance for Auto controls. Explicit selections remain represented in `OutputPreferences`; display text is not used as an Auto request.

The activity supports SponsorBlock-backed segments and local TXT/JSON segment files. Users can select categories and individual segments, add or edit entries, and export the current segment list. A saved output-folder URI is used when available.

### ProcessingService

The non-exported foreground service receives `EXTRA_OUTPUT_PREFERENCES_JSON`, input URI, execution mode, stream selection, segment source, and selected segment indexes. It caches the input, probes it, builds the `ProcessingPlan`, resolves segments, runs the engine, copies the result to the destination, and reports completion or failure.

The service also manages progress notifications, cancellation, elapsed-time and power diagnostics, diagnostic logs, and cleanup of temporary artifacts. The service persists terminal status/log information so the activity can restore a completed result.

### FFProbeInspector

`FFProbeInspector` is the canonical source of input-media metadata. It calls FFprobeKit's structured media-information API and a JSON probe path, then merges the results into `VideoInfo`. The JSON query includes stream and format fields, including both `r_frame_rate` and `avg_frame_rate`. Attached-picture streams are excluded from primary video selection.

`VideoInfo` retains stream and container bitrates as integer bps, dimensions, codecs, sample rate, duration, format, stream indexes, TS encapsulation, and frame-rate fields. Rational frame rates are represented as numerator/denominator pairs. The legacy `fps` numeric value remains available to other policy calculations.

The API supplies average frame rate when it is available. During merge, the API's preferred rate wins when present; otherwise the JSON path prefers nominal `r_frame_rate`, falling back to average rate. Both rational values remain available when the JSON path supplies them. The input summary describes probed media, not output policy. For distinct nominal and average values it displays both as decimal rates, without exposing rational notation.

Probe failure returns a minimal `VideoInfo` with unknown codecs and missing metadata. Policy is responsible for handling missing values or reporting unsupported input conditions.

### TranscodePolicy

`TranscodePolicy` interprets canonical input metadata, requested output preferences, execution mode, stream selection, and available encoders. Its `OutputResolution` separates requested values from effective values and records provenance. `ProcessingPlan` carries that resolution together with the command arguments and engine behavior.

`resolveForUi()` obtains its result through the policy planning path. Processing calls `plan()` with the same kinds of inputs; both therefore use the same policy rules. `OutputPreferences` contains requested choices only. Auto bitrate and frame rate remain null, including when a control displays an input-derived or fallback value.

#### Video bitrate

- Fast mode copies streams and does not encode a video bitrate.
- For video re-encoding in bitrate mode, an explicit `videoBitrate` takes precedence.
- Auto accepts the inspected stream bitrate only when it is at least 64,000 bps.
- Otherwise policy estimates video bitrate as container bitrate minus the effective audio bitrate, and accepts that estimate only at or above 64,000 bps.
- If neither source is plausible, the resolution fallback is 1,500,000 bps at 1920x1080 or larger, 1,000,000 bps at 1280x720 or larger, 600,000 bps at 854x480 or larger, and 400,000 bps below that.
- The estimate and fallback are policy decisions. In particular, a stream value and container-minus-audio estimate both below 64,000 bps legitimately lead to the resolution fallback.
- Bitrates remain integer bps through preference parsing, policy, and FFmpeg `-b:v` arguments.

When CRF is active for a supported software encoder, bitrate resolution is not used for the normal encoder arguments. Hardware CQ and encoder availability are resolved by policy and the encoder catalog.

#### Audio bitrate

- Stream-copy audio has no re-encoding bitrate target and does not use the policy fallback.
- For Auto audio re-encoding, policy uses the inspected source bitrate when it is at least 32,000 bps; if absent or below threshold, it selects the intentional 128,000 bps policy fallback.
- An explicit value at or above the threshold is used as requested. An explicit value below the threshold follows the existing source-policy path and is identified separately in provenance.
- The resolution reports `AUTO_SOURCE`, `POLICY_FALLBACK`, explicit, below-minimum, or stream-copy provenance as applicable.
- FFmpeg bitrate arguments use exact integer bps.

The engine's AAC recovery profile (`-c:a aac -b:a 128k`) is distinct from policy resolution. The completion summary reports it separately when the engine actually uses that profile.

#### Frame rate

Auto leaves the requested frame rate unset. The policy's preview reports the input-derived display rate and that processing should inherit source timing; it does not force `-r` for Auto. An explicit rate is compared with the preferred source rate using the existing 0.01 difference threshold. Policy emits `-r` only when the explicit request differs beyond that tolerance or source comparison is unavailable.

For VFR-like metadata where nominal and average rates differ, the display presents both values (average and nominal). The rational metadata remains internal; display formatting does not determine the policy comparison or FFmpeg behavior.

#### Encoder and container selection

Encoder discovery is performed by `EncoderCatalog`. Policy chooses compatible encoders based on mode, input codec family, custom selections, and encoder availability. Container selection follows compatibility rules and input container metadata; transcoded MPEG-TS inputs use MP4 because direct TS output transcoding is unsupported by the current FFmpegKit path.

### EncoderCatalog

`EncoderCatalog` discovers available FFmpeg encoders from the bundled FFmpegKit build and maps names to codec family, media type, hardware/software classification, and availability. Both UI choices and policy resolution use this catalog.

### FfmpegEngine

`FfmpegEngine` executes the plan using FFmpegKit. It supports stream-selection jobs without segment removal, segment-based processing, and the planned single-pass filter path where selected by policy. It builds FFmpeg commands from plan arguments, reports progress, handles cancellation and retry paths, and returns timing and recovery-profile information to the service.

The engine adds compatibility options for encoders that require them. If a configured recovery profile succeeds, the result records whether AAC recovery audio arguments were used so the completion report can distinguish recovery from the planned target.

### FileResolver

`FileResolver` converts selected content URIs into cached local files and writes output to an appropriate destination. Destinations may be a user-selected SAF tree, a writable source-adjacent location, or a platform storage fallback. Temporary outputs are deleted on failure or cancellation.

### SegmentParser and segment sources

`SegmentParser` accepts local JSON arrays containing `category` and a two-value `segment` array, or TXT ranges. It validates ranges against media duration, normalizes duplicates and overlaps, and returns warnings for normalization. `MainActivity` also permits manual segment editing and TXT/JSON export.

SponsorBlock access is implemented in `SponsorBlockClient`. `MegaCutClient` implements media-source parsing and segment-set lookup. These lookups require network access; media encoding and cutting are local.

## Preferences and intent contract

`OutputPreferences` represents requested settings, not resolved output values. It is serialized as JSON in `EXTRA_OUTPUT_PREFERENCES_JSON` and parsed by `ProcessingService`.

Nullable bitrate fields distinguish Auto from explicit targets:

- `videoBitrate = null`: Auto video bitrate.
- `audioBitrate = null`: Auto audio bitrate.
- A non-null value: explicit requested bitrate in bps.
- `videoFrameRate = null`: Auto/input timing.
- A non-null frame-rate string: explicit requested rate.

Resolved bitrates, provenance, and effective frame-rate behavior are calculated by `TranscodePolicy`; they are not substituted into the user's serialized request.

## Segment processing behavior

The engine derives keep ranges from sorted removal ranges. Overlaps do not produce duplicate keep ranges. Fast mode favors stream copy; accurate modes use policy-selected encoders and filtering. The engine can retry failed segments with slower seeking or conservative fallback arguments where the plan permits. Hardware-accurate failures are surfaced rather than silently switching modes.

When segment removal is disabled, the engine handles stream selection/transcoding without requiring segment ranges. Audio-only and video-only extraction use the same policy and plan model, with the unused stream dropped.

## Diagnostics and completion reporting

`DiagLog` records probe, policy, engine, service, and run-summary information. The service reports the media summary from input metadata, the plan rationale, output size information, and audio bitrate provenance. Input media rates must not be interpreted as resolved output targets.

Audio completion reporting distinguishes:

- Auto using plausible source bitrate.
- Auto policy fallback to 128,000 bps.
- Explicit target.
- Explicit below-minimum request routed through source policy.
- Stream copy or no encoder bitrate.
- AAC recovery profile, only if actually used.

## Architectural invariants

1. `FFProbeInspector` reports input facts; it does not choose output targets.
2. `TranscodePolicy` is authoritative for input-aware Auto decisions.
3. UI previews and processing plans use the same policy resolution path.
4. Auto display text is not serialized as an explicit request.
5. Requested preferences, effective settings, and provenance remain distinct.
6. Audio's 32,000 bps plausibility threshold and 128,000 bps Auto re-encode fallback are policy behavior; stream copy is unaffected.
7. Video's 64,000 bps threshold, container-minus-audio estimate, and resolution fallback remain policy behavior.
8. Rational frame-rate metadata is retained internally; display text does not decide timing behavior.
9. Input summaries describe probed media, while output controls describe resolved Auto values or explicit requests.
10. The engine executes the plan; it does not silently reinterpret Auto policy.
