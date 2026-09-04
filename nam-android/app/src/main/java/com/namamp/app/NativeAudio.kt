package com.namamp.app

// All native calls live here rather than on MainActivity. The actual audio
// engine is a process-level singleton in C++, not owned by any Activity —
// AudioService needs to reach it too (specifically to stop audio when the
// user swipes the app away entirely, via onTaskRemoved()).
object NativeAudio {
    // Chain positions.
    const val POSITION_DRIVE = 0
    const val POSITION_AMP = 1
    const val POSITION_MOD = 2

    var loadError: String? = null
    init {
        try {
            System.loadLibrary("namamp_native")
        } catch (e: Throwable) {
            // UnsatisfiedLinkError is an Error, not an Exception — must
            // catch Throwable to actually catch it.
            loadError = e.toString()
        }
    }

    // position: POSITION_DRIVE/AMP/MOD. fileSlot: 0-2, one of 3 loadable
    // files remembered per position.
    external fun nativeLoadModel(modelFilePath: String, position: Int, fileSlot: Int): String
    external fun nativeSetActiveFile(position: Int, fileSlot: Int)
    external fun nativeStartAudio(): String
    external fun nativeStopAudio()

    // Buffer logger/tuner — wholeBursts: positive nudges the buffer larger
    // (more latency, more headroom against glitches), negative nudges it
    // smaller (lower latency, more risk of xruns). Only meaningful while
    // audio is running.
    external fun nativeGetBufferStatus(): String
    external fun nativeAdjustBufferSize(wholeBursts: Int): String

    external fun nativeSetDriveGain(db: Float)
    external fun nativeSetDriveLevel(db: Float)
    external fun nativeSetDriveTone(db: Float)

    external fun nativeSetAmpGain(db: Float)
    external fun nativeSetAmpBass(db: Float)
    external fun nativeSetAmpMid(db: Float)
    external fun nativeSetAmpTreble(db: Float)
    external fun nativeSetAmpVolume(db: Float)

    // mixPercent: 0-100.
    external fun nativeSetModMix(mixPercent: Float)
    external fun nativeSetModLevel(db: Float)

    // Built-in spring reverb — dwellPercent: 0-100.
    external fun nativeSetReverbDwell(dwellPercent: Float)
    external fun nativeSetReverbMix(mixPercent: Float)
    external fun nativeSetReverbLevel(db: Float)

    // Bypass toggles — each stage's whole block (model + its own knobs) is
    // skipped when disabled.
    external fun nativeSetDriveEnabled(enabled: Boolean)
    external fun nativeSetAmpEnabled(enabled: Boolean)
    external fun nativeSetModEnabled(enabled: Boolean)
    external fun nativeSetReverbEnabled(enabled: Boolean)
    // 0 = Spring, 1 = Plate, 2 = Hall.
    external fun nativeSetReverbType(type: Int)

    // Guitar tuner — taps the raw input independently of the effects
    // chain; getTunerFrequency returns 0f when no signal is detected.
    external fun nativeSetTunerEnabled(enabled: Boolean)
    external fun nativeGetTunerFrequency(): Float

    external fun nativeSetInputDevice(deviceId: Int)
    external fun nativeSetOutputDevice(deviceId: Int)

    // Multi-channel input routing (e.g. selecting Channel 2 on an
    // interface with separate Hi-Z/Line inputs). channelIndex is 0-based.
    external fun nativeSetInputChannelCount(channelCount: Int)
    external fun nativeSetSelectedInputChannel(channelIndex: Int)
}
