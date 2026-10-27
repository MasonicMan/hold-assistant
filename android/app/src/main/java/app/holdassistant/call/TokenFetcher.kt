package app.holdassistant.call

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Gets a Twilio Voice access token from the user's /token Twilio Function. */
object TokenFetcher {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** Blocking; call off the main thread. */
    fun fetch(tokenUrl: String, secret: String): String {
        val url = tokenUrl.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("key", secret)?.build()
            ?: throw IOException("Token URL isn't a valid https URL")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401) throw IOException("Token server rejected the app secret")
            if (!resp.isSuccessful) throw IOException("Token server error ${resp.code}")
            return JSONObject(body).optString("token").ifEmpty { throw IOException("Token server returned no token") }
        }
    }
}
