package com.viewassist.webrtc;

import android.util.Log;

/**
 * WebRTC Audio Processing Module wrapper for View Assist.
 *
 * This is a clean public API that delegates to the native JNI bridge
 * in com.bk.webrtc.Apm. The native .so libraries have JNI function names
 * baked to the com.bk.webrtc package path, so the internal bridge class
 * must retain that package name. This wrapper provides a stable, modern
 * API surface under the com.viewassist.webrtc namespace.
 *
 * Native library: libwebrtc_apms.so
 * Audio format: 16kHz, 16-bit PCM, Mono, 10ms frames (160 samples)
 */
public class WebRtcApm implements AutoCloseable {

    private static final String TAG = "WebRtcApm";

    private final com.bk.webrtc.Apm delegate;
    private boolean closed = false;

    // Re-export enums with clear names
    public enum AecSuppressionLevel {
        LOW, MODERATE, HIGH;

        com.bk.webrtc.Apm.AEC_SuppressionLevel toInternal() {
            switch (this) {
                case LOW: return com.bk.webrtc.Apm.AEC_SuppressionLevel.LowSuppression;
                case MODERATE: return com.bk.webrtc.Apm.AEC_SuppressionLevel.ModerateSuppression;
                case HIGH: return com.bk.webrtc.Apm.AEC_SuppressionLevel.HighSuppression;
                default: return com.bk.webrtc.Apm.AEC_SuppressionLevel.ModerateSuppression;
            }
        }
    }

    public enum NsLevel {
        LOW, MODERATE, HIGH, VERY_HIGH;

        com.bk.webrtc.Apm.NS_Level toInternal() {
            switch (this) {
                case LOW: return com.bk.webrtc.Apm.NS_Level.Low;
                case MODERATE: return com.bk.webrtc.Apm.NS_Level.Moderate;
                case HIGH: return com.bk.webrtc.Apm.NS_Level.High;
                case VERY_HIGH: return com.bk.webrtc.Apm.NS_Level.VeryHigh;
                default: return com.bk.webrtc.Apm.NS_Level.High;
            }
        }
    }

    public enum AgcMode {
        ADAPTIVE_ANALOG, ADAPTIVE_DIGITAL, FIXED_DIGITAL;

        com.bk.webrtc.Apm.AGC_Mode toInternal() {
            switch (this) {
                case ADAPTIVE_ANALOG: return com.bk.webrtc.Apm.AGC_Mode.AdaptiveAnalog;
                case ADAPTIVE_DIGITAL: return com.bk.webrtc.Apm.AGC_Mode.AdaptiveDigital;
                case FIXED_DIGITAL: return com.bk.webrtc.Apm.AGC_Mode.FixedDigital;
                default: return com.bk.webrtc.Apm.AGC_Mode.AdaptiveDigital;
            }
        }
    }

    public enum AecmRoutingMode {
        QUIET_EARPIECE_OR_HEADSET, EARPIECE, LOUD_EARPIECE, SPEAKERPHONE, LOUD_SPEAKERPHONE;

        com.bk.webrtc.Apm.AECM_RoutingMode toInternal() {
            switch (this) {
                case QUIET_EARPIECE_OR_HEADSET: return com.bk.webrtc.Apm.AECM_RoutingMode.QuietEarpieceOrHeadset;
                case EARPIECE: return com.bk.webrtc.Apm.AECM_RoutingMode.Earpiece;
                case LOUD_EARPIECE: return com.bk.webrtc.Apm.AECM_RoutingMode.LoudEarpiece;
                case SPEAKERPHONE: return com.bk.webrtc.Apm.AECM_RoutingMode.Speakerphone;
                case LOUD_SPEAKERPHONE: return com.bk.webrtc.Apm.AECM_RoutingMode.LoudSpeakerphone;
                default: return com.bk.webrtc.Apm.AECM_RoutingMode.Speakerphone;
            }
        }
    }

    public enum VadLikelihood {
        VERY_LOW, LOW, MODERATE, HIGH;

        com.bk.webrtc.Apm.VAD_Likelihood toInternal() {
            switch (this) {
                case VERY_LOW: return com.bk.webrtc.Apm.VAD_Likelihood.VeryLowLikelihood;
                case LOW: return com.bk.webrtc.Apm.VAD_Likelihood.LowLikelihood;
                case MODERATE: return com.bk.webrtc.Apm.VAD_Likelihood.ModerateLikelihood;
                case HIGH: return com.bk.webrtc.Apm.VAD_Likelihood.HighLikelihood;
                default: return com.bk.webrtc.Apm.VAD_Likelihood.ModerateLikelihood;
            }
        }
    }

    /**
     * Create a new WebRTC Audio Processing Module instance.
     *
     * @param aecExtendFilter         Enable extended AEC filter for better tail-length handling
     * @param speechIntelligibility    Enable speech intelligibility enhancement (modifies speaker output)
     * @param delayAgnostic            Enable delay-agnostic AEC (tolerates unknown system delays)
     * @param beamforming              Enable beamforming (requires multi-mic array, mono = false)
     * @param nextGenAec              Enable next-generation AEC algorithm
     * @param experimentalNs           Enable experimental NS with transient suppression
     * @param experimentalAgc          Enable experimental AGC (may cause volume pumping)
     * @throws Exception if native APM creation fails
     */
    public WebRtcApm(
            boolean aecExtendFilter,
            boolean speechIntelligibility,
            boolean delayAgnostic,
            boolean beamforming,
            boolean nextGenAec,
            boolean experimentalNs,
            boolean experimentalAgc
    ) throws Exception {
        delegate = new com.bk.webrtc.Apm(
                aecExtendFilter,
                speechIntelligibility,
                delayAgnostic,
                beamforming,
                nextGenAec,
                experimentalNs,
                experimentalAgc
        );
        Log.d(TAG, "WebRtcApm initialized successfully");
    }

