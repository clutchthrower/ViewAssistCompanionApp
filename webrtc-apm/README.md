# WebRTC Audio Processing Module for View Assist

A standalone Android library that exposes the WebRTC Audio Processing Module (APM) via JNI, enabling high-quality audio processing without the full WebRTC networking stack.

Forked from [brucekayle/webrtc-apm](https://github.com/brucekayle/webrtc-apm) and modernized for the [View Assist](https://github.com/dinki/View-Assist) project.

## Features

- **Acoustic Echo Cancellation (AEC)** — Removes speaker feedback from the microphone signal
- **Noise Suppression (NS)** — Filters out background noise, including transient suppression (keyboard clicks, tapping)
- **Automatic Gain Control (AGC)** — Normalizes microphone volume levels
- **High-Pass Filter (HPF)** — Removes DC offset and low-frequency rumble
- **Voice Activity Detection (VAD)** — Detects the presence of speech

## Audio Format

- **Sample rate:** 16kHz
- **Bit depth:** 16-bit PCM
- **Channels:** Mono
- **Frame size:** 160 samples (10ms)

## Usage

```kotlin
import com.viewassist.webrtc.WebRtcApm

val apm = WebRtcApm(
    /* aecExtendFilter = */ true,
    /* speechIntelligibility = */ false,
    /* delayAgnostic = */ true,
    /* beamforming = */ false,
    /* nextGenAec = */ true,
    /* experimentalNs = */ true,
    /* experimentalAgc = */ false
)

// Configure processing pipeline
apm.setHighPassFilter(true)
apm.setAecEnabled(true)
apm.setAecSuppressionLevel(WebRtcApm.AecSuppressionLevel.MODERATE)
apm.setNsEnabled(true)
apm.setNsLevel(WebRtcApm.NsLevel.HIGH)
apm.setAgcEnabled(true)
apm.setAgcMode(WebRtcApm.AgcMode.ADAPTIVE_DIGITAL)
apm.setAgcLimiterEnabled(true)

// Process audio in 160-sample (10ms) frames
for (offset in 0 until buffer.size step 160) {
    apm.processCaptureStream(buffer, offset)
}

// Clean up
apm.close()
```

## Integration

This library is included as a composite build in the ViewAssistCompanionApp via `settings.gradle.kts`:

```kotlin
includeBuild("../webrtc-apm")
```

And referenced as a dependency in `app/build.gradle.kts`:

```kotlin
implementation("com.viewassist.webrtc:apm")
```

## Architecture

```text
com.bk.webrtc.Apm          — Internal JNI bridge (must retain original package for .so compatibility)
com.viewassist.webrtc.WebRtcApm  — Clean public API with Javadoc and AutoCloseable support
libwebrtc_apms.so          — Precompiled native WebRTC APM (arm64-v8a, armeabi-v7a)
```

## Attribution

This project is a fork of [brucekayle/webrtc-apm](https://github.com/brucekayle/webrtc-apm), which provides a standalone JNI wrapper around the [Google WebRTC](https://webrtc.org/) Audio Processing Module.

The original work by [BruceKayle](https://github.com/brucekayle) extracted the APM from the full WebRTC stack and created the native JNI bridge (`com.bk.webrtc.Apm`) and precompiled `.so` libraries that this project builds upon.

### Changes from the original

- Modernized build system (Gradle 8.14.3, AGP 8.13.2, compileSdk 35, Java 17)
- Added clean public API wrapper (`com.viewassist.webrtc.WebRtcApm`) with full Javadoc and `AutoCloseable` support
- Repackaged under `com.viewassist.webrtc` namespace
- Removed demo application module
- Integrated as a composite build for the View Assist Companion App

## License

The WebRTC source code is licensed by Google under a BSD-style license.  
See [webrtc.org/license](https://webrtc.org/support/license) for details.

The original JNI wrapper by [BruceKayle](https://github.com/brucekayle/webrtc-apm) is used under the terms of its original repository.