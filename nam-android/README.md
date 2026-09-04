# NAM Amp — Android (Milestone 2: live audio)

This is a genuine step up from Milestone 1: it now processes **live audio**
through the real NeuralAmpModelerCore engine — mic in, through the loaded
model, out to your speaker/headphones — using Oboe (Google's low-latency
audio library).

## What it does

1. **Load .nam file** — same as before, loads through the real `nam::get_dsp()`.
2. **Start audio** — requests microphone permission if needed, then opens a
   real-time full-duplex audio stream and starts processing your guitar
   signal through the loaded model.
3. **Stop audio** — closes the stream cleanly. Also happens automatically if
   you leave the app (see limitation below).

## How the audio engine was built

Every API call here — `nam::DSP::process()`'s exact signature, and Oboe's
`FullDuplexStream` setup (`setCallback`, `setSharedInputStream`/
`setSharedOutputStream`, the output-stream-first ordering) — was copied from
real, current, verified sources: Steve Atkinson's own NeuralAmpModelerPlugin
for the model side, and Oboe's own wiki (as of Oct 2025) for the audio side.
Not guessed.

## Known limitations — real, not hidden

- **Sample rate**: the model is prepared for whatever rate the device
  actually negotiates, rather than using proper resampling (which would need
  the `ResamplingNAM` wrapper from the plugin repo, not included here). In
  practice, nearly all modern Android devices negotiate 48000 Hz — the same
  rate NAM models are normally trained at — so this should match. If it
  doesn't on some device, audio would sound slightly off in pitch/speed, not
  crash.
- **Mono only**: models with more than 1 input or output channel are
  rejected with a clear message rather than mishandled.
- **No foreground service yet**: audio stops when you leave the app
  (`onPause`). A real "keep playing with the screen off" amp needs a
  foreground Service with a notification — genuinely useful next step, not
  built yet.
- **No knobs/EQ/tone stack yet** — this is the neural model running raw.
  Gain/Bass/Mid/Treble/Level (as built for the earlier web version) is the
  natural next addition, now that real audio is flowing.
- **Model swapping while running isn't safe** — the UI prevents loading a
  new model while audio is active, but this isn't enforced at the native
  level with a lock (deliberately, to keep the realtime audio callback
  lock-free). Stop audio before loading a different model.

## Setup

Same as before: create a public GitHub repo, drag the unzipped project
folders in (`.github` and `nam-android` should land as separate top-level
folders — this matters, see the earlier build-failure history if unsure),
commit, and watch the Actions tab. Build errors go through the same
"expand the log, send it over" process as always.
