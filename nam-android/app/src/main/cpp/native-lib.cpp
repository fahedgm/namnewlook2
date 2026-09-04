// The real audio engine. Fixed three-stage chain, always in series:
// Drive -> Amp -> Modulation -> speaker/headphone out. Each stage is an
// independently loadable A2 NAM model (up to 3 files each, switchable),
// wrapped in its own small set of real DSP knobs. Every method call and
// signature here is copied from real, verified sources — Steve Atkinson's
// own NeuralAmpModelerPlugin for the nam::DSP side, Oboe's own current
// class docs/source for the audio side, and NeuralAmpModelerCore's own
// NAM/slimmable.h for the A2/Lite enforcement.
//
// History of real, fixed compile errors from earlier rounds (kept for
// context — the underlying facts these fixes rely on haven't changed):
//   - FullDuplexStream declares virtual Result start()/stop(); our own
//     methods are named setupAndStart()/stopEngine() to avoid silently
//     overriding those with an incompatible signature.
//   - setDataCallback(), not the deprecated setCallback().
//   - NAM_SAMPLE is double, not float — Oboe hands us float buffers, so we
//     convert around every process() call using pre-allocated buffers.
//
// Known simplification (documented, not hidden): each model is Reset() to
// whatever sample rate the device actually negotiates, rather than using
// the ResamplingNAM wrapper (from the plugin repo, not NeuralAmpModelerCore
// itself) that would resample if they don't match. Nearly all modern
// Android devices negotiate 48000 Hz, which is also what NAM models are
// normally trained/exported at.
//
// Device selection: builder.setDeviceId() and the AudioManager.getDevices()
// -> AudioDeviceInfo.getId() pairing are both confirmed directly from
// Oboe's own official docs. A requested device may not be granted, so the
// "Live." status string reports the actual device ID each stream opened
// with. Changing device selection only takes effect on the next Start.
//
// A2-only, forced to Lite: confirmed directly against NeuralAmpModelerCore's
// own NAM/slimmable.h — SetSlimmableSize(const double val) takes 0.0
// (minimum size/quality) to 1.0 (maximum). A file that isn't A2 fails a
// dynamic_cast<nam::SlimmableModel*> and is rejected outright at load time.
// That call is thread-safe but NOT real-time safe (it takes an internal
// lock), so it happens once at load time on the calling (UI) thread, never
// from inside the audio callback.
//
// Chain buffering: three double buffers (mBufA, mBufB, mDryBuf) are passed
// between stages so every nam::DSP::process() call always has genuinely
// separate source/destination buffers — in-place aliasing on that
// third-party call was never verified safe, so it's avoided entirely. Our
// own simple per-sample math (gain, biquads) IS safe in-place since we
// wrote it and know it only reads the current sample.
//
// Safety rule carried over from the old A/B design: reloading the file slot
// that's both currently active for its position AND the app is live isn't
// attempted here — the Kotlin UI disables that specific combination to
// avoid a torn pointer write racing the audio thread's read.

#include <jni.h>
#include <string>
#include <sstream>
#include <filesystem>
#include <memory>
#include <mutex>
#include <algorithm>
#include <atomic>
#include <cmath>

#include <oboe/Oboe.h>
#include "NAM/get_dsp.h"
#include "NAM/slimmable.h"

