package com.namamp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.round
import kotlin.math.sin
import org.json.JSONArray
import kotlinx.coroutines.delay
import org.json.JSONObject

data class SlotUiState(
    val status: String = "No model loaded.",
    val fileName: String? = null,
    val archLabel: String? = null,
    val loaded: Boolean = false
)

/** One chain position's 3 loadable file slots, plus which one is currently active/live. */
data class PositionState(
    val slots: List<SlotUiState> = listOf(SlotUiState(), SlotUiState(), SlotUiState()),
    val activeFileIndex: Int = 0,   // which slot is being viewed/targeted for loading
    val playingFileIndex: Int = 0   // which slot is actually feeding the audio thread right now
)

data class ChainPreset(
    val id: String,
    val name: String,
    val driveFileName: String?,
    val ampFileName: String?,
    val modFileName: String?,
    val driveGain: Float,
    val driveLevel: Float,
    val driveTone: Float,
    val ampGain: Float,
    val ampBass: Float,
    val ampMid: Float,
    val ampTreble: Float,
    val ampVolume: Float,
    val modMix: Float,
    val modLevel: Float,
    val driveEnabled: Boolean,
    val ampEnabled: Boolean,
    val modEnabled: Boolean,
    val reverbDwell: Float,
    val reverbMix: Float,
    val reverbLevel: Float,
    val reverbEnabled: Boolean,
    val reverbType: Int,  // 0 = Spring, 1 = Plate, 2 = Hall
    val inputDeviceId: Int,
    val inputChannel: Int,
    val outputDeviceId: Int
)

data class DeviceOption(val id: Int, val label: String, val channelIndex: Int = 0)

data class TunerReading(val noteName: String, val cents: Float, val hasSignal: Boolean)

// Frequency -> note name + cents off, standard equal-temperament math
// (A4 = 440Hz = MIDI note 69). freqHz <= 0 means "no signal detected"
// (see GuitarTuner's silence gate on the native side).
fun frequencyToTunerReading(freqHz: Float): TunerReading {
    if (freqHz <= 0f) return TunerReading("--", 0f, false)
    val noteNumber = 12.0 * log2(freqHz / 440.0) + 69.0
    val rounded = round(noteNumber).toInt()
    val cents = ((noteNumber - rounded) * 100.0).toFloat()
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val noteName = noteNames[((rounded % 12) + 12) % 12]
    val octave = (rounded / 12) - 1
    return TunerReading("$noteName$octave", cents, true)
}

data class T3kChoiceState(
    val position: Int,
    val fileSlot: Int,
    val accessToken: String,
    val models: List<org.json.JSONObject>
)

class MainActivity : ComponentActivity() {

    // Which (position, fileSlot) the in-flight file pick targets. Plain
    // instance vars are fine — set right before launching the picker, read
    // once the result comes back, all on the UI thread.
    private var pendingPosition: Int = 0
    private var pendingFileSlot: Int = 0

    private var onFilePicked: ((Uri?) -> Unit)? = null
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> onFilePicked?.invoke(uri) }

    private var onMicPermissionResult: ((Boolean) -> Unit)? = null
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onMicPermissionResult?.invoke(granted) }

    // Optional, non-blocking: without this the persistent notification just
    // won't show on Android 13+, but the foreground service (and the
    // background-audio protection it provides) still works either way.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way */ }

    private fun startAudioService() {
        startForegroundService(Intent(this, AudioService::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun stopAudioService() {
        stopService(Intent(this, AudioService::class.java))
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // --- Device selection -------------------------------------------------

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (media)"
        else -> "Device"
    }

    private fun listInputDevices(): List<DeviceOption> {
        val am = getSystemService(AudioManager::class.java)
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val options = mutableListOf(DeviceOption(-1, "System default", 0))
        for (d in devices) {
            // An empty array from getChannelCounts() means "supports
            // arbitrary channel counts" per Android's own docs, not "mono
            // only" — but since we don't know the real number in that case,
            // defaulting to 1 (not guessing higher) is the safe choice.
            val maxChannels = d.channelCounts.maxOrNull() ?: 1
            val baseLabel = "${deviceTypeLabel(d.type)} — ${d.productName}"
            if (maxChannels > 1) {
                // One dropdown row per channel — e.g. an interface with
                // separate Hi-Z and Line inputs shows as two distinct
                // selectable rows, picking device and channel together in
                // one tap rather than needing a second control.
                for (ch in 0 until maxChannels) {
                    options.add(DeviceOption(d.id, "$baseLabel (Ch ${ch + 1})", ch))
                }
            } else {
                options.add(DeviceOption(d.id, baseLabel, 0))
            }
        }
        return options
    }

    private fun listOutputDevices(): List<DeviceOption> {
        val am = getSystemService(AudioManager::class.java)
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return listOf(DeviceOption(-1, "System default")) +
            devices.map { DeviceOption(it.id, "${deviceTypeLabel(it.type)} — ${it.productName}") }
    }

    private var onDevicesChanged: (() -> Unit)? = null
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged?.invoke()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            onDevicesChanged?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    override fun onStop() {
        super.onStop()
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
    }
    // -----------------------------------------------------------------------

    // --- TONE3000 login + per-card tone download ---------------------------
    // pendingCodeVerifier/pendingState/pendingPosition/pendingFileSlot are
    // plain instance vars — they only need to survive the trip out to
    // Custom Tabs and back, which stays within this same running process
    // under normal conditions. Known, flagged simplification: if the OS
    // kills the process under memory pressure while Custom Tabs is in the
    // foreground, this state is lost and the redirect fails gracefully with
    // a clear message (state mismatch) rather than crashing.
    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null
    private var onT3kResult: ((String) -> Unit)? = null
    // Delivers a finished (success or failure) SlotUiState for a specific
    // (position, fileSlot) — set inside setContent, reusing the exact same
    // per-slot update path the local file picker already uses.
    private var onT3kModelLoaded: ((Int, Int, SlotUiState) -> Unit)? = null
    // Fires when a tone has more than one model — instead of silently
    // grabbing the first one, the user gets an actual choice.
    private var onT3kModelChoice: ((Int, Int, String, List<JSONObject>) -> Unit)? = null

    private fun connectToTone3000(position: Int, fileSlot: Int) {
        pendingPosition = position
        pendingFileSlot = fileSlot
        val verifier = Tone3000Auth.generateCodeVerifier()
        val challenge = Tone3000Auth.generateCodeChallenge(verifier)
        val state = Tone3000Auth.generateState()
        pendingCodeVerifier = verifier
        pendingState = state
        val uri = Tone3000Auth.buildAuthorizeUri(Tone3000Auth.CLIENT_ID, challenge, state)
        CustomTabsIntent.Builder().build().launchUrl(this, uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTone3000Redirect(intent)
    }

    /** Downloads and loads one specific chosen model. Must be called off the main thread. */
    private fun downloadAndLoadT3kModel(position: Int, fileSlot: Int, accessToken: String, model: JSONObject) {
        try {
            val downloadUrl = Tone3000Auth.modelDownloadUrl(model)
                ?: throw Exception("No model_url in TONE3000's response for this model. Raw data: $model")
            val displayName = Tone3000Auth.modelDisplayName(model)

            val staged = File(cacheDir, "staged-model-$position-$fileSlot.nam")
            Tone3000Auth.downloadModelFile(downloadUrl, accessToken, staged)

            // A .nam file is JSON text; a zip starts with a binary "PK"
            // signature. Checking this before handing it to the native
            // loader turns "silently loads garbage" into a clear message
            // if model_url ever points somewhere unexpected again.
            // (Using read(), not readNBytes() — the latter needs a
            // higher API level than this app's minSdk supports.)
            val header = ByteArray(2)
            val bytesRead = staged.inputStream().use { it.read(header) }
            if (bytesRead == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
                throw Exception("TONE3000 returned a zip, not a single .nam file, for this model.")
            }

            val jsonText = staged.readText()
            val result = NativeAudio.nativeLoadModel(staged.absolutePath, position, fileSlot)
            val loaded = result.startsWith("Model loaded")
            if (loaded) NativeAudio.nativeSetActiveFile(position, fileSlot)

            // If native rejected it, show what the file itself actually
            // declares — real evidence instead of a generic message, so a
            // mismatch between TONE3000's tone-level architecture filter
            // and this specific file's real content is directly visible.
            val displayResult = if (loaded) result else "$result (File declares: ${classifyArchitecture(jsonText)})"

            runOnUiThread {
                onT3kResult?.invoke(if (loaded) "Loaded \"$displayName\" from TONE3000." else displayResult)
                onT3kModelLoaded?.invoke(
                    position, fileSlot,
                    SlotUiState(
                        status = displayResult,
                        fileName = if (loaded) displayName else null,
                        archLabel = if (loaded) classifyArchitecture(jsonText) else null,
                        loaded = loaded
                    )
                )
            }
        } catch (e: Exception) {
            runOnUiThread { onT3kResult?.invoke("TONE3000 error: ${e.message}") }
        }
    }

    private fun handleTone3000Redirect(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "namamped" || uri.host != "oauth-callback") return

        val position = pendingPosition
        val fileSlot = pendingFileSlot

        val error = uri.getQueryParameter("error")
        if (error != null) {
            onT3kResult?.invoke("TONE3000 error: $error")
            return
        }
        if (uri.getQueryParameter("canceled") == "true") {
            onT3kResult?.invoke("Canceled.")
            return
        }
        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val toneId = uri.getQueryParameter("tone_id")
        val verifier = pendingCodeVerifier
        // CSRF protection: the state we get back must match what we sent.
        if (code == null || returnedState == null || verifier == null || returnedState != pendingState) {
            onT3kResult?.invoke("Login callback invalid or expired — please try again.")
            return
        }

        onT3kResult?.invoke("Connecting…")
        Thread {
            try {
                val tokenJson = Tone3000Auth.exchangeCode(Tone3000Auth.CLIENT_ID, code, verifier)
                val accessToken = tokenJson.getString("access_token")
                val refreshToken = tokenJson.optString("refresh_token", "")
                val expiresIn = tokenJson.optLong("expires_in", 3600L)
                Tone3000Auth.saveTokens(this, accessToken, refreshToken, expiresIn)

                if (toneId == null) {
                    // Logged in, but no tone was picked (shouldn't normally
                    // happen with prompt=select_tone success) — nothing to
                    // load into a slot, just confirm the login worked.
                    runOnUiThread { onT3kResult?.invoke("Connected, but no tone was selected.") }
                    return@Thread
                }

                runOnUiThread { onT3kResult?.invoke("Fetching model list…") }
                val models = Tone3000Auth.fetchModelsForTone(accessToken, toneId)
                if (models.isEmpty()) throw Exception("This tone has no downloadable model files.")

                // A tone can bundle multiple distinct captures (e.g. two
                // different mic placements of the same amp) — grabbing the
                // first one silently would be a real, confusing guess.
                // Only auto-load when there's genuinely just one choice.
                if (models.size == 1) {
                    downloadAndLoadT3kModel(position, fileSlot, accessToken, models[0])
                } else {
                    runOnUiThread {
                        onT3kResult?.invoke("Choose a model to load…")
                        onT3kModelChoice?.invoke(position, fileSlot, accessToken, models)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { onT3kResult?.invoke("TONE3000 error: ${e.message}") }
            }
        }.start()
    }
    // -----------------------------------------------------------------------

    private fun getDisplayName(uri: Uri): String {
        var name = "(unknown filename)"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (_: Exception) {
            // Fall back to the placeholder above.
        }
        return name
    }

    // Reports what a file's own JSON actually declares — used both for
    // display on successful loads, and (since a recent change) as a real
    // diagnostic on TONE3000 rejections too. It must not assume A2: an
    // earlier version of this function always prepended "A2" regardless of
    // what was found, which was harmless everywhere it was originally
    // called (only ever on files that had already passed native's real A2
    // check) but became actively misleading once also used on files that
    // hadn't. Parses the JSON properly rather than a raw-text search, so a
    // same-named "architecture" key nested elsewhere (e.g. inside an
    // internal layer's config) can't be mistaken for the real top-level one.
    private fun classifyArchitecture(jsonText: String): String {
        return try {
            val obj = JSONObject(jsonText)
            val arch = obj.optString("architecture", "")
            if (arch.isBlank()) return "Unknown architecture"
            val lower = arch.lowercase()
            if (lower.contains("slim") || lower.contains("container")) "A2 ($arch)" else "A1 ($arch)"
        } catch (e: Exception) {
            "Unknown architecture (couldn't parse file: ${e.message})"
        }
    }

    // --- Preset storage (whole-chain: all 3 positions' files + all knobs) -
    // Metadata in SharedPreferences as JSON; each position's active file
    // copied into permanent storage (not cacheDir, which the OS can clear).
    // Global list capped at 20 — enforced in the UI layer, not here.

    private fun prefs() = getSharedPreferences("nam_amp_chain_presets", MODE_PRIVATE)
    private fun presetListKey() = "chain_presets"
    private fun presetFile(id: String, positionName: String) = File(filesDir, "preset_${id}_$positionName.nam")

    // --- Global "last used" settings — survives app restart/reboot, fixes
    // "have to re-select the interface every time" directly. Separate from
    // presets: this restores automatically on launch regardless of
    // whether any preset gets loaded afterward.
    private fun settingsPrefs() = getSharedPreferences("nam_amp_settings", MODE_PRIVATE)
    private fun saveLastUsedDevices(inputId: Int, inputChannel: Int, outputId: Int) {
        settingsPrefs().edit()
            .putInt("last_input_id", inputId)
            .putInt("last_input_channel", inputChannel)
            .putInt("last_output_id", outputId)
            .apply()
    }
    private fun loadLastInputId(): Int = settingsPrefs().getInt("last_input_id", -1)
    private fun loadLastInputChannel(): Int = settingsPrefs().getInt("last_input_channel", 0)
    private fun loadLastOutputId(): Int = settingsPrefs().getInt("last_output_id", -1)
    // -----------------------------------------------------------------------

    private fun loadPresetIds(): List<String> {
        val raw = prefs().getString(presetListKey(), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePresetIds(ids: List<String>) {
        prefs().edit().putString(presetListKey(), JSONArray(ids).toString()).apply()
    }

    private fun savePresetMeta(p: ChainPreset) {
        val obj = JSONObject()
        obj.put("name", p.name)
        obj.put("driveFileName", p.driveFileName ?: "")
        obj.put("ampFileName", p.ampFileName ?: "")
        obj.put("modFileName", p.modFileName ?: "")
        obj.put("driveGain", p.driveGain)
        obj.put("driveLevel", p.driveLevel)
        obj.put("driveTone", p.driveTone)
        obj.put("ampGain", p.ampGain)
        obj.put("ampBass", p.ampBass)
        obj.put("ampMid", p.ampMid)
        obj.put("ampTreble", p.ampTreble)
        obj.put("ampVolume", p.ampVolume)
        obj.put("modMix", p.modMix)
        obj.put("modLevel", p.modLevel)
        obj.put("driveEnabled", p.driveEnabled)
        obj.put("ampEnabled", p.ampEnabled)
        obj.put("modEnabled", p.modEnabled)
        obj.put("reverbDwell", p.reverbDwell)
        obj.put("reverbMix", p.reverbMix)
        obj.put("reverbLevel", p.reverbLevel)
        obj.put("reverbEnabled", p.reverbEnabled)
        obj.put("reverbType", p.reverbType)
        obj.put("inputDeviceId", p.inputDeviceId)
        obj.put("inputChannel", p.inputChannel)
        obj.put("outputDeviceId", p.outputDeviceId)
        prefs().edit().putString("preset_${p.id}", obj.toString()).apply()
    }

    private fun loadPresetMeta(id: String): ChainPreset? {
        val raw = prefs().getString("preset_$id", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            fun nameOrNull(key: String) = obj.optString(key, "").ifBlank { null }
            ChainPreset(
                id = id,
                name = obj.getString("name"),
                driveFileName = nameOrNull("driveFileName"),
                ampFileName = nameOrNull("ampFileName"),
                modFileName = nameOrNull("modFileName"),
                driveGain = obj.getDouble("driveGain").toFloat(),
                driveLevel = obj.getDouble("driveLevel").toFloat(),
                driveTone = obj.getDouble("driveTone").toFloat(),
                ampGain = obj.getDouble("ampGain").toFloat(),
                ampBass = obj.getDouble("ampBass").toFloat(),
                ampMid = obj.getDouble("ampMid").toFloat(),
                ampTreble = obj.getDouble("ampTreble").toFloat(),
                ampVolume = obj.getDouble("ampVolume").toFloat(),
                modMix = obj.getDouble("modMix").toFloat(),
                modLevel = obj.getDouble("modLevel").toFloat(),
                // optBoolean, not getBoolean: presets saved before this
                // feature existed shouldn't crash on load, and should
                // default to "on" (their original, only) behavior.
                driveEnabled = obj.optBoolean("driveEnabled", true),
                ampEnabled = obj.optBoolean("ampEnabled", true),
                modEnabled = obj.optBoolean("modEnabled", true),
                // Reverb card didn't exist when older presets were saved —
                // default to a modest dwell/mix rather than 0, so an old
                // preset loaded after this update doesn't silently gain a
                // reverb stage that's on but inaudible either way.
                reverbDwell = obj.optDouble("reverbDwell", 30.0).toFloat(),
                reverbMix = obj.optDouble("reverbMix", 30.0).toFloat(),
                reverbLevel = obj.optDouble("reverbLevel", 0.0).toFloat(),
                reverbEnabled = obj.optBoolean("reverbEnabled", true),
                reverbType = obj.optInt("reverbType", 0),
                // -1 is the "System default" sentinel used everywhere else
                // in this app — old presets saved before this field existed
                // fall back to that rather than an invalid device id.
                inputDeviceId = obj.optInt("inputDeviceId", -1),
                inputChannel = obj.optInt("inputChannel", 0),
                outputDeviceId = obj.optInt("outputDeviceId", -1)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAllPresets(): List<ChainPreset> = loadPresetIds().mapNotNull { loadPresetMeta(it) }

    private fun doDeletePreset(id: String) {
        presetFile(id, "drive").delete()
        presetFile(id, "amp").delete()
        presetFile(id, "mod").delete()
        prefs().edit().remove("preset_$id").apply()
        savePresetIds(loadPresetIds() - id)
    }

    /** Copies each position's currently-ACTIVE staged file (if any) into permanent, preset-scoped storage. */
    private fun doSavePreset(
        name: String,
        driveState: PositionState,
        ampState: PositionState,
        modState: PositionState,
        driveGain: Float,
        driveLevel: Float,
        driveTone: Float,
        ampGain: Float,
        ampBass: Float,
        ampMid: Float,
        ampTreble: Float,
        ampVolume: Float,
        modMix: Float,
        modLevel: Float,
        driveEnabled: Boolean,
        ampEnabled: Boolean,
        modEnabled: Boolean,
        reverbDwell: Float,
        reverbMix: Float,
        reverbLevel: Float,
        reverbEnabled: Boolean,
        reverbType: Int,
        inputDeviceId: Int,
        inputChannel: Int,
        outputDeviceId: Int
    ): ChainPreset {
        val id = System.currentTimeMillis().toString()

        // Uses playingFileIndex (what's actually live), not activeFileIndex
        // (what's merely being viewed/targeted) — a real distinction since
        // hot-swap: you can view a spare slot you've loaded into without
        // it being what's actually feeding the audio chain, and a preset
        // must always capture what's genuinely making sound.
        fun copyActive(positionIndex: Int, positionName: String, state: PositionState): String? {
            val playingSlot = state.slots.getOrNull(state.playingFileIndex) ?: return null
            if (!playingSlot.loaded) return null
            val staged = File(cacheDir, "staged-model-$positionIndex-${state.playingFileIndex}.nam")
            if (!staged.exists()) return null
            return try {
                staged.copyTo(presetFile(id, positionName), overwrite = true)
                playingSlot.fileName
            } catch (_: Exception) {
                null
            }
        }

        val driveFileName = copyActive(NativeAudio.POSITION_DRIVE, "drive", driveState)
        val ampFileName = copyActive(NativeAudio.POSITION_AMP, "amp", ampState)
        val modFileName = copyActive(NativeAudio.POSITION_MOD, "mod", modState)

        val preset = ChainPreset(
            id, name, driveFileName, ampFileName, modFileName,
            driveGain, driveLevel, driveTone,
            ampGain, ampBass, ampMid, ampTreble, ampVolume,
            modMix, modLevel,
            driveEnabled, ampEnabled, modEnabled,
            reverbDwell, reverbMix, reverbLevel, reverbEnabled, reverbType,
            inputDeviceId, inputChannel, outputDeviceId
        )
        savePresetMeta(preset)
        savePresetIds(loadPresetIds() + id)
        return preset
    }
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var driveState by remember { mutableStateOf(PositionState()) }
            var ampState by remember { mutableStateOf(PositionState()) }
            var modState by remember { mutableStateOf(PositionState()) }
            var audioStatus by remember { mutableStateOf("Audio stopped.") }
            var audioRunning by remember { mutableStateOf(false) }
            var t3kStatus by remember { mutableStateOf("Not connected.") }
            onT3kResult = { status -> t3kStatus = status }

            var t3kChoice by remember { mutableStateOf<T3kChoiceState?>(null) }
            onT3kModelChoice = { position, fileSlot, accessToken, models ->
                t3kChoice = T3kChoiceState(position, fileSlot, accessToken, models)
            }

            // Every knob drives real, independent per-position DSP in the
            // native audio callback — see native-lib.cpp's chain comment.
            var driveGain by remember { mutableFloatStateOf(0f) }
            var driveLevel by remember { mutableFloatStateOf(0f) }
            var driveTone by remember { mutableFloatStateOf(0f) }
            var ampGain by remember { mutableFloatStateOf(0f) }
            var ampBass by remember { mutableFloatStateOf(0f) }
            var ampMid by remember { mutableFloatStateOf(0f) }
            var ampTreble by remember { mutableFloatStateOf(0f) }
            var ampVolume by remember { mutableFloatStateOf(0f) }
            var modMix by remember { mutableFloatStateOf(100f) }
            var modLevel by remember { mutableFloatStateOf(0f) }
            var reverbDwell by remember { mutableFloatStateOf(30f) }
            var reverbMix by remember { mutableFloatStateOf(30f) }
            var reverbLevel by remember { mutableFloatStateOf(0f) }

            var driveEnabled by remember { mutableStateOf(true) }
            var ampEnabled by remember { mutableStateOf(true) }
            var modEnabled by remember { mutableStateOf(true) }
            var reverbEnabled by remember { mutableStateOf(true) }
            var reverbType by remember { mutableIntStateOf(0) }  // 0 = Spring, 1 = Plate, 2 = Hall

            var presets by remember { mutableStateOf(loadAllPresets()) }
            var showSaveDialog by remember { mutableStateOf(false) }
            var presetNameInput by remember { mutableStateOf("") }

            var inputDevices by remember { mutableStateOf(listInputDevices()) }
            var outputDevices by remember { mutableStateOf(listOutputDevices()) }
            var selectedInputId by remember { mutableIntStateOf(loadLastInputId()) }
            var selectedOutputId by remember { mutableIntStateOf(loadLastOutputId()) }
            var selectedInputChannel by remember { mutableIntStateOf(loadLastInputChannel()) }

            LaunchedEffect(Unit) {
                // Restored Kotlin state above needs to actually reach the
                // native engine once — its own atomics still hold their
                // hardcoded defaults until told otherwise.
                if (selectedInputId != -1) {
                    NativeAudio.nativeSetInputDevice(selectedInputId)
                    val channelCountForDevice = inputDevices.count { it.id == selectedInputId }
                    NativeAudio.nativeSetInputChannelCount(if (channelCountForDevice > 0) channelCountForDevice else 1)
                    NativeAudio.nativeSetSelectedInputChannel(selectedInputChannel)
                }
                if (selectedOutputId != -1) {
                    NativeAudio.nativeSetOutputDevice(selectedOutputId)
                }
            }

            onDevicesChanged = {
                inputDevices = listInputDevices()
                outputDevices = listOutputDevices()
            }

            fun stateFor(position: Int) = when (position) {
                NativeAudio.POSITION_DRIVE -> driveState
                NativeAudio.POSITION_AMP -> ampState
                else -> modState
            }
            fun setStateFor(position: Int, s: PositionState) {
                when (position) {
                    NativeAudio.POSITION_DRIVE -> driveState = s
                    NativeAudio.POSITION_AMP -> ampState = s
                    else -> modState = s
                }
            }

            onT3kModelLoaded = { position, fileSlot, newSlotState ->
                val current = stateFor(position)
                val newSlots = current.slots.toMutableList()
                newSlots[fileSlot] = newSlotState
                // A TONE3000 download already activates the slot natively
                // (see downloadAndLoadT3kModel) — playingFileIndex needs to
                // match, not just activeFileIndex, or the UI would show the
                // wrong slot as "Playing".
                setStateFor(position, current.copy(slots = newSlots, activeFileIndex = fileSlot, playingFileIndex = fileSlot))
            }

            onFilePicked = { uri ->
                if (uri != null) {
                    val position = pendingPosition
                    val fileSlot = pendingFileSlot
                    val current = stateFor(position)
                    fun updateSlot(newSlotState: SlotUiState) {
                        val newSlots = current.slots.toMutableList()
                        newSlots[fileSlot] = newSlotState
                        setStateFor(position, current.copy(slots = newSlots, activeFileIndex = fileSlot))
                    }
                    updateSlot(SlotUiState(status = "Loading…"))
                    try {
                        val displayName = getDisplayName(uri)
                        val staged = File(cacheDir, "staged-model-$position-$fileSlot.nam")
                        var jsonText = ""
                        contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readBytes()
                            jsonText = bytes.toString(Charsets.UTF_8)
                            staged.writeBytes(bytes)
                        }
                        val result = NativeAudio.nativeLoadModel(staged.absolutePath, position, fileSlot)
                        val loaded = result.startsWith("Model loaded")
                        if (loaded) NativeAudio.nativeSetActiveFile(position, fileSlot)
                        updateSlot(
                            SlotUiState(
                                status = result,
                                fileName = if (loaded) displayName else null,
                                archLabel = if (loaded) classifyArchitecture(jsonText) else null,
                                loaded = loaded
                            )
                        )
                    } catch (e: Exception) {
                        updateSlot(SlotUiState(status = "Error: ${e.message}"))
                    }
                }
            }

            onMicPermissionResult = { granted ->
                if (granted) {
                    audioStatus = NativeAudio.nativeStartAudio()
                    audioRunning = audioStatus.startsWith("Live.")
                    if (audioRunning) startAudioService()
                } else {
                    audioStatus = "Microphone permission denied — can't start audio without it."
                }
            }

            fun applyPreset(preset: ChainPreset) {
                fun loadInto(position: Int, positionName: String, fileName: String?) {
                    if (fileName == null) return
                    val file = presetFile(preset.id, positionName)
                    if (!file.exists()) return
                    val jsonText = try { file.readText() } catch (_: Exception) { "" }
                    val result = NativeAudio.nativeLoadModel(file.absolutePath, position, 0)
                    val loaded = result.startsWith("Model loaded")
                    if (loaded) NativeAudio.nativeSetActiveFile(position, 0)
                    val newSlot = SlotUiState(
                        status = result,
                        fileName = fileName,
                        archLabel = if (loaded) classifyArchitecture(jsonText) else null,
                        loaded = loaded
                    )
                    val current = stateFor(position)
                    val newSlots = current.slots.toMutableList()
                    newSlots[0] = newSlot
                    setStateFor(position, current.copy(slots = newSlots, activeFileIndex = 0, playingFileIndex = 0))
                }
                loadInto(NativeAudio.POSITION_DRIVE, "drive", preset.driveFileName)
                loadInto(NativeAudio.POSITION_AMP, "amp", preset.ampFileName)
                loadInto(NativeAudio.POSITION_MOD, "mod", preset.modFileName)

                driveGain = preset.driveGain; NativeAudio.nativeSetDriveGain(preset.driveGain)
                driveLevel = preset.driveLevel; NativeAudio.nativeSetDriveLevel(preset.driveLevel)
                driveTone = preset.driveTone; NativeAudio.nativeSetDriveTone(preset.driveTone)
                ampGain = preset.ampGain; NativeAudio.nativeSetAmpGain(preset.ampGain)
                ampBass = preset.ampBass; NativeAudio.nativeSetAmpBass(preset.ampBass)
                ampMid = preset.ampMid; NativeAudio.nativeSetAmpMid(preset.ampMid)
                ampTreble = preset.ampTreble; NativeAudio.nativeSetAmpTreble(preset.ampTreble)
                ampVolume = preset.ampVolume; NativeAudio.nativeSetAmpVolume(preset.ampVolume)
                modMix = preset.modMix; NativeAudio.nativeSetModMix(preset.modMix)
                modLevel = preset.modLevel; NativeAudio.nativeSetModLevel(preset.modLevel)
                reverbDwell = preset.reverbDwell; NativeAudio.nativeSetReverbDwell(preset.reverbDwell)
                reverbMix = preset.reverbMix; NativeAudio.nativeSetReverbMix(preset.reverbMix)
                reverbLevel = preset.reverbLevel; NativeAudio.nativeSetReverbLevel(preset.reverbLevel)

                driveEnabled = preset.driveEnabled; NativeAudio.nativeSetDriveEnabled(preset.driveEnabled)
                ampEnabled = preset.ampEnabled; NativeAudio.nativeSetAmpEnabled(preset.ampEnabled)
                modEnabled = preset.modEnabled; NativeAudio.nativeSetModEnabled(preset.modEnabled)
                reverbEnabled = preset.reverbEnabled; NativeAudio.nativeSetReverbEnabled(preset.reverbEnabled)
                reverbType = preset.reverbType; NativeAudio.nativeSetReverbType(preset.reverbType)

                // Only apply a saved device if it's still actually
                // connected — a preset saved with one interface plugged in
                // shouldn't silently select a device id that doesn't
                // correspond to anything currently present.
                if (preset.inputDeviceId == -1 || inputDevices.any { it.id == preset.inputDeviceId }) {
                    selectedInputId = preset.inputDeviceId
                    selectedInputChannel = preset.inputChannel
                    NativeAudio.nativeSetInputDevice(preset.inputDeviceId)
                    val channelCountForDevice = inputDevices.count { it.id == preset.inputDeviceId }
                    NativeAudio.nativeSetInputChannelCount(if (channelCountForDevice > 0) channelCountForDevice else 1)
                    NativeAudio.nativeSetSelectedInputChannel(preset.inputChannel)
                    saveLastUsedDevices(preset.inputDeviceId, preset.inputChannel, selectedOutputId)
                }
                if (preset.outputDeviceId == -1 || outputDevices.any { it.id == preset.outputDeviceId }) {
                    selectedOutputId = preset.outputDeviceId
                    NativeAudio.nativeSetOutputDevice(preset.outputDeviceId)
                    saveLastUsedDevices(selectedInputId, selectedInputChannel, preset.outputDeviceId)
                }
            }

            if (showSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showSaveDialog = false; presetNameInput = "" },
                    title = { Text("Save preset") },
                    text = {
                        OutlinedTextField(
                            value = presetNameInput,
                            onValueChange = { presetNameInput = it },
                            label = { Text("Preset name") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = presetNameInput.ifBlank { "Untitled" }
                            doSavePreset(
                                name, driveState, ampState, modState,
                                driveGain, driveLevel, driveTone,
                                ampGain, ampBass, ampMid, ampTreble, ampVolume,
                                modMix, modLevel,
                                driveEnabled, ampEnabled, modEnabled,
                                reverbDwell, reverbMix, reverbLevel, reverbEnabled, reverbType,
                                selectedInputId, selectedInputChannel, selectedOutputId
                            )
                            presets = loadAllPresets()
                            showSaveDialog = false
                            presetNameInput = ""
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveDialog = false; presetNameInput = "" }) { Text("Cancel") }
                    }
                )
            }

            t3kChoice?.let { choice ->
                AlertDialog(
                    onDismissRequest = { t3kChoice = null },
                    title = { Text("This tone has ${choice.models.size} models") },
                    text = {
                        Column {
                            choice.models.forEach { model ->
                                TextButton(onClick = {
                                    t3kChoice = null
                                    Thread {
                                        downloadAndLoadT3kModel(choice.position, choice.fileSlot, choice.accessToken, model)
                                    }.start()
                                }) {
                                    val hint = Tone3000Auth.modelArchitectureHint(model)
                                    Text(Tone3000Auth.modelDisplayName(model) + (hint?.let { " ($it)" } ?: ""))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { t3kChoice = null }) { Text("Cancel") }
                    }
                )
            }

            AppScreen(
                driveState = driveState,
                ampState = ampState,
                modState = modState,
                audioStatus = audioStatus,
                audioRunning = audioRunning,
                nativeError = NativeAudio.loadError,
                t3kStatus = t3kStatus,
                onConnectT3kClick = { position, fileSlot -> connectToTone3000(position, fileSlot) },
                driveGain = driveGain,
                onDriveGainChange = { v -> driveGain = v; NativeAudio.nativeSetDriveGain(v) },
                driveLevel = driveLevel,
                onDriveLevelChange = { v -> driveLevel = v; NativeAudio.nativeSetDriveLevel(v) },
                driveTone = driveTone,
                onDriveToneChange = { v -> driveTone = v; NativeAudio.nativeSetDriveTone(v) },
                ampGain = ampGain,
                onAmpGainChange = { v -> ampGain = v; NativeAudio.nativeSetAmpGain(v) },
                ampBass = ampBass,
                onAmpBassChange = { v -> ampBass = v; NativeAudio.nativeSetAmpBass(v) },
                ampMid = ampMid,
                onAmpMidChange = { v -> ampMid = v; NativeAudio.nativeSetAmpMid(v) },
                ampTreble = ampTreble,
                onAmpTrebleChange = { v -> ampTreble = v; NativeAudio.nativeSetAmpTreble(v) },
                ampVolume = ampVolume,
                onAmpVolumeChange = { v -> ampVolume = v; NativeAudio.nativeSetAmpVolume(v) },
                modMix = modMix,
                onModMixChange = { v -> modMix = v; NativeAudio.nativeSetModMix(v) },
                modLevel = modLevel,
                onModLevelChange = { v -> modLevel = v; NativeAudio.nativeSetModLevel(v) },
                reverbDwell = reverbDwell,
                onReverbDwellChange = { v -> reverbDwell = v; NativeAudio.nativeSetReverbDwell(v) },
                reverbMix = reverbMix,
                onReverbMixChange = { v -> reverbMix = v; NativeAudio.nativeSetReverbMix(v) },
                reverbLevel = reverbLevel,
                onReverbLevelChange = { v -> reverbLevel = v; NativeAudio.nativeSetReverbLevel(v) },
                driveEnabled = driveEnabled,
                onDriveEnabledChange = { v -> driveEnabled = v; NativeAudio.nativeSetDriveEnabled(v) },
                ampEnabled = ampEnabled,
                onAmpEnabledChange = { v -> ampEnabled = v; NativeAudio.nativeSetAmpEnabled(v) },
                modEnabled = modEnabled,
                onModEnabledChange = { v -> modEnabled = v; NativeAudio.nativeSetModEnabled(v) },
                reverbEnabled = reverbEnabled,
                onReverbEnabledChange = { v -> reverbEnabled = v; NativeAudio.nativeSetReverbEnabled(v) },
                reverbType = reverbType,
                onReverbTypeChange = { v -> reverbType = v; NativeAudio.nativeSetReverbType(v) },
                onSelectSlot = { position, fileSlot ->
                    // Viewing/targeting a slot no longer activates it for
                    // playback — that's onUseSlot below. This is what makes
                    // hot-swap possible: you can view and load into a
                    // different slot than the one currently playing,
                    // without touching the pointer the audio thread reads.
                    setStateFor(position, stateFor(position).copy(activeFileIndex = fileSlot))
                },
                onUseSlot = { position, fileSlot ->
                    setStateFor(position, stateFor(position).copy(playingFileIndex = fileSlot))
                    NativeAudio.nativeSetActiveFile(position, fileSlot)
                },
                onLoadClick = { position, fileSlot ->
                    pendingPosition = position
                    pendingFileSlot = fileSlot
                    pickFileLauncher.launch("*/*")
                },
                presets = presets,
                onSavePresetClick = { showSaveDialog = true },
                onLoadPresetClick = { p -> applyPreset(p) },
                onDeletePresetClick = { p -> doDeletePreset(p.id); presets = loadAllPresets() },
                inputDevices = inputDevices,
                outputDevices = outputDevices,
                selectedInputId = selectedInputId,
                selectedOutputId = selectedOutputId,
                selectedInputChannel = selectedInputChannel,
                onSelectInputDevice = { id, channel ->
                    selectedInputId = id
                    selectedInputChannel = channel
                    NativeAudio.nativeSetInputDevice(id)
                    // How many channels this device supports is recoverable
                    // from how many rows it contributed to the dropdown —
                    // one row per channel for multi-channel devices (see
                    // listInputDevices), so this needs no separate lookup.
                    val channelCountForDevice = inputDevices.count { it.id == id }
                    NativeAudio.nativeSetInputChannelCount(if (channelCountForDevice > 0) channelCountForDevice else 1)
                    NativeAudio.nativeSetSelectedInputChannel(channel)
                    saveLastUsedDevices(id, channel, selectedOutputId)
                },
                onSelectOutputDevice = { id ->
                    selectedOutputId = id
                    NativeAudio.nativeSetOutputDevice(id)
                    saveLastUsedDevices(selectedInputId, selectedInputChannel, id)
                },
                onStartAudioClick = {
                    if (hasMicPermission()) {
                        audioStatus = NativeAudio.nativeStartAudio()
                        audioRunning = audioStatus.startsWith("Live.")
                        if (audioRunning) startAudioService()
                    } else {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopAudioClick = {
                    NativeAudio.nativeStopAudio()
                    stopAudioService()
                    audioStatus = "Audio stopped."
                    audioRunning = false
                }
            )
        }
    }
}

private val AmpColorScheme = darkColorScheme(
    background = Color(0xFF1A1F29),
    surface = Color(0xFF232A38),
    primary = Color(0xFFFF8A00),
    onPrimary = Color(0xFF1A1F29),
    secondary = Color(0xFFFFB74D),
    onBackground = Color(0xFFF2F2F2),
    onSurface = Color(0xFFF2F2F2)
)

// Per-card accent overrides. Nesting one of these around a card's content
// re-colors its buttons, its on/off switch, and (since RotaryKnob now reads
// MaterialTheme.colorScheme.primary instead of a hardcoded color) its knobs
// too — one consistent mechanism for everything in that card, rather than
// threading a color parameter through every individual component.
private val DriveColorScheme = AmpColorScheme.copy(primary = Color(0xFF58A45A))
private val AmpSectionColorScheme = AmpColorScheme.copy(primary = Color(0xFFC9A828))
private val ModColorScheme = AmpColorScheme.copy(primary = Color(0xFF4F9EB8))
private val ReverbColorScheme = AmpColorScheme.copy(primary = Color(0xFF9B59B6))

// Subtle per-stage card tints — a muted blend of each stage's accent color
// into the base dark background (roughly 15% mix), not the full-saturation
// accent itself. A fully saturated background would fight with the knobs
// and switches already using that same color at full strength on top of it.
private val DriveTint = Color(0xFF2C372C)
private val AmpTint = Color(0xFF3D3825)
private val ModTint = Color(0xFF2A363A)
private val ReverbTint = Color(0xFF362C3A)

@Composable
fun InfoText(text: String) {
    Text(text, fontSize = 8.sp)
}

@Composable
fun SmallButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 1.dp)
    ) {
        content()
    }
}

@Composable
fun SectionCard(
    highlighted: Boolean = false,
    backgroundColor: Color = Color(0xFF232A38),
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(if (highlighted) 2.dp else 1.5.dp, if (highlighted) Color(0xFFFF8A00) else Color(0xFF4A5468)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

// Real rotary knob: Canvas-drawn dial turned by vertical drag (up =
// increase). unit defaults to "dB" (with a +/- prefix); pass unit = "%" for
// percentage-style controls like Modulation's Mix, which don't want a sign.
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -24f..24f,
    unit: String = "dB"
) {
    val pointerColor = MaterialTheme.colorScheme.primary
    val range = valueRange.endInclusive - valueRange.start
    val latestValue = rememberUpdatedState(value)
    val latestOnChange = rememberUpdatedState(onValueChange)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val sensitivity = range / 300f
                        val newValue = (latestValue.value - dragAmount.y * sensitivity)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        latestOnChange.value(newValue)
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color(0xFF2F2F2F), radius = radius, center = center)
            drawCircle(color = Color(0xFF3A3A3A), radius = radius * 0.78f, center = center)
            val frac = ((value - valueRange.start) / range).coerceIn(0f, 1f)
            val angleDeg = -135.0 + frac * 270.0
            val angleRad = Math.toRadians(angleDeg)
            val pointerLen = radius * 0.65f
            val end = Offset(
                x = center.x + (pointerLen * sin(angleRad)).toFloat(),
                y = center.y - (pointerLen * cos(angleRad)).toFloat()
            )
            drawLine(color = pointerColor, start = center, end = end, strokeWidth = 4f, cap = StrokeCap.Round)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
        val sign = if (unit == "dB" && value >= 0) "+" else ""
        InfoText("$sign${"%.1f".format(value)} $unit")
    }
}

// Classic tuner-needle visual: pivots from the bottom-center, sweeping left
// (flat) to right (sharp), centered/vertical when exactly in tune. Reuses
// the same angle-to-Offset formula already proven correct in RotaryKnob,
// just with a bottom pivot point instead of a center one, and a smaller,
// fixed sweep range (cents, not an arbitrary knob value).
@Composable
fun TunerNeedle(cents: Float, needleColor: Color) {
    Canvas(modifier = Modifier.size(140.dp, 80.dp)) {
        val pivot = Offset(size.width / 2f, size.height)
        val needleLength = size.height * 0.92f
        val clampedCents = cents.coerceIn(-50f, 50f)
        val angleDeg = (clampedCents / 50f) * 45.0
        val angleRad = Math.toRadians(angleDeg)
        val end = Offset(
            x = pivot.x + (needleLength * sin(angleRad)).toFloat(),
            y = pivot.y - (needleLength * cos(angleRad)).toFloat()
        )
        drawLine(color = needleColor, start = pivot, end = end, strokeWidth = 5f, cap = StrokeCap.Round)
        drawCircle(color = needleColor, radius = 7f, center = pivot)
    }
}

@Composable
fun DeviceSelector(
    label: String,
    options: List<DeviceOption>,
    selectedId: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.id == selectedId }?.label ?: "System default"
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            SmallButton(onClick = { expanded = true }) {
                Text(currentLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelect(option.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// Input-specific: each option already carries its own channel index (see
// listInputDevices — multi-channel devices produce one row per channel), so
// picking device and channel happens together in one dropdown, one tap, no
// separate channel control needed.
@Composable
fun InputDeviceSelector(
    options: List<DeviceOption>,
    selectedId: Int,
    selectedChannel: Int,
    onSelect: (Int, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.id == selectedId && it.channelIndex == selectedChannel }?.label
        ?: "System default"
    Column {
        Text("Input", style = MaterialTheme.typography.labelMedium)
        Box {
            SmallButton(onClick = { expanded = true }) {
                Text(currentLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelect(option.id, option.channelIndex)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// A dropdown of the 3 loadable file slots for one chain position. Viewing a
// slot (dropdown selection) and playing a slot are now separate — this is
// what makes hot-swapping possible: you can view and load into a different,
// currently-silent slot while audio keeps running, since the audio thread
// only ever reads whichever slot is actually marked "playing" (see
// PositionState.playingFileIndex). Load and TONE3000 are only blocked when
// their target would be that specific playing slot while live — loading
// into any other slot, or switching which already-loaded slot plays via
// Use, both work without stopping audio.
@Composable
fun FileSlotSelector(
    state: PositionState,
    audioRunning: Boolean,
    onSelectSlot: (Int) -> Unit,
    onUseSlot: (Int) -> Unit,
    onLoadClick: (Int) -> Unit,
    onConnectT3kClick: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeSlot = state.slots.getOrNull(state.activeFileIndex) ?: SlotUiState()
    val isViewingPlaying = state.activeFileIndex == state.playingFileIndex
    val activeLabel = (activeSlot.fileName ?: "Slot ${state.activeFileIndex + 1} (empty)") +
        (if (isViewingPlaying) " \u2022 Playing" else "")

    // Same target-selection logic the TONE3000 button itself uses, computed
    // here too so its enabled state can check whether that target would
    // land on the currently-playing slot.
    val t3kEmptySlot = state.slots.indexOfFirst { !it.loaded }
    val t3kTarget = if (t3kEmptySlot >= 0) t3kEmptySlot else state.activeFileIndex

    Column {
        // Full-width with ellipsis truncation — a long file name can no
        // longer push the buttons off-screen, regardless of how long a
        // future name might be.
        Box(modifier = Modifier.fillMaxWidth()) {
            SmallButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(activeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (i in state.slots.indices) {
                    val itemLabel = (state.slots[i].fileName ?: "Slot ${i + 1} (empty)") +
                        (if (i == state.playingFileIndex) " \u2022 Playing" else "")
                    DropdownMenuItem(
                        text = { Text(itemLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onSelectSlot(i)
                            expanded = false
                        }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
            SmallButton(
                onClick = { onLoadClick(state.activeFileIndex) },
                enabled = !(audioRunning && isViewingPlaying)
            ) {
                Text("Load")
            }
            SmallButton(
                onClick = { onUseSlot(state.activeFileIndex) },
                enabled = activeSlot.loaded && !isViewingPlaying
            ) {
                Text("Use")
            }
            SmallButton(
                onClick = { onConnectT3kClick(t3kTarget) },
                enabled = !(audioRunning && t3kTarget == state.playingFileIndex)
            ) {
                Text("TONE3000")
            }
        }
        if (activeSlot.archLabel != null) InfoText("Architecture: ${activeSlot.archLabel}")
        InfoText(activeSlot.status)
    }
}

// Each stage's real content (title, switch, file selector where applicable,
// knobs) extracted into its own composable so it can render identically
// whether shown inline (Overview) or full-screen (Compact, opened) —
// avoiding maintaining two separate copies of the same knob-wiring code.
@Composable
fun DriveStageBody(
    driveState: PositionState,
    audioRunning: Boolean,
    driveEnabled: Boolean,
    onDriveEnabledChange: (Boolean) -> Unit,
    driveGain: Float,
    onDriveGainChange: (Float) -> Unit,
    driveLevel: Float,
    onDriveLevelChange: (Float) -> Unit,
    driveTone: Float,
    onDriveToneChange: (Float) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onUseSlot: (Int) -> Unit,
    onLoadClick: (Int) -> Unit,
    onConnectT3kClick: (Int) -> Unit
) {
    MaterialTheme(colorScheme = DriveColorScheme) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Drive", style = MaterialTheme.typography.titleMedium)
                Switch(checked = driveEnabled, onCheckedChange = onDriveEnabledChange)
            }
            Column(modifier = Modifier.alpha(if (driveEnabled) 1f else 0.5f)) {
                FileSlotSelector(driveState, audioRunning, onSelectSlot, onUseSlot, onLoadClick, onConnectT3kClick)
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryKnob("Gain", driveGain, onDriveGainChange)
                    RotaryKnob("Level", driveLevel, onDriveLevelChange)
                    RotaryKnob("Tone", driveTone, onDriveToneChange)
                }
            }
        }
    }
}

@Composable
fun AmpStageBody(
    ampState: PositionState,
    audioRunning: Boolean,
    ampEnabled: Boolean,
    onAmpEnabledChange: (Boolean) -> Unit,
    ampGain: Float,
    onAmpGainChange: (Float) -> Unit,
    ampBass: Float,
    onAmpBassChange: (Float) -> Unit,
    ampMid: Float,
    onAmpMidChange: (Float) -> Unit,
    ampTreble: Float,
    onAmpTrebleChange: (Float) -> Unit,
    ampVolume: Float,
    onAmpVolumeChange: (Float) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onUseSlot: (Int) -> Unit,
    onLoadClick: (Int) -> Unit,
    onConnectT3kClick: (Int) -> Unit
) {
    MaterialTheme(colorScheme = AmpSectionColorScheme) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Amp", style = MaterialTheme.typography.titleMedium)
                Switch(checked = ampEnabled, onCheckedChange = onAmpEnabledChange)
            }
            Column(modifier = Modifier.alpha(if (ampEnabled) 1f else 0.5f)) {
                FileSlotSelector(ampState, audioRunning, onSelectSlot, onUseSlot, onLoadClick, onConnectT3kClick)
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryKnob("Gain", ampGain, onAmpGainChange)
                    RotaryKnob("Bass", ampBass, onAmpBassChange)
                    RotaryKnob("Mid", ampMid, onAmpMidChange)
                }
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryKnob("Treble", ampTreble, onAmpTrebleChange)
                    RotaryKnob("Volume", ampVolume, onAmpVolumeChange)
                }
            }
        }
    }
}

@Composable
fun ModStageBody(
    modState: PositionState,
    audioRunning: Boolean,
    modEnabled: Boolean,
    onModEnabledChange: (Boolean) -> Unit,
    modMix: Float,
    onModMixChange: (Float) -> Unit,
    modLevel: Float,
    onModLevelChange: (Float) -> Unit,
    onSelectSlot: (Int) -> Unit,
    onUseSlot: (Int) -> Unit,
    onLoadClick: (Int) -> Unit,
    onConnectT3kClick: (Int) -> Unit
) {
    MaterialTheme(colorScheme = ModColorScheme) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Modulation", style = MaterialTheme.typography.titleMedium)
                Switch(checked = modEnabled, onCheckedChange = onModEnabledChange)
            }
            Column(modifier = Modifier.alpha(if (modEnabled) 1f else 0.5f)) {
                FileSlotSelector(modState, audioRunning, onSelectSlot, onUseSlot, onLoadClick, onConnectT3kClick)
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    RotaryKnob("Mix", modMix, onModMixChange, valueRange = 0f..100f, unit = "%")
                    RotaryKnob("Level", modLevel, onModLevelChange)
                }
            }
        }
    }
}

@Composable
fun ReverbStageBody(
    reverbEnabled: Boolean,
    onReverbEnabledChange: (Boolean) -> Unit,
    reverbType: Int,
    onReverbTypeChange: (Int) -> Unit,
    reverbDwell: Float,
    onReverbDwellChange: (Float) -> Unit,
    reverbMix: Float,
    onReverbMixChange: (Float) -> Unit,
    reverbLevel: Float,
    onReverbLevelChange: (Float) -> Unit
) {
    MaterialTheme(colorScheme = ReverbColorScheme) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reverb", style = MaterialTheme.typography.titleMedium)
                Switch(checked = reverbEnabled, onCheckedChange = onReverbEnabledChange)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.alpha(if (reverbEnabled) 1f else 0.5f)
            ) {
                val typeNames = listOf("Spring", "Plate", "Hall")
                typeNames.forEachIndexed { index, typeName ->
                    SmallButton(
                        onClick = { onReverbTypeChange(index) },
                        enabled = reverbEnabled,
                        modifier = Modifier.alpha(if (reverbType == index) 1f else 0.4f)
                    ) {
                        Text(typeName)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().alpha(if (reverbEnabled) 1f else 0.5f)
            ) {
                RotaryKnob("Dwell", reverbDwell, onReverbDwellChange, valueRange = 0f..100f, unit = "%")
                RotaryKnob("Mix", reverbMix, onReverbMixChange, valueRange = 0f..100f, unit = "%")
                RotaryKnob("Level", reverbLevel, onReverbLevelChange)
            }
        }
    }
}

// Compact mode's collapsed list row: title, switch, and a read-only values
// summary, tinted to match the stage's identity, tappable to open the
// full-screen editor. The Switch is its own nested interactive element —
// Compose gives it priority for its own tap area, so toggling it doesn't
// also trigger the row's onClick.
@Composable
fun CollapsedStageRow(
    title: String,
    tint: Color,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    valuesText: String,
    onClick: () -> Unit
) {
    SectionCard(backgroundColor = tint) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            InfoText(valuesText)
        }
    }
}

