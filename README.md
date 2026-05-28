# SponsorCut

An Android app that removes SponsorBlock-marked sponsor segments from locally downloaded YouTube videos using FFmpeg.

## Features

- Share a downloaded video from PipePipe or NewPipe → SponsorCut removes sponsor segments automatically
- Uses [SponsorBlock](https://sponsor.ajay.app/) API to fetch sponsor segment timestamps
- FFmpeg-powered cutting (fast stream-copy or frame-accurate mode)
- No Google Play Services required — works on de-Googled phones (CalyxOS, GrapheneOS, DivestOS, etc.)
- Background processing with notification progress
- Output saved alongside the source file

## How to Use

1. In PipePipe/NewPipe, share the YouTube video URL to SponsorCut first (to cache the video ID)
2. Then share the downloaded video file to SponsorCut
3. SponsorCut fetches sponsor segments and cuts them out
4. Output saved as `originalname_clean_<timestamp>.ext` in the same folder

## Building

### Prerequisites

- Android Studio or JDK 17+
- Gradle 9.x
- The FFmpeg AAR library (not included in repo due to size — see below)

### FFmpeg AAR Setup

The app requires `mobile-ffmpeg-full-gpl-4.4.LTS.aar` placed at:

```
app/libs/mobile-ffmpeg-full-gpl-4.4.LTS.aar
```

Download it from the [mobile-ffmpeg releases](https://github.com/tanersener/mobile-ffmpeg/releases/tag/v4.4.LTS) — get the `mobile-ffmpeg-full-gpl-4.4.LTS.aar` asset.

### Build APK

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Integration with PipePipe / NewPipe

SponsorCut registers the intent action `com.sponsorcut.PROCESS_FILE` so PipePipe/NewPipe can send both the local file URI and the YouTube video ID in one step:

```kotlin
val intent = Intent("com.sponsorcut.PROCESS_FILE").apply {
    putExtra("uri", localFile.uri.toString())
    putExtra("video_id", videoId)
    setPackage("com.sponsorcut")
}
startActivity(intent)
```

## License

MIT

---

*By: Guy Steiff*

