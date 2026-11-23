package com.suguna.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Firebase Authentication (anonymous) over REST — no Firebase SDK, no secret.
 *
 * The tablet signs in anonymously once; the refresh token is persisted and short-lived ID
 * tokens are minted from it. The ID token authorises Realtime Database REST calls, and its uid
 * is this device's record id (/devices/<uid>), so the rules keep each tablet to its own node.
 */
object FbAuth {

    private const val TAG = "FbAuth"
    private const val SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
    private const val SECURETOKEN = "https://securetoken.googleapis.com/v1/token"

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUid: String? = null
    @Volatile private var expiresAt: Long = 0L

    fun uid(context: Context): String? =
        cachedUid ?: Prefs(context).fbUid.ifBlank { null }?.also { cachedUid = it }

    @Synchronized
    fun idToken(context: Context): String? {
        if (cachedToken != null && System.currentTimeMillis() < expiresAt - 60_000) return cachedToken
        val prefs = Prefs(context)
        val rt = prefs.fbRefreshToken
        return if (rt.isBlank()) signUpAnonymous(prefs) else refresh(prefs, rt)
    }

    private fun signUpAnonymous(prefs: Prefs): String? {
        val resp = post("$SIGNUP?key=${Config.FIREBASE_API_KEY}",
            JSONObject().put("returnSecureToken", true).toString(), form = false) ?: return null
        return try {
            val o = JSONObject(resp)
            prefs.fbRefreshToken = o.getString("refreshToken")
            prefs.fbUid = o.getString("localId")
            cachedUid = o.getString("localId")
            cache(o.getString("idToken"), o.optString("expiresIn", "3600"))
            cachedToken
        } catch (e: Exception) { Log.w(TAG, "signUp parse: ${e.message}"); null }
    }

    private fun refresh(prefs: Prefs, rt: String): String? {
        val resp = post("$SECURETOKEN?key=${Config.FIREBASE_API_KEY}",
            "grant_type=refresh_token&refresh_token=$rt", form = true) ?: return null
        return try {
            val o = JSONObject(resp)
            prefs.fbRefreshToken = o.getString("refresh_token")
            prefs.fbUid = o.optString("user_id", prefs.fbUid)
            cachedUid = prefs.fbUid
            cache(o.getString("id_token"), o.optString("expires_in", "3600"))
            cachedToken
        } catch (e: Exception) {
            Log.w(TAG, "refresh parse: ${e.message}; dropping token")
            prefs.fbRefreshToken = ""
            null
        }
    }

    private fun cache(token: String, expiresIn: String) {
        cachedToken = token
        expiresAt = System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600) * 1000
    }

    private fun post(url: String, body: String, form: Boolean): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000
        c.doOutput = true
        c.setRequestProperty("Content-Type",
            if (form) "application/x-www-form-urlencoded" else "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        val t = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (ok) t else { Log.w(TAG, "auth http ${c.responseCode}: $t"); null }
    } catch (e: Exception) { Log.w(TAG, "auth post: ${e.message}"); null }
}