namespace {

// RBJ cookbook biquad — same formulas already used and verified in the
// earlier web version's tone stack.
struct BiquadCoeffs {
  double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
};

BiquadCoeffs makeLowShelf(double f0, double dbGain, double q, double sampleRate) {
  double A = std::pow(10.0, dbGain / 40.0);
  double w0 = 2.0 * M_PI * f0 / sampleRate;
  double cw = std::cos(w0), sw = std::sin(w0);
  double alpha = sw / (2.0 * q);
  double s = 2.0 * std::sqrt(A) * alpha;
  double b0 = A * ((A + 1) - (A - 1) * cw + s);
  double b1 = 2.0 * A * ((A - 1) - (A + 1) * cw);
  double b2 = A * ((A + 1) - (A - 1) * cw - s);
  double a0 = (A + 1) + (A - 1) * cw + s;
  double a1 = -2.0 * ((A - 1) + (A + 1) * cw);
  double a2 = (A + 1) + (A - 1) * cw - s;
  return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

BiquadCoeffs makeHighShelf(double f0, double dbGain, double q, double sampleRate) {
  double A = std::pow(10.0, dbGain / 40.0);
  double w0 = 2.0 * M_PI * f0 / sampleRate;
  double cw = std::cos(w0), sw = std::sin(w0);
  double alpha = sw / (2.0 * q);
  double s = 2.0 * std::sqrt(A) * alpha;
  double b0 = A * ((A + 1) + (A - 1) * cw + s);
  double b1 = -2.0 * A * ((A - 1) + (A + 1) * cw);
  double b2 = A * ((A + 1) + (A - 1) * cw - s);
  double a0 = (A + 1) - (A - 1) * cw + s;
  double a1 = 2.0 * ((A - 1) - (A + 1) * cw);
  double a2 = (A + 1) - (A - 1) * cw - s;
  return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

BiquadCoeffs makePeaking(double f0, double dbGain, double q, double sampleRate) {
  double A = std::pow(10.0, dbGain / 40.0);
  double w0 = 2.0 * M_PI * f0 / sampleRate;
  double cw = std::cos(w0), sw = std::sin(w0);
  double alpha = sw / (2.0 * q);
  double b0 = 1 + alpha * A;
  double b1 = -2.0 * cw;
  double b2 = 1 - alpha * A;
  double a0 = 1 + alpha / A;
  double a1 = -2.0 * cw;
  double a2 = 1 - alpha / A;
  return {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

// Direct Form I — holds its own state between calls, which is what makes
// this an actual IIR filter rather than a fresh calculation every burst.
struct BiquadState {
  double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  double process(double x, const BiquadCoeffs& c) {
    double y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2;
    x2 = x1;
    x1 = x;
    y2 = y1;
    y1 = y;
    return y;
  }
};

// RBJ cookbook allpass — same family as the shelf/peaking filters above,
// used here as a dispersion stage for the spring reverb: chaining several
// of these at different frequencies makes different frequencies exit at
// different times without changing the overall tone, which is specifically
// what produces a spring tank's "boing" rather than a smooth wash.
BiquadCoeffs makeAllpass(double f0, double q, double sampleRate) {
  double w0 = 2.0 * M_PI * f0 / sampleRate;
  double cw = std::cos(w0), sw = std::sin(w0);
  double alpha = sw / (2.0 * q);
  double a0 = 1.0 + alpha;
  double b0 = (1.0 - alpha) / a0;
  double b1 = (-2.0 * cw) / a0;
  double a1 = (-2.0 * cw) / a0;
  double a2 = (1.0 - alpha) / a0;
  return {b0, b1, 1.0, a1, a2};
}

// Simple one-pole lowpass, used inside the reverb's feedback loop to darken
// the tail as it decays — real springs lose brightness as they ring out;
// without this the decay sounds harsh/synthetic rather than authentic.
struct OnePoleLowpass {
  double state = 0;
  double process(double x, double coeff) {
    state += coeff * (x - state);
    return state;
  }
};

// A short, dispersive spring-tank reverb: dry signal feeds several parallel
// delay+feedback ("comb") lines of DIFFERENT lengths -> their outputs are
// summed -> that blend runs through a chain of allpass stages to diffuse it
// further. Two real fixes are folded into this structure, each found from
// actually listening to an earlier version, not assumed up front:
//   1. Allpass filters alone only shift phase, not time — a feedback loop
//      built from allpass stages with no delay line produced a resonant
//      "barrel" coloration instead of any sense of a decaying reflection.
//   2. A SINGLE delay length in the feedback loop (the first fix) sounded
//      like a distinct, regularly-spaced repeat — like a metronome — no
//      matter how much diffusion followed it, because diffusion smooths
//      the tone of each repeat, not its timing. Several different,
//      deliberately non-simple-ratio delay lengths blended together (the
//      classic Schroeder/Freeverb approach) is what turns a single
//      repeating echo into an overlapping, smooth-sounding tail.
// Dwell (fed into each comb line as a 0..kMaxDwellFeedback fraction, capped
// well below 1.0 by the setter) controls how much of each line recirculates,
// i.e. how long the tank rings. This is a reasonable, correctly-structured
// tuning, not an ear-verified match to a real Fender tank — the exact delay
// lengths, stage frequencies, and damping amount remain reasonable
// candidates for further refinement once heard again.
constexpr int kReverbStages = 5;
constexpr int kReverbCombLines = 4;
// Increased from the original 4096 to comfortably cover Hall's longest
// delay (~120ms ≈ 5760 samples at 48kHz) with margin — the buffer just
// needs to be at least as large as the longest delay any type uses; wasted
// space for the shorter types (Spring/Plate) costs nothing but a few
// hundred KB of memory, trivial on a phone.
constexpr int kReverbDelayBufferSize = 8192;

// Three delay-length sets sharing the identical comb+diffusion structure —
// what actually changes character between Spring/Plate/Hall is how long
// and how spread-out these delay lines are, not the algorithm itself. Each
// set still uses no simple integer ratios between its own four values, for
// the same reason as the original Spring tuning: avoids the lines
// reinforcing each other into an artificial new periodicity.
// Spring: short, "boingy" — the original tuning, ~15-42ms.
constexpr int kSpringDelaySamples[kReverbCombLines] = {733, 1049, 1471, 2003};
// Plate: medium, denser and smoother — ~35-75ms.
constexpr int kPlateDelaySamples[kReverbCombLines] = {1700, 2300, 2900, 3600};
// Hall: long, spacious — ~50-120ms.
constexpr int kHallDelaySamples[kReverbCombLines] = {2400, 3600, 4800, 5760};

struct CombLine {
  double buf[kReverbDelayBufferSize] = {0};
  int pos = 0;
  OnePoleLowpass damping;
  double feedbackState = 0.0;

  double process(double input, double dwellFraction, int delaySamples) {
    // The dry contribution is scaled down before entering the delay line —
    // without this, the very FIRST reflection comes back at full,
    // unattenuated input level, since dwell/feedback only scale what
    // recirculates on later passes, not this initial write. That's what
    // was making the first repeat sound disproportionately loud compared
    // to the decay that followed, independent of the dwell-range fix
    // (which only changes how long that decay continues).
    static constexpr double kInputGain = 0.5;
    double fed = input * kInputGain + feedbackState * dwellFraction;
    buf[pos] = fed;
    int readPos = pos - delaySamples;
    if (readPos < 0) readPos += kReverbDelayBufferSize;
    double delayed = buf[readPos];
    pos = (pos + 1) % kReverbDelayBufferSize;
    // Damping lives on each line's own feedback path — real springs lose
    // brightness as they ring out; without this the decay sounds harsh
    // rather than authentic.
    feedbackState = damping.process(delayed, 0.3);
    return delayed;
  }
};

// Despite the name (kept to avoid an unnecessary rename across the file),
// this now drives all three reverb types — Spring/Plate/Hall share this
// exact same comb+diffusion structure, differing only in which delay-length
// set is passed in by the caller each callback.
struct SpringReverb {
  CombLine combs[kReverbCombLines];
  BiquadState allpassStates[kReverbStages];

  double process(double dryInput, double dwellFraction, const int* combDelays,
                  const BiquadCoeffs (&coeffs)[kReverbStages]) {
    double summed = 0.0;
    for (int i = 0; i < kReverbCombLines; i++) {
      summed += combs[i].process(dryInput, dwellFraction, combDelays[i]);
    }
    summed /= kReverbCombLines;  // average, not sum — avoids gain buildup from 4 parallel paths

    double dispersed = summed;
    for (int i = 0; i < kReverbStages; i++) {
      dispersed = allpassStates[i].process(dispersed, coeffs[i]);
    }
    return dispersed;
  }
};

// Autocorrelation-based pitch detection — the standard, proven technique
// for monophonic instrument tuning (better suited to a single guitar
// string than naive FFT peak-picking, especially at low-E frequencies
// where frequency resolution matters most).
//
// The real engineering concern here isn't the algorithm itself, it's cost:
// a full autocorrelation pass across a few thousand samples is genuinely
// expensive — expensive enough that doing it all at once could risk
// missing the real-time deadline on a tight buffer (this project has seen
// buffers as small as 96 frames / ~2ms in real testing). So the search is
// deliberately spread across many callbacks — a small, bounded number of
// lag values checked per call — rather than computed in one shot. A full
// analysis pass takes roughly 80-100ms to complete this way, which is
// still plenty responsive for a tuner, and guarantees no single callback
// ever pays the full cost.
struct GuitarTuner {
  static constexpr int kWindowSize = 4096;
  static constexpr int kLagsPerCallback = 40;  // bounds worst-case per-callback cost
  static constexpr double kSilenceRms = 0.01;  // below this, a window counts as silent
  // A plucked note's loudness drops well before the string has actually
  // stopped ringing — clearing the reading the instant one window dips
  // quiet made the needle disappear mid-decay, before the note was really
  // over. Holding the last confident reading through ~2 seconds of
  // continuous silence (not just one quiet window) fixes that, matching
  // how most real tuners behave, without re-analyzing an unreliable,
  // decaying tail and risking a jittery readout instead of a stable one.
  static constexpr int kSilentWindowsBeforeClear = 24;  // ~24 * ~85ms per window ≈ 2s

  double buffer[kWindowSize] = {0};
  int writePos = 0;
  int samplesAccumulated = 0;
  double energyAccum = 0;

  bool searching = false;
  bool hasSignalThisWindow = false;
  int consecutiveSilentWindows = 0;
  int minLag = 1, maxLag = 1;
  int searchLag = 1;
  double bestCorrelation = 0;
  int bestLag = 1;

  std::atomic<float>* outFrequency;  // 0 = no signal detected (only after the hold period)

  explicit GuitarTuner(std::atomic<float>* out) : outFrequency(out) {}

  void process(const double* samples, int n, double sampleRate) {
    for (int i = 0; i < n; i++) {
      double s = samples[i];
      buffer[writePos] = s;
      energyAccum += s * s;
      writePos = (writePos + 1) % kWindowSize;
      samplesAccumulated++;
      if (samplesAccumulated >= kWindowSize) {
        // A window just completed — decide whether it's worth searching at
        // all (silence gate is essentially free, computed as samples
        // arrived above) before committing to the expensive part.
        double rms = std::sqrt(energyAccum / kWindowSize);
        energyAccum = 0;
        samplesAccumulated = 0;
        hasSignalThisWindow = rms > kSilenceRms;
        if (!hasSignalThisWindow) {
          consecutiveSilentWindows++;
          if (consecutiveSilentWindows >= kSilentWindowsBeforeClear) {
            outFrequency->store(0.0f);
          }
          // Below the hold threshold: deliberately leave outFrequency
          // untouched, so the last confident reading keeps showing.
          searching = false;
          continue;
        }
        consecutiveSilentWindows = 0;
        // Guitar range, a little wider than standard tuning (E2-E4) to
        // comfortably cover common drop tunings too.
        minLag = static_cast<int>(sampleRate / 400.0);
        maxLag = std::min(static_cast<int>(sampleRate / 65.0), kWindowSize / 2 - 1);
        if (minLag < 1) minLag = 1;
        searchLag = minLag;
        bestCorrelation = -1e18;
        bestLag = minLag;
        searching = true;
      }
    }

    if (!searching) return;

    int steps = 0;
    while (searchLag <= maxLag && steps < kLagsPerCallback) {
      double correlation = 0;
      int compareLength = kWindowSize - searchLag;
      for (int i = 0; i < compareLength; i++) {
        correlation += buffer[i] * buffer[i + searchLag];
      }
      correlation /= compareLength;
      if (correlation > bestCorrelation) {
        bestCorrelation = correlation;
        bestLag = searchLag;
      }
      searchLag++;
      steps++;
    }

    if (searchLag > maxLag) {
      outFrequency->store(bestLag > 0 ? static_cast<float>(sampleRate / bestLag) : 0.0f);
      searching = false;  // wait for the next window to fill before searching again
    }
  }
};

// Chain positions.
constexpr int kDrive = 0;
constexpr int kAmp = 1;
constexpr int kMod = 2;
constexpr int kNumPositions = 3;
constexpr int kFilesPerPosition = 3;

class NamAudioEngine : public oboe::FullDuplexStream {
public:
  std::string loadModel(const std::string& path, int position, int fileSlot) {
    if (position < 0 || position >= kNumPositions) return "Invalid position.";
    if (fileSlot < 0 || fileSlot >= kFilesPerPosition) return "Invalid file slot.";
    std::lock_guard<std::mutex> lock(mLoadMutex);
    try {
      auto dspPath = std::filesystem::path(path);
      auto model = nam::get_dsp(dspPath);
      if (!model) {
        return "nam::get_dsp() returned null (no exception, but no model either).";
      }
      if (model->NumInputChannels() != 1 || model->NumOutputChannels() != 1) {
        std::stringstream ss;
        ss << "This engine only supports mono in/out models right now. This model has "
           << model->NumInputChannels() << " input channel(s) and "
           << model->NumOutputChannels() << " output channel(s).";
        return ss.str();
      }

      // A2 only: a real, definitive check — every A2 model (ContainerModel
      // or SlimmableWaveNet) implements nam::SlimmableModel, nothing else
      // does. If this cast fails, the file isn't A2, full stop.
      nam::SlimmableModel* slimmable = dynamic_cast<nam::SlimmableModel*>(model.get());
      if (!slimmable) {
        return "This app only loads A2 models now. This file isn't A2 — please load an A2 .nam file.";
      }
      // Force Lite. 0.0 = minimum size/quality (Lite), 1.0 = maximum
      // (Full). Must happen here, not in the audio callback.
      slimmable->SetSlimmableSize(0.0);

      if (mOutputStream) {
        model->Reset(mOutputStream->getSampleRate(), mOutputStream->getFramesPerBurst());
      }
      mModel[position][fileSlot] = std::move(model);
      std::stringstream ok;
      ok << "Model loaded (A2, Lite). Expects " << mModel[position][fileSlot]->GetExpectedSampleRate() << " Hz.";
      if (mModel[position][fileSlot]->HasLoudness()) {
        ok << " Loudness: " << mModel[position][fileSlot]->GetLoudness() << " dB.";
      }
      return ok.str();
    } catch (const std::exception& e) {
      return std::string("Failed to load model: ") + e.what();
    }
  }

  bool hasModel(int position, int fileSlot) const {
    if (position < 0 || position >= kNumPositions) return false;
    if (fileSlot < 0 || fileSlot >= kFilesPerPosition) return false;
    return mModel[position][fileSlot] != nullptr;
  }

  void setActiveFile(int position, int fileSlot) {
    if (position < 0 || position >= kNumPositions) return;
    if (fileSlot < 0 || fileSlot >= kFilesPerPosition) return;
    mActiveFile[position].store(fileSlot);
  }

  // All setters: called from the UI thread; read from the audio thread in
  // onBothStreamsReady(). std::atomic is lock-free on every real Android
  // target, so this is safe without a mutex in the realtime path.
  void setDriveGainDb(float db) { mDriveGainLinear.store(std::pow(10.0f, db / 20.0f)); }
  void setDriveLevelDb(float db) { mDriveLevelLinear.store(std::pow(10.0f, db / 20.0f)); }
  void setDriveToneDb(float db) { mDriveToneDb.store(db); }

  void setAmpGainDb(float db) { mAmpGainLinear.store(std::pow(10.0f, db / 20.0f)); }
  void setAmpBassDb(float db) { mAmpBassDb.store(db); }
  void setAmpMidDb(float db) { mAmpMidDb.store(db); }
  void setAmpTrebleDb(float db) { mAmpTrebleDb.store(db); }
  void setAmpVolumeDb(float db) { mAmpVolumeLinear.store(std::pow(10.0f, db / 20.0f)); }

  // mixPercent: 0-100, converted here to the internal 0.0-1.0 fraction.
  void setModMixPercent(float mixPercent) { mModMixFraction.store(mixPercent / 100.0f); }
  void setModLevelDb(float db) { mModLevelLinear.store(std::pow(10.0f, db / 20.0f)); }

  // dwellPercent: 0-100, mapped to a feedback gain capped at
  // kMaxDwellFeedback regardless of input — a feedback coefficient at or
  // above 1.0 would make the reverb grow unbounded rather than decay, so
  // this cap guarantees stability at every possible knob position. Raised
  // from an earlier, more conservative 0.85: real-world testing showed the
  // whole knob range decayed too fast to build a real tail (feedback-to-
  // decay-time isn't linear — the audible differences bunch up near the
  // top of the range), which read as "one strong repeat" rather than
  // reverb, and made the knob's effect hard to notice at all. 0.95 stays
  // safely below the 1.0 instability threshold while giving the top of the
  // range genuine, extended decay.
  void setReverbDwellPercent(float dwellPercent) {
    static constexpr float kMaxDwellFeedback = 0.95f;
    mReverbDwellFraction.store((dwellPercent / 100.0f) * kMaxDwellFeedback);
  }
  void setReverbMixPercent(float mixPercent) { mReverbMixFraction.store(mixPercent / 100.0f); }
  void setReverbLevelDb(float db) { mReverbLevelLinear.store(std::pow(10.0f, db / 20.0f)); }

  // Bypass toggles — each stage's whole block (model + its own knobs) is
  // skipped when disabled; see onBothStreamsReady.
  void setDriveEnabled(bool enabled) { mDriveEnabled.store(enabled); }
  void setAmpEnabled(bool enabled) { mAmpEnabled.store(enabled); }
  void setModEnabled(bool enabled) { mModEnabled.store(enabled); }
  void setReverbEnabled(bool enabled) { mReverbEnabled.store(enabled); }
  // 0 = Spring, 1 = Plate, 2 = Hall — see kSpringDelaySamples etc above.
  void setReverbType(int type) { mReverbType.store(type); }

  // Engaging the tuner mutes the processed output (see onBothStreamsReady)
  // and starts pitch analysis on the raw input; disabling it does neither
  // — the analysis code path isn't reached at all when this is false, so
  // there is zero added cost while the tuner is off.
  void setTunerEnabled(bool enabled) { mTunerEnabled.store(enabled); }
  float getTunerFrequency() { return mTunerDetectedFrequency.load(); }

  // deviceId is an Android AudioDeviceInfo id from the Kotlin side, or -1
  // (our own sentinel, not Oboe's) meaning "system default" — translated
  // here to the real oboe::kUnspecified constant.
  void setPreferredInputDevice(int32_t deviceId) {
    mPreferredInputDeviceId.store(deviceId < 0 ? oboe::kUnspecified : deviceId);
  }
  void setPreferredOutputDevice(int32_t deviceId) {
    mPreferredOutputDeviceId.store(deviceId < 0 ? oboe::kUnspecified : deviceId);
  }

  // Multi-channel input routing (e.g. picking Channel 2 on an interface
  // with separate Hi-Z/Line inputs on the same USB device). channelCount
  // is REQUESTED when the input stream next opens — confirmed via Android's
  // own AudioDeviceInfo.getChannelCounts() on the Kotlin side. channelIndex
  // is 0-based and can be changed live; it's read fresh every callback, so
  // no restart is needed to switch which channel is actually used.
  void setInputChannelCount(int32_t channelCount) {
    mDesiredInputChannelCount.store(channelCount < 1 ? 1 : channelCount);
  }
  void setSelectedInputChannel(int32_t channelIndex) { mSelectedInputChannel.store(channelIndex); }

  std::string setupAndStart() {
    if (mRunning) return "Already running.";
    // No longer requires any model loaded — an empty position just passes
    // its input through unchanged (see onBothStreamsReady).

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setSampleRate(kPreferredSampleRate)
        ->setDeviceId(mPreferredOutputDeviceId.load())
        ->setDataCallback(this);

    oboe::Result result = outBuilder.openStream(mOutputStream);
    if (result != oboe::Result::OK) {
      std::stringstream ss;
      ss << "Failed to open output stream: " << oboe::convertToText(result);
      return ss.str();
    }

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(mDesiredInputChannelCount.load())
        ->setSampleRate(mOutputStream->getSampleRate())
        ->setDeviceId(mPreferredInputDeviceId.load())
        ->setBufferCapacityInFrames(mOutputStream->getBufferCapacityInFrames() * 2);

    result = inBuilder.openStream(mInputStream);
    if (result != oboe::Result::OK) {
      mOutputStream->close();
      mOutputStream.reset();
      std::stringstream ss;
      ss << "Failed to open input stream: " << oboe::convertToText(result);
      return ss.str();
    }

    // The actual negotiated channel count may differ from what was
    // requested — this is what onBothStreamsReady actually uses to
    // correctly index into a possibly-interleaved multi-channel buffer.
    mActualInputChannelCount.store(mInputStream->getChannelCount());

    // Explicitly force the smallest possible buffer (exactly one burst)
    // every time audio starts. LowLatency+Exclusive mode usually already
    // negotiates something close to minimum by default, but "usually" isn't
    // a guarantee — this makes the starting point deterministic rather than
    // trusting whatever a given device happens to default to. The buffer
    // tuner can still grow it from here if the user chooses to.
    int32_t burst = mOutputStream->getFramesPerBurst();
    if (burst > 0) mOutputStream->setBufferSizeInFrames(burst);

    // Now that we know the real negotiated sample rate, prepare every
    // currently loaded model for it — every position and file slot, not
    // just the active ones, so switching later doesn't need a fresh Reset.
    for (int p = 0; p < kNumPositions; p++) {
      for (int f = 0; f < kFilesPerPosition; f++) {
        if (mModel[p][f]) mModel[p][f]->Reset(mOutputStream->getSampleRate(), mOutputStream->getFramesPerBurst());
      }
    }

    setSharedInputStream(mInputStream);
    setSharedOutputStream(mOutputStream);

    result = FullDuplexStream::start();
    if (result != oboe::Result::OK) {
      std::stringstream ss;
      ss << "Failed to start streams: " << oboe::convertToText(result);
      closeStreams();
      return ss.str();
    }

    mRunning = true;
    std::stringstream ok;
    ok << "Live. Out: " << mOutputStream->getSampleRate() << " Hz, "
       << mOutputStream->getChannelCount() << " ch, "
       << oboe::convertToText(mOutputStream->getFormat()) << ", "
       << mOutputStream->getFramesPerBurst() << " frames/burst, device "
       << mOutputStream->getDeviceId() << ". "
       << "In: " << mInputStream->getSampleRate() << " Hz, "
       << mInputStream->getChannelCount() << " ch, "
       << oboe::convertToText(mInputStream->getFormat()) << ", device "
       << mInputStream->getDeviceId() << ".";
    return ok.str();
  }

  void stopEngine() {
    if (!mRunning) return;
    FullDuplexStream::stop();
    closeStreams();
    mRunning = false;
  }

  // --- Buffer logger/tuner --------------------------------------------
  // Confirmed directly from Oboe's own docs/source: getXRunCount() +
  // setBufferSizeInFrames() together are literally Oboe's documented
  // mechanism for runtime latency tuning. Buffer size can only be adjusted
  // on an ALREADY-OPEN stream (it depends on runtime negotiation), and per
  // Oboe's own docs this only meaningfully applies to the OUTPUT stream —
  // input streams are intentionally kept as empty as possible, so there's
  // no equivalent control for it. A larger buffer means more latency but
  // more headroom against glitches; a smaller one is the reverse — this
  // is a real, live tradeoff control, not just a readout.

  /** Human-readable buffer/xrun status, or a placeholder if audio isn't running. */
  std::string getBufferStatus() {
    if (!mRunning || !mOutputStream) return "Audio not running.";
    int32_t bufSize = mOutputStream->getBufferSizeInFrames();
    int32_t bufCapacity = mOutputStream->getBufferCapacityInFrames();
    int32_t burst = mOutputStream->getFramesPerBurst();
    auto xrunResult = mOutputStream->getXRunCount();
    int32_t xruns = (xrunResult == oboe::Result::OK) ? xrunResult.value() : -1;
    std::stringstream ss;
    ss << "Buffer: " << bufSize << " frames (" << (burst > 0 ? bufSize / burst : 0) << " bursts of " << burst
       << ") / " << bufCapacity << " max. XRuns: " << xruns << ".";
    return ss.str();
  }

  /** Adjusts the output buffer by wholeBursts (positive = larger/safer, negative = smaller/lower-latency). */
  std::string adjustBufferSize(int wholeBursts) {
    if (!mRunning || !mOutputStream) return "Start audio first.";
    int32_t burst = mOutputStream->getFramesPerBurst();
    if (burst <= 0) return "Burst size unavailable.";
    int32_t current = mOutputStream->getBufferSizeInFrames();
    int32_t requested = current + wholeBursts * burst;
    if (requested < burst) requested = burst;  // never below one burst
    auto result = mOutputStream->setBufferSizeInFrames(requested);
    if (result != oboe::Result::OK) {
      std::stringstream ss;
      ss << "Couldn't adjust buffer: " << oboe::convertToText(result.error());
      return ss.str();
    }
    return getBufferStatus();
  }
  // -----------------------------------------------------------------------

  oboe::DataCallbackResult onBothStreamsReady(const void* inputData, int numInputFrames, void* outputData,
                                               int numOutputFrames) override {
    int n = std::min({numInputFrames, numOutputFrames, kMaxFramesPerCallback});
    auto* in = static_cast<const float*>(inputData);
    auto* out = static_cast<float*>(outputData);

    if (n <= 0) {
      for (int i = 0; i < numOutputFrames; i++) out[i] = 0.0f;
      return oboe::DataCallbackResult::Continue;
    }

    double sr = mOutputStream ? mOutputStream->getSampleRate() : kPreferredSampleRate;

    // Multi-channel input support: the input buffer may be interleaved
    // (e.g. [L0,R0,L1,R1,...] for a 2-channel device) rather than plain
    // mono. inChannels is the REAL negotiated count (confirmed via
    // getChannelCount() after opening, not just what was requested); the
    // selected index is clamped defensively in case a device was swapped
    // for one with fewer channels without the selection being updated.
    int inChannels = mActualInputChannelCount.load();
    if (inChannels < 1) inChannels = 1;
    int selChannel = mSelectedInputChannel.load();
    if (selChannel < 0 || selChannel >= inChannels) selChannel = 0;

    // Tuner: taps the raw, unprocessed input independently of the chain
    // below (never the Drive/Amp/Mod/Reverb output — running pitch
    // detection on a distorted or reverberant signal would badly confuse
    // it). Gated behind one atomic check, same mechanism as every other
    // bypass toggle in this engine — when disabled, this block simply
    // isn't entered, at all, so there is no cost from it whatsoever.
    if (mTunerEnabled.load()) {
      for (int i = 0; i < n; i++) mTunerInputBufD[i] = static_cast<double>(in[i * inChannels + selChannel]);
      mTuner.process(mTunerInputBufD, n, sr);
    }

    // --- Stage 1: Drive ---
    if (mDriveEnabled.load()) {
      float driveGain = mDriveGainLinear.load();
      for (int i = 0; i < n; i++) mBufA[i] = static_cast<double>(in[i * inChannels + selChannel]) * driveGain;
      nam::DSP* driveModel = mModel[kDrive][mActiveFile[kDrive].load()].get();
      if (driveModel) {
        double* inCh[1] = {mBufA};
        double* outCh[1] = {mBufB};
        driveModel->process(inCh, outCh, n);
      } else {
        std::copy(mBufA, mBufA + n, mBufB);
      }
      BiquadCoeffs driveToneC = makeHighShelf(2000.0, mDriveToneDb.load(), 0.7, sr);
      float driveLevel = mDriveLevelLinear.load();
      for (int i = 0; i < n; i++) {
        mBufB[i] = mDriveToneState.process(mBufB[i], driveToneC) * driveLevel;
      }
    } else {
      // Bypassed: input passes straight through untouched — no gain, no
      // model, no tone shaping — landing in mBufB, same as the enabled
      // path does, so the next stage doesn't need to know the difference.
      for (int i = 0; i < n; i++) mBufB[i] = static_cast<double>(in[i * inChannels + selChannel]);
    }

    // --- Stage 2: Amp ---
    if (mAmpEnabled.load()) {
      float ampGain = mAmpGainLinear.load();
      for (int i = 0; i < n; i++) mBufB[i] *= ampGain;
      nam::DSP* ampModel = mModel[kAmp][mActiveFile[kAmp].load()].get();
      if (ampModel) {
        double* inCh[1] = {mBufB};
        double* outCh[1] = {mBufA};
        ampModel->process(inCh, outCh, n);
      } else {
        std::copy(mBufB, mBufB + n, mBufA);
      }
      BiquadCoeffs bassC = makeLowShelf(110.0, mAmpBassDb.load(), 0.7, sr);
      BiquadCoeffs midC = makePeaking(750.0, mAmpMidDb.load(), 0.9, sr);
      BiquadCoeffs trebleC = makeHighShelf(3200.0, mAmpTrebleDb.load(), 0.7, sr);
      float ampVolume = mAmpVolumeLinear.load();
      for (int i = 0; i < n; i++) {
        double s = mBufA[i];
        s = mAmpBassState.process(s, bassC);
        s = mAmpMidState.process(s, midC);
        s = mAmpTrebleState.process(s, trebleC);
        mBufA[i] = s * ampVolume;
      }
    } else {
      std::copy(mBufB, mBufB + n, mBufA);
    }

    // mBufA now holds the post-amp signal — this is the "dry" reference
    // for the Modulation wet/dry mix, preserved before Modulation touches it.
    std::copy(mBufA, mBufA + n, mDryBuf);

    // --- Stage 3: Modulation ---
    if (mModEnabled.load()) {
      nam::DSP* modModel = mModel[kMod][mActiveFile[kMod].load()].get();
      if (modModel) {
        double* inCh[1] = {mBufA};
        double* outCh[1] = {mBufB};
        modModel->process(inCh, outCh, n);
      } else {
        std::copy(mBufA, mBufA + n, mBufB);
      }
      float modLevel = mModLevelLinear.load();
      float mix = mModMixFraction.load();
      for (int i = 0; i < n; i++) {
        double wet = mBufB[i] * modLevel;
        mBufA[i] = mDryBuf[i] * (1.0 - mix) + wet * mix;
      }
    } else {
      // Bypassed: dry signal straight through — no model, no mix, no level.
      std::copy(mDryBuf, mDryBuf + n, mBufA);
    }

    // --- Stage 4: Reverb (built-in spring-tank algorithm — not NAM-based,
    // no file loading, just the dispersion/feedback DSP above) ---
    if (mReverbEnabled.load()) {
      // Coefficients only depend on sample rate, which doesn't change
      // mid-callback — computed once per callback here, same pattern as
      // the Amp tone stack above, not recomputed per sample.
      static const double kReverbFreqs[kReverbStages] = {280.0, 620.0, 1150.0, 2000.0, 3400.0};
      BiquadCoeffs reverbCoeffs[kReverbStages];
      for (int i = 0; i < kReverbStages; i++) {
        reverbCoeffs[i] = makeAllpass(kReverbFreqs[i], 0.7, sr);
      }
      const int* combDelays;
      switch (mReverbType.load()) {
        case 1: combDelays = kPlateDelaySamples; break;
        case 2: combDelays = kHallDelaySamples; break;
        default: combDelays = kSpringDelaySamples; break;
      }
      double dwell = mReverbDwellFraction.load();
      double mix = mReverbMixFraction.load();
      double level = mReverbLevelLinear.load();
      for (int i = 0; i < n; i++) {
        double dry = mBufA[i];
        double wet = mReverb.process(dry, dwell, combDelays, reverbCoeffs);
        mBufA[i] = (dry * (1.0 - mix) + wet * mix) * level;
      }
    }

    // Tuner mutes the live output while active — same behavior as a real
    // tuner pedal. The chain above still runs normally underneath (not
    // skipped), so there's no state-discontinuity artifact when the tuner
    // is turned back off; only this final write to the speaker is muted.
    if (mTunerEnabled.load()) {
      for (int i = 0; i < numOutputFrames; i++) out[i] = 0.0f;
    } else {
      for (int i = 0; i < n; i++) out[i] = static_cast<float>(mBufA[i]);
      for (int i = n; i < numOutputFrames; i++) out[i] = 0.0f;
    }

    return oboe::DataCallbackResult::Continue;
  }

private:
  void closeStreams() {
    if (mOutputStream) {
      mOutputStream->close();
      mOutputStream.reset();
    }
    if (mInputStream) {
      mInputStream->close();
      mInputStream.reset();
    }
  }

  static constexpr int32_t kPreferredSampleRate = 48000;
  static constexpr int kMaxFramesPerCallback = 4096;  // generous safety margin over real burst sizes

  // [position][fileSlot]: position is Drive/Amp/Mod, fileSlot is one of 3
  // loadable files remembered per position.
  std::unique_ptr<nam::DSP> mModel[kNumPositions][kFilesPerPosition];
  std::atomic<int> mActiveFile[kNumPositions]{0, 0, 0};

  std::shared_ptr<oboe::AudioStream> mInputStream;
  std::shared_ptr<oboe::AudioStream> mOutputStream;
  std::mutex mLoadMutex;
  bool mRunning = false;

  std::atomic<float> mDriveGainLinear{1.0f};
  std::atomic<float> mDriveLevelLinear{1.0f};
  std::atomic<float> mDriveToneDb{0.0f};
  BiquadState mDriveToneState;

  std::atomic<float> mAmpGainLinear{1.0f};
  std::atomic<float> mAmpBassDb{0.0f};
  std::atomic<float> mAmpMidDb{0.0f};
  std::atomic<float> mAmpTrebleDb{0.0f};
  std::atomic<float> mAmpVolumeLinear{1.0f};
  BiquadState mAmpBassState;
  BiquadState mAmpMidState;
  BiquadState mAmpTrebleState;

  std::atomic<float> mModMixFraction{1.0f};  // 100% wet by default
  std::atomic<float> mModLevelLinear{1.0f};

  // Modest defaults, not maxed out — 100% wet spring reverb would drown an
  // amp signal, unlike Modulation where 100% wet is a reasonable default
  // for an arbitrary loaded effect.
  SpringReverb mReverb;
  std::atomic<int> mReverbType{0};  // 0 = Spring, 1 = Plate, 2 = Hall
  std::atomic<float> mReverbDwellFraction{0.3f * 0.95f};  // 30% dwell, pre-scaled by the same cap setReverbDwellPercent uses
  std::atomic<float> mReverbMixFraction{0.3f};
  std::atomic<float> mReverbLevelLinear{1.0f};

  std::atomic<bool> mDriveEnabled{true};
  std::atomic<bool> mAmpEnabled{true};
  std::atomic<bool> mModEnabled{true};
  std::atomic<bool> mReverbEnabled{true};

  // Tuner taps the raw input independently of the Drive/Amp/Mod/Reverb
  // chain (which keeps running normally underneath — only the final output
  // gets muted, so model/filter state stays warm and there's no resume
  // artifact when the tuner is turned back off).
  std::atomic<bool> mTunerEnabled{false};
  std::atomic<float> mTunerDetectedFrequency{0.0f};  // must be declared before mTuner below
  GuitarTuner mTuner{&mTunerDetectedFrequency};

  std::atomic<int32_t> mPreferredInputDeviceId{oboe::kUnspecified};
  std::atomic<int32_t> mPreferredOutputDeviceId{oboe::kUnspecified};

  std::atomic<int32_t> mDesiredInputChannelCount{1};  // requested when the stream next opens
  std::atomic<int32_t> mActualInputChannelCount{1};   // real negotiated count, set after opening
  std::atomic<int32_t> mSelectedInputChannel{0};      // 0-based; which channel to actually use

  // Passed between chain stages — always distinct source/destination
  // buffers for every nam::DSP::process() call (see file header note).
  double mBufA[kMaxFramesPerCallback];
  double mBufB[kMaxFramesPerCallback];
  double mDryBuf[kMaxFramesPerCallback];
  double mTunerInputBufD[kMaxFramesPerCallback];
};

NamAudioEngine* gEngine = nullptr;

NamAudioEngine* getEngine() {
  if (!gEngine) gEngine = new NamAudioEngine();
  return gEngine;
}

}  // namespace

// All exported under NativeAudio (a plain Kotlin object, not tied to any
// Activity) — AudioService needs to reach these too, e.g. when the user
// swipes the app away entirely.

extern "C" JNIEXPORT jstring JNICALL Java_com_namamp_app_NativeAudio_nativeLoadModel(JNIEnv* env, jobject,
                                                                                      jstring pathJ, jint position,
                                                                                      jint fileSlot) {
  const char* pathChars = env->GetStringUTFChars(pathJ, nullptr);
  std::string path(pathChars);
  env->ReleaseStringUTFChars(pathJ, pathChars);
  std::string result = getEngine()->loadModel(path, position, fileSlot);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetActiveFile(JNIEnv*, jobject,
                                                                                       jint position, jint fileSlot) {
  getEngine()->setActiveFile(position, fileSlot);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_namamp_app_NativeAudio_nativeStartAudio(JNIEnv* env, jobject) {
  std::string result = getEngine()->setupAndStart();
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeStopAudio(JNIEnv*, jobject) {
  if (gEngine) gEngine->stopEngine();
}

extern "C" JNIEXPORT jstring JNICALL Java_com_namamp_app_NativeAudio_nativeGetBufferStatus(JNIEnv* env, jobject) {
  std::string result = getEngine()->getBufferStatus();
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_namamp_app_NativeAudio_nativeAdjustBufferSize(JNIEnv* env, jobject,
                                                                                             jint wholeBursts) {
  std::string result = getEngine()->adjustBufferSize(wholeBursts);
  return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetDriveGain(JNIEnv*, jobject, jfloat db) {
  getEngine()->setDriveGainDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetDriveLevel(JNIEnv*, jobject, jfloat db) {
  getEngine()->setDriveLevelDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetDriveTone(JNIEnv*, jobject, jfloat db) {
  getEngine()->setDriveToneDb(db);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpGain(JNIEnv*, jobject, jfloat db) {
  getEngine()->setAmpGainDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpBass(JNIEnv*, jobject, jfloat db) {
  getEngine()->setAmpBassDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpMid(JNIEnv*, jobject, jfloat db) {
  getEngine()->setAmpMidDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpTreble(JNIEnv*, jobject, jfloat db) {
  getEngine()->setAmpTrebleDb(db);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpVolume(JNIEnv*, jobject, jfloat db) {
  getEngine()->setAmpVolumeDb(db);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetModMix(JNIEnv*, jobject,
                                                                                   jfloat mixPercent) {
  getEngine()->setModMixPercent(mixPercent);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetModLevel(JNIEnv*, jobject, jfloat db) {
  getEngine()->setModLevelDb(db);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetReverbDwell(JNIEnv*, jobject,
                                                                                        jfloat dwellPercent) {
  getEngine()->setReverbDwellPercent(dwellPercent);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetReverbMix(JNIEnv*, jobject,
                                                                                      jfloat mixPercent) {
  getEngine()->setReverbMixPercent(mixPercent);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetReverbLevel(JNIEnv*, jobject, jfloat db) {
  getEngine()->setReverbLevelDb(db);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetDriveEnabled(JNIEnv*, jobject,
                                                                                          jboolean enabled) {
  getEngine()->setDriveEnabled(enabled);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetAmpEnabled(JNIEnv*, jobject,
                                                                                        jboolean enabled) {
  getEngine()->setAmpEnabled(enabled);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetModEnabled(JNIEnv*, jobject,
                                                                                        jboolean enabled) {
  getEngine()->setModEnabled(enabled);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetReverbEnabled(JNIEnv*, jobject,
                                                                                          jboolean enabled) {
  getEngine()->setReverbEnabled(enabled);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetReverbType(JNIEnv*, jobject, jint type) {
  getEngine()->setReverbType(type);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetTunerEnabled(JNIEnv*, jobject,
                                                                                         jboolean enabled) {
  getEngine()->setTunerEnabled(enabled);
}
extern "C" JNIEXPORT jfloat JNICALL Java_com_namamp_app_NativeAudio_nativeGetTunerFrequency(JNIEnv*, jobject) {
  return getEngine()->getTunerFrequency();
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetInputDevice(JNIEnv*, jobject,
                                                                                        jint deviceId) {
  getEngine()->setPreferredInputDevice(deviceId);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetOutputDevice(JNIEnv*, jobject,
                                                                                         jint deviceId) {
  getEngine()->setPreferredOutputDevice(deviceId);
}

extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetInputChannelCount(JNIEnv*, jobject,
                                                                                              jint channelCount) {
  getEngine()->setInputChannelCount(channelCount);
}
extern "C" JNIEXPORT void JNICALL Java_com_namamp_app_NativeAudio_nativeSetSelectedInputChannel(JNIEnv*, jobject,
                                                                                                 jint channelIndex) {
  getEngine()->setSelectedInputChannel(channelIndex);
}