    // --- High-Pass Filter ---

    /** Enable/disable the built-in high-pass filter (removes DC offset and low-frequency rumble). */
    public int setHighPassFilter(boolean enable) {
        return delegate.HighPassFilter(enable);
    }

    // --- Acoustic Echo Cancellation (Desktop/Full) ---

    /** Enable/disable full AEC. */
    public int setAecEnabled(boolean enable) {
        return delegate.AEC(enable);
    }

    /** Set AEC suppression level. */
    public int setAecSuppressionLevel(AecSuppressionLevel level) {
        return delegate.AECSetSuppressionLevel(level.toInternal());
    }

    /** Enable/disable clock drift compensation for AEC. */
    public int setAecClockDriftCompensation(boolean enable) {
        return delegate.AECClockDriftCompensation(enable);
    }

    // --- Acoustic Echo Cancellation Mobile (Lightweight) ---

    /** Enable/disable mobile AEC (lightweight, for embedded devices). */
    public int setAecMobileEnabled(boolean enable) {
        return delegate.AECM(enable);
    }

    /** Set mobile AEC routing mode / suppression level. */
    public int setAecMobileRoutingMode(AecmRoutingMode mode) {
        return delegate.AECMSetSuppressionLevel(mode.toInternal());
    }

    // --- Noise Suppression ---

    /** Enable/disable noise suppression. */
    public int setNsEnabled(boolean enable) {
        return delegate.NS(enable);
    }

    /** Set noise suppression aggressiveness level. */
    public int setNsLevel(NsLevel level) {
        return delegate.NSSetLevel(level.toInternal());
    }

    // --- Automatic Gain Control ---

    /** Enable/disable AGC. */
    public int setAgcEnabled(boolean enable) {
        return delegate.AGC(enable);
    }

    /** Set AGC mode (adaptive analog, adaptive digital, or fixed digital). */
    public int setAgcMode(AgcMode mode) {
        return delegate.AGCSetMode(mode.toInternal());
    }

    /**
     * Set target peak level in dBFs (decibels from digital full-scale).
     * Uses positive convention: 3 means -3 dBFs. Range: [0, 31].
     */
    public int setAgcTargetLevelDbfs(int level) {
        return delegate.AGCSetTargetLevelDbfs(level);
    }

    /**
     * Set maximum compression gain in dB. Range: [0, 90].
     * Higher = more compression. 0 = no compression.
     */
    public int setAgcCompressionGainDb(int gain) {
        return delegate.AGCSetcompressionGainDb(gain);
    }

    /** Enable/disable hard limiter at the target level. */
    public int setAgcLimiterEnabled(boolean enable) {
        return delegate.AGCEnableLimiter(enable);
    }

    /** Set analog level limits for adaptive analog mode. Range: [0, 65535]. */
    public int setAgcAnalogLevelLimits(int minimum, int maximum) {
        return delegate.AGCSetAnalogLevelLimits(minimum, maximum);
    }

    /** Set current analog level before ProcessCaptureStream (for adaptive analog mode). */
    public int setAgcStreamAnalogLevel(int level) {
        return delegate.AGCSetStreamAnalogLevel(level);
    }

    /** Get recommended analog level after ProcessCaptureStream (for adaptive analog mode). */
    public int getAgcStreamAnalogLevel() {
        return delegate.AGCStreamAnalogLevel();
    }

    // --- Voice Activity Detection ---

    /** Enable/disable VAD. */
    public int setVadEnabled(boolean enable) {
        return delegate.VAD(enable);
    }

    /** Set VAD likelihood threshold. */
    public int setVadLikelihood(VadLikelihood likelihood) {
        return delegate.VADSetLikeHood(likelihood.toInternal());
    }

    /** Check if the last processed frame contained voice. */
    public boolean hasVoice() {
        return delegate.VADHasVoice();
    }

    // --- Stream Processing ---

    /**
     * Process captured audio (near-end / microphone).
     * Applies AEC, NS, AGC, HPF in-place on the buffer.
     *
     * @param samples  Audio samples (16-bit PCM, mono, 16kHz)
     * @param offset   Offset into the array (must have 160 samples from offset)
     * @return 0 on success
     */
    public int processCaptureStream(short[] samples, int offset) {
        return delegate.ProcessCaptureStream(samples, offset);
    }

    /**
     * Process render audio (far-end / speaker output).
     * Required for AEC to have an echo reference signal.
     *
     * @param samples  Audio samples (16-bit PCM, mono, 16kHz)
     * @param offset   Offset into the array (must have 160 samples from offset)
     * @return 0 on success
     */
    public int processRenderStream(short[] samples, int offset) {
        return delegate.ProcessRenderStream(samples, offset);
    }

    /** Set the estimated delay between render and capture streams in milliseconds. */
    public int setStreamDelay(int delayMs) {
        return delegate.SetStreamDelay(delayMs);
    }

    // --- Lifecycle ---

    @Override
    public void close() {
        if (!closed) {
            delegate.close();
            closed = true;
            Log.d(TAG, "WebRtcApm closed");
        }
    }
}
