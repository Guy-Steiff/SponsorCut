## 11. CQ and Hardware Quality Control

### 11.1 Current Status

CQ is not operational.

Attempting CQ produced an FFmpeg error.

The policy and argument-generation code exist, but the feature is not exposed as a working user-facing path.

It will be retried after an FFmpegKitNext upgrade.

### 11.2 Intended CQ Behavior

For:

    h264_mediacodec
    hevc_mediacodec

The intended arguments are:

    -c:v <encoder>
    -bitrate_mode cq
    -global_quality <cq>
    -g <gop>

The GOP size is computed as:

    requested fps rounded to integer, minimum 30

If no requested fps:

    GOP = 120

The GOP guard exists because MediaCodec derives i-frame interval from GOP size and FPS. If the rounded interval becomes zero, encoder configuration can fail.

### 11.3 CRF Availability

CRF is not available on MediaCodec H.264 or HEVC encoders.

CRF is available for:

    libx264
    libx265

The policy intentionally restricts CRF to those encoders.

### 11.4 HEVC CQ Attempt

An attempt was made to implement CQ for HEVC MediaCodec.

FFmpeg rejected it.

Therefore:

- H.264 MediaCodec CQ: code exists, not operational.
- HEVC MediaCodec CQ: code exists, not operational.
- CRF on MediaCodec: unsupported.
- Current reliable Accurate quality control: bitrate-based.

This is a known FFmpegKit limitation, not a SponsorCut policy-design failure.

