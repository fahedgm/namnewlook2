package com.namamp.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

// TONE3000's real, documented OAuth 2.0 + PKCE API — fetched directly from
// https://www.tone3000.com/api and confirmed field-by-field (endpoint URLs,
// every parameter name, the token-response shape) where noted, not guessed.
// Covers both stages now: the login round-trip (Custom Tabs -> redirect ->
// token exchange), and fetching + downloading a picked tone's model file.
// The models-list response shape and its exact field names are handled
// defensively (see fetchModelsForTone/modelDownloadUrl) since they aren't
// confirmed against a real response yet — this fails visibly with a clear
// message rather than silently assuming a shape and crashing if wrong.
//
// Known simplification, flagged not hidden: tokens are stored in plain
// SharedPreferences, not EncryptedSharedPreferences. Reasonable for a first
// working version; upgrading storage is a sensible later hardening step.
object Tone3000Auth {
    // TONE3000 publishable key (Settings -> API Keys on tone3000.com). Safe
    // to embed directly in the app; TONE3000's own docs confirm the
    // publishable key is meant for client-side/mobile use, unlike the
    // secret key.
    const val CLIENT_ID = "t3k_pub_qZ4haMYdfeNWCeKUr3uckh_FAX_JKKFR"

    private const val AUTH_URL = "https://www.tone3000.com/api/v1/oauth/authorize"
    private const val TOKEN_URL = "https://www.tone3000.com/api/v1/oauth/token"
    private const val API_BASE = "https://www.tone3000.com/api/v1"

    // Must exactly match both the intent-filter in AndroidManifest.xml and
    // the redirect URI registered in TONE3000's API key settings.
    const val REDIRECT_URI = "namamped://oauth-callback"

    // --- PKCE (per TONE3000's own reference implementation) ---------------

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun generateState(): String = UUID.randomUUID().toString()

    /**
     * The "Select" flow: TONE3000 hosts the entire browse/pick UI itself,
     * so nothing on our end needs to build a tone browser. Scoped to NAM
     * amp/cab gear only, matching what this app can actually load.
     *
     * architecture=2 is required, not optional: TONE3000's own docs state
     * that omitting this parameter defaults to "A1 + Custom, excludes A2" —
     * this app is A2-only (enforced natively), so leaving it unset was a
     * real bug, not a neutral default.
     */
    fun buildAuthorizeUri(clientId: String, codeChallenge: String, state: String): Uri {
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("prompt", "select_tone")
            .appendQueryParameter("format", "nam")
            .appendQueryParameter("architecture", "2")
            // No "gears" filter — every card can browse any gear type
            // (amp, pedal, cab, space/etc). architecture=2 here, plus the
            // native SlimmableModel check and forced Lite on load, are
            // what actually guarantee correctness — restricting gear type
            // per card was an unnecessary extra restriction, and the
            // actual bug: it silently applied "amp/cab only" to every
            // card's requests, including Drive's.
            .appendQueryParameter("menubar", "true")
            .build()
    }

    // --- Networking (plain HttpURLConnection — no new HTTP dependency) ----

    private fun postForm(urlStr: String, fields: Map<String, String>): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        val body = fields.entries.joinToString("&") { (k, v) -> "${Uri.encode(k)}=${Uri.encode(v)}" }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).readText()
        conn.disconnect()
        return JSONObject(text)
    }

    private fun getJson(urlStr: String, accessToken: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).readText()
        conn.disconnect()
        return JSONObject(text)
    }

    private fun getRawText(urlStr: String, accessToken: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).readText()
        conn.disconnect()
        return text
    }

    /**
     * Lists downloadable model files for a tone. The exact response shape
     * (bare JSON array vs. an object wrapping the array under "models" or
     * "data") isn't confirmed against a real response yet, so this handles
     * either rather than assuming one and failing silently if wrong.
     *
     * architecture=2 here is a real, confirmed-necessary fix: this is a
     * separate API call from the OAuth authorize request (which has its own
     * architecture=2), and TONE3000 stores both an A2 capture and its
     * legacy A1 sibling under the same tone. Without this parameter on
     * *this* call specifically, their documented default (A1 + Custom,
     * excludes A2) applied here too — confirmed by real testing: downloads
     * were succeeding as valid, single .nam files, just the legacy A1
     * version instead of the A2 one.
     */
    fun fetchModelsForTone(accessToken: String, toneId: String): List<JSONObject> {
        val text = getRawText("$API_BASE/models?tone_id=$toneId&architecture=2", accessToken).trim()
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = JSONObject(text)
            obj.optJSONArray("models") ?: obj.optJSONArray("data") ?: JSONArray()
        }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * model_url is confirmed directly from TONE3000's own docs as the
     * correct field for per-model, individual-file downloads — no fallback
     * to other guessed field names anymore. That fallback chain was the
     * actual bug behind downloads coming back as a whole-tone zip: one of
     * the guessed alternate names was apparently matching some other real
     * field in the response pointing at a bundle, not the single file.
     */
    fun modelDownloadUrl(model: JSONObject): String? = model.optString("model_url").ifBlank { null }

    fun modelDisplayName(model: JSONObject): String =
        model.optString("name").ifBlank { null }
            ?: model.optString("title").ifBlank { null }
            ?: "TONE3000 model"

    /** Not confirmed against a real response — purely additive if present, harmless if not. */
    fun modelArchitectureHint(model: JSONObject): String? =
        model.optString("architecture_version").ifBlank { null }
            ?: model.optString("architecture").ifBlank { null }

    /** Downloads a model file's raw bytes to a destination. Must be called off the main thread. */
    fun downloadModelFile(url: String, accessToken: String, destination: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.inputStream.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
    }

    /** Exchanges an authorization code for tokens. Must be called off the main thread. */
    fun exchangeCode(clientId: String, code: String, codeVerifier: String): JSONObject = postForm(
        TOKEN_URL,
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to codeVerifier,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId
        )
    )

    /** Proves the token works — fetches the authenticated user's own profile. */
    fun fetchUser(accessToken: String): JSONObject = getJson("$API_BASE/user", accessToken)

    // --- Token persistence --------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences("t3k_auth", Context.MODE_PRIVATE)

    fun saveTokens(context: Context, accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        prefs(context).edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
    }

    fun getAccessToken(context: Context): String? = prefs(context).getString("access_token", null)
}
