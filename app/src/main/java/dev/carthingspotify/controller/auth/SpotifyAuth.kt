package dev.carthingspotify.controller.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class SpotifyAuth(private val context: Context, private val store: SecureTokenStore) {
    companion object {
        const val REDIRECT_URI = "http://127.0.0.1:25566/callback"
        private const val SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state user-read-recently-played user-library-read user-library-modify playlist-read-private playlist-read-collaborative"
    }

    private val executor = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    fun begin(onResult: (Result<Unit>) -> Unit) {
        val clientId = store.clientId
        if (clientId.isBlank()) {
            onResult(Result.failure(IllegalStateException("Enter a Spotify Client ID first")))
            return
        }
        val verifier = Pkce.newVerifier()
        val state = Pkce.newVerifier().take(32)
        executor.execute {
            try {
                server?.close()
                server = ServerSocket(25566, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 180_000 }
                val url = "https://accounts.spotify.com/authorize?" + form(
                    mapOf(
                        "client_id" to clientId,
                        "response_type" to "code",
                        "redirect_uri" to REDIRECT_URI,
                        "code_challenge_method" to "S256",
                        "code_challenge" to Pkce.challenge(verifier),
                        "state" to state,
                        "scope" to SCOPES,
                        "show_dialog" to "true"
                    )
                )
                main.post {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val socket = server!!.accept().apply { soTimeout = 10_000 }
                val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val uri = Uri.parse("http://127.0.0.1$target")
                val responseHtml = "<!doctype html><meta name=viewport content='width=device-width'><style>body{background:#101412;color:white;font:24px sans-serif;text-align:center;padding-top:20vh}b{color:#1ed760}</style><b>Connected.</b><p>Returning to Car Thing…</p><script>location='carthingctl://oauth/done'</script>"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${responseHtml.toByteArray().size}\r\nConnection: close\r\n\r\n$responseHtml"
                socket.getOutputStream().write(response.toByteArray())
                socket.close()

                val error = uri.getQueryParameter("error")
                val returnedState = uri.getQueryParameter("state")
                val code = uri.getQueryParameter("code")
                if (error != null) error(error)
                if (returnedState != state) error("OAuth state did not match")
                if (code.isNullOrBlank()) error("Spotify did not return an authorization code")
                exchangeCode(code, verifier)
                main.post { onResult(Result.success(Unit)) }
            } catch (e: Exception) {
                main.post { onResult(Result.failure(e)) }
            } finally {
                try { server?.close() } catch (_: Exception) { }
                server = null
            }
        }
    }

    @Synchronized
    fun validAccessToken(): String? {
        val current = store.load() ?: return null
        if (current.expiresAtMs > System.currentTimeMillis() + 60_000L) return current.accessToken
        return refresh(current)
    }

    private fun exchangeCode(code: String, verifier: String) {
        val body = form(
            mapOf(
                "client_id" to store.clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier
            )
        )
        val json = tokenRequest(body)
        val refresh = json.optString("refresh_token")
        if (refresh.isBlank()) error("Spotify did not return a refresh token")
        store.save(OAuthTokens(json.getString("access_token"), refresh, expiry(json)))
    }

    private fun refresh(current: OAuthTokens): String? = try {
        val body = form(
            mapOf(
                "client_id" to store.clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to current.refreshToken
            )
        )
        val json = tokenRequest(body)
        val access = json.getString("access_token")
        store.save(OAuthTokens(access, json.optString("refresh_token", current.refreshToken), expiry(json)))
        access
    } catch (_: Exception) {
        null
    }

    private fun tokenRequest(body: String): JSONObject {
        val connection = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        val text = streamText(connection)
        if (connection.responseCode !in 200..299) error("Spotify authorization failed (${connection.responseCode}): $text")
        return JSONObject(text)
    }

    private fun expiry(json: JSONObject) = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L

    private fun streamText(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}