// A thin, tappable strip showing a neighboring card's title, tinted to
// match its identity — enough to see what's above/below without losing
// the dedicated space given to the currently open card.
@Composable
fun PeekStrip(title: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    driveState: PositionState,
    ampState: PositionState,
    modState: PositionState,
    audioStatus: String,
    audioRunning: Boolean,
    nativeError: String?,
    t3kStatus: String,
    onConnectT3kClick: (Int, Int) -> Unit,
    driveGain: Float,
    onDriveGainChange: (Float) -> Unit,
    driveLevel: Float,
    onDriveLevelChange: (Float) -> Unit,
    driveTone: Float,
    onDriveToneChange: (Float) -> Unit,
    ampGain: Float,
    onAmpGainChange: (Float) -> Unit,
    ampBass: Float,
    onAmpBassChange: (Float) -> Unit,
    ampMid: Float,
    onAmpMidChange: (Float) -> Unit,
    ampTreble: Float,
    onAmpTrebleChange: (Float) -> Unit,
    ampVolume: Float,
    onAmpVolumeChange: (Float) -> Unit,
    modMix: Float,
    onModMixChange: (Float) -> Unit,
    modLevel: Float,
    onModLevelChange: (Float) -> Unit,
    reverbDwell: Float,
    onReverbDwellChange: (Float) -> Unit,
    reverbMix: Float,
    onReverbMixChange: (Float) -> Unit,
    reverbLevel: Float,
    onReverbLevelChange: (Float) -> Unit,
    driveEnabled: Boolean,
    onDriveEnabledChange: (Boolean) -> Unit,
    ampEnabled: Boolean,
    onAmpEnabledChange: (Boolean) -> Unit,
    modEnabled: Boolean,
    onModEnabledChange: (Boolean) -> Unit,
    reverbEnabled: Boolean,
    onReverbEnabledChange: (Boolean) -> Unit,
    reverbType: Int,
    onReverbTypeChange: (Int) -> Unit,
    onSelectSlot: (Int, Int) -> Unit,
    onUseSlot: (Int, Int) -> Unit,
    onLoadClick: (Int, Int) -> Unit,
    presets: List<ChainPreset>,
    onSavePresetClick: () -> Unit,
    onLoadPresetClick: (ChainPreset) -> Unit,
    onDeletePresetClick: (ChainPreset) -> Unit,
    inputDevices: List<DeviceOption>,
    outputDevices: List<DeviceOption>,
    selectedInputId: Int,
    selectedOutputId: Int,
    selectedInputChannel: Int,
    onSelectInputDevice: (Int, Int) -> Unit,
    onSelectOutputDevice: (Int) -> Unit,
    onStartAudioClick: () -> Unit,
    onStopAudioClick: () -> Unit
) {
    MaterialTheme(colorScheme = AmpColorScheme) {
        Scaffold(
            containerColor = AmpColorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Nam Amped") },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (audioRunning) Color(0xFF4CAF50) else Color(0xFF666666),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (audioRunning) "Live" else "Stopped", fontSize = 10.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1A1F29),
                        titleContentColor = Color(0xFFF2F2F2)
                    )
                )
            }
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (nativeError != null) {
                        Text("Native library failed to load:")
                        InfoText(nativeError)
                    } else {
                        var selectedTab by remember { mutableIntStateOf(0) }
                        val tabLabels = listOf("Chain", "Setup", "Presets")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF141922), RoundedCornerShape(24.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            tabLabels.forEachIndexed { index, label ->
                                val selected = selectedTab == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(if (selected) Color(0xFF2A3040) else Color.Transparent)
                                        .then(
                                            if (selected) Modifier.border(1.dp, Color(0xFFFF8A00), RoundedCornerShape(20.dp))
                                            else Modifier
                                        )
                                        .clickable { selectedTab = index }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) Color(0xFFFF8A00) else Color(0xFFF2F2F2),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            }
                        }

                        if (selectedTab == 0) {
                        var tunerEnabled by remember { mutableStateOf(false) }
                        var tunerReading by remember { mutableStateOf(TunerReading("--", 0f, false)) }

                        // Only polls while both the tuner is on AND audio is
                        // actually running — the native analysis loop only
                        // advances while live, so polling otherwise would
                        // just repeatedly read a stale value for nothing.
                        LaunchedEffect(tunerEnabled, audioRunning) {
                            if (tunerEnabled && audioRunning) {
                                while (true) {
                                    val freq = NativeAudio.nativeGetTunerFrequency()
                                    tunerReading = frequencyToTunerReading(freq)
                                    delay(150)
                                }
                            } else {
                                tunerReading = TunerReading("--", 0f, false)
                            }
                        }

                        val tunerCardColor = when {
                            !tunerEnabled || !tunerReading.hasSignal -> Color(0xFF232A38)
                            abs(tunerReading.cents) <= 5f -> Color(0xFF2E7D32)
                            abs(tunerReading.cents) <= 15f -> Color(0xFFB8860B)
                            else -> Color(0xFFB71C1C)
                        }

                        SectionCard(backgroundColor = tunerCardColor) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Tuner", style = MaterialTheme.typography.titleMedium)
                                Switch(
                                    checked = tunerEnabled,
                                    onCheckedChange = { enabled ->
                                        tunerEnabled = enabled
                                        NativeAudio.nativeSetTunerEnabled(enabled)
                                    },
                                    enabled = audioRunning
                                )
                            }
                            if (tunerEnabled) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                    Text(tunerReading.noteName, style = MaterialTheme.typography.headlineMedium)
                                    TunerNeedle(tunerReading.cents, Color.White)
                                    if (tunerReading.hasSignal) {
                                        val sign = if (tunerReading.cents >= 0) "+" else ""
                                        InfoText("$sign${"%.1f".format(tunerReading.cents)} cents")
                                    } else {
                                        InfoText("Play a note\u2026")
                                    }
                                }
                            } else {
                                InfoText("Mutes live output while active.")
                            }
                        }

                        Text("Drive \u2192 Amp \u2192 Modulation, always in series. Load a file into each, start audio, plug in and play.")

                        val driveValues = "Gain ${"%+.1f".format(driveGain)}dB, Level ${"%+.1f".format(driveLevel)}dB, Tone ${"%+.1f".format(driveTone)}dB"
                        val driveBody: @Composable () -> Unit = {
                            DriveStageBody(
                                driveState, audioRunning, driveEnabled, onDriveEnabledChange,
                                driveGain, onDriveGainChange, driveLevel, onDriveLevelChange, driveTone, onDriveToneChange,
                                { i -> onSelectSlot(0, i) }, { i -> onUseSlot(0, i) }, { i -> onLoadClick(0, i) }, { fileSlot -> onConnectT3kClick(0, fileSlot) }
                            )
                        }

                        val ampValues = "Gain ${"%+.1f".format(ampGain)}dB, Bass ${"%+.1f".format(ampBass)}dB, Mid ${"%+.1f".format(ampMid)}dB, Treble ${"%+.1f".format(ampTreble)}dB, Volume ${"%+.1f".format(ampVolume)}dB"
                        val ampBody: @Composable () -> Unit = {
                            AmpStageBody(
                                ampState, audioRunning, ampEnabled, onAmpEnabledChange,
                                ampGain, onAmpGainChange, ampBass, onAmpBassChange, ampMid, onAmpMidChange,
                                ampTreble, onAmpTrebleChange, ampVolume, onAmpVolumeChange,
                                { i -> onSelectSlot(1, i) }, { i -> onUseSlot(1, i) }, { i -> onLoadClick(1, i) }, { fileSlot -> onConnectT3kClick(1, fileSlot) }
                            )
                        }

                        val modValues = "Mix ${modMix.toInt()}%, Level ${"%+.1f".format(modLevel)}dB"
                        val modBody: @Composable () -> Unit = {
                            ModStageBody(
                                modState, audioRunning, modEnabled, onModEnabledChange,
                                modMix, onModMixChange, modLevel, onModLevelChange,
                                { i -> onSelectSlot(2, i) }, { i -> onUseSlot(2, i) }, { i -> onLoadClick(2, i) }, { fileSlot -> onConnectT3kClick(2, fileSlot) }
                            )
                        }

                        val reverbTypeNames = listOf("Spring", "Plate", "Hall")
                        val reverbValues = "${reverbTypeNames[reverbType]}, Dwell ${reverbDwell.toInt()}%, Mix ${reverbMix.toInt()}%, Level ${"%+.1f".format(reverbLevel)}dB"
                        val reverbBody: @Composable () -> Unit = {
                            ReverbStageBody(
                                reverbEnabled, onReverbEnabledChange, reverbType, onReverbTypeChange,
                                reverbDwell, onReverbDwellChange, reverbMix, onReverbMixChange, reverbLevel, onReverbLevelChange
                            )
                        }

                        var chainOverview by remember { mutableStateOf(true) }
                        var openCardIndex by remember { mutableStateOf<Int?>(null) }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmallButton(onClick = { chainOverview = true; openCardIndex = null }, enabled = !chainOverview) {
                                Text("Overview")
                            }
                            SmallButton(onClick = { chainOverview = false }, enabled = chainOverview) {
                                Text("Compact")
                            }
                        }

                        if (chainOverview) {
                            SectionCard(backgroundColor = DriveTint) { driveBody() }
                            SectionCard(backgroundColor = AmpTint) { ampBody() }
                            SectionCard(backgroundColor = ModTint) { modBody() }
                            SectionCard(backgroundColor = ReverbTint) { reverbBody() }
                        } else if (openCardIndex == null) {
                            CollapsedStageRow("Drive", DriveTint, driveEnabled, onDriveEnabledChange, driveValues) { openCardIndex = 0 }
                            CollapsedStageRow("Amp", AmpTint, ampEnabled, onAmpEnabledChange, ampValues) { openCardIndex = 1 }
                            CollapsedStageRow("Modulation", ModTint, modEnabled, onModEnabledChange, modValues) { openCardIndex = 2 }
                            CollapsedStageRow("Reverb", ReverbTint, reverbEnabled, onReverbEnabledChange, reverbValues) { openCardIndex = 3 }
                        } else {
                            val idx = openCardIndex!!
                            val stages = listOf(
                                Triple("Drive", DriveTint, driveBody),
                                Triple("Amp", AmpTint, ampBody),
                                Triple("Modulation", ModTint, modBody),
                                Triple("Reverb", ReverbTint, reverbBody)
                            )

                            SmallButton(onClick = { openCardIndex = null }) { Text("\u2715 Close") }

                            // Swipe up/down between adjacent stages, matching
                            // the same forward/backward direction scrolling
                            // already uses elsewhere in the app — keyed on
                            // idx so the gesture detector's captured value
                            // is always fresh after each navigation, not
                            // stale from before the last swipe.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(idx) {
                                        var totalDrag = 0f
                                        detectVerticalDragGestures(
                                            onDragStart = { totalDrag = 0f },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                totalDrag += dragAmount
                                            },
                                            onDragEnd = {
                                                val threshold = 100f
                                                if (totalDrag < -threshold && idx < stages.size - 1) {
                                                    openCardIndex = idx + 1
                                                } else if (totalDrag > threshold && idx > 0) {
                                                    openCardIndex = idx - 1
                                                }
                                            }
                                        )
                                    },
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (idx > 0) {
                                    val (prevTitle, prevTint, _) = stages[idx - 1]
                                    PeekStrip(prevTitle, prevTint) { openCardIndex = idx - 1 }
                                }
                                val (_, tint, body) = stages[idx]
                                SectionCard(backgroundColor = tint) { body() }
                                if (idx < stages.size - 1) {
                                    val (nextTitle, nextTint, _) = stages[idx + 1]
                                    PeekStrip(nextTitle, nextTint) { openCardIndex = idx + 1 }
                                }
                            }
                        }

                        SectionCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallButton(onClick = onStartAudioClick, enabled = !audioRunning) {
                                    Text("Start audio")
                                }
                                SmallButton(onClick = onStopAudioClick, enabled = audioRunning) {
                                    Text("Stop audio")
                                }
                            }
                            InfoText(audioStatus)
                        }
                        }

                        if (selectedTab == 1) {
                        SectionCard {
                            Text("Input / Output", style = MaterialTheme.typography.titleMedium)
                            InputDeviceSelector(
                                inputDevices, selectedInputId, selectedInputChannel, onSelectInputDevice
                            )
                            DeviceSelector("Output", outputDevices, selectedOutputId, onSelectOutputDevice)
                            InfoText("Device changes apply the next time you tap Start audio.")
                        }

                        SectionCard {
                            Text("Buffer / Latency", style = MaterialTheme.typography.titleMedium)
                            var bufferStatus by remember { mutableStateOf("Audio not running.") }
                            // Confirmed directly from Oboe's own docs: getXRunCount() lets
                            // you actually see glitches happening, and setBufferSizeInFrames()
                            // lets you trade latency for headroom against them at runtime —
                            // this polls that status once a second while audio is live.
                            LaunchedEffect(audioRunning) {
                                if (audioRunning) {
                                    while (true) {
                                        bufferStatus = NativeAudio.nativeGetBufferStatus()
                                        delay(1000)
                                    }
                                } else {
                                    bufferStatus = "Audio not running."
                                }
                            }
                            InfoText(bufferStatus)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallButton(
                                    onClick = { bufferStatus = NativeAudio.nativeAdjustBufferSize(-1) },
                                    enabled = audioRunning
                                ) {
                                    Text("▼", fontSize = 20.sp)
                                }
                                SmallButton(
                                    onClick = { bufferStatus = NativeAudio.nativeAdjustBufferSize(1) },
                                    enabled = audioRunning
                                ) {
                                    Text("▲", fontSize = 20.sp)
                                }
                            }
                        }
                        }

                        if (selectedTab == 2) {
                        SectionCard {
                            Text("Presets", style = MaterialTheme.typography.titleMedium)
                            SmallButton(onClick = onSavePresetClick, enabled = presets.size < 20) {
                                Text(if (presets.size < 20) "Save preset" else "Preset list full (20/20)")
                            }

                            var activePresetId by remember { mutableStateOf<String?>(null) }
                            var pendingDeleteId by remember { mutableStateOf<String?>(null) }

                            if (presets.isEmpty()) {
                                InfoText("No presets saved yet.")
                            } else {
                                presets.forEachIndexed { index, preset ->
                                    val isActive = preset.id == activePresetId
                                    // Wide contrast gap between the two
                                    // shades so alternating rows are clearly
                                    // visible, not just a subtle hint.
                                    val rowColor = if (index % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF141414)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(rowColor, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isActive) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(Color(0xFF4CAF50), CircleShape)
                                                )
                                            }
                                            Text(preset.name, style = MaterialTheme.typography.titleMedium)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            SmallButton(onClick = {
                                                activePresetId = preset.id
                                                onLoadPresetClick(preset)
                                            }) {
                                                Text("Use", fontSize = 11.sp)
                                            }
                                            SmallButton(onClick = {
                                                if (pendingDeleteId == preset.id) {
                                                    // Second tap on the same preset — actually
                                                    // delete, and clear its active/pending state
                                                    // so nothing points at a now-gone preset.
                                                    if (activePresetId == preset.id) activePresetId = null
                                                    pendingDeleteId = null
                                                    onDeletePresetClick(preset)
                                                } else {
                                                    pendingDeleteId = preset.id
                                                }
                                            }) {
                                                Text(if (pendingDeleteId == preset.id) "Confirm?" else "\uD83D\uDDD1", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
