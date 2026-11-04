package app.speedg

import app.speedg.core.AvailableUpdate
import app.speedg.core.UpdatePolicy
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Asks the update server for the latest release. Failures just mean no update is offered. */
object UpdateChecker {
    private const val TIMEOUT_MS = 10_000

    /** Blocks on the network, so call off the main thread. */
    fun check(): AvailableUpdate? {
        if (BuildConfig.UPDATE_URL.isEmpty()) return null
        return try {
            val connection = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val version = json.getString("version")
                val url = json.getString("url")
                val offer = UpdatePolicy.isNewer(BuildConfig.VERSION_NAME, version) && UpdatePolicy.isTrustedUrl(url)
                if (offer) AvailableUpdate(version, url) else null
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
