package app.zakhm.core

import org.json.JSONException
import org.json.JSONObject

/** A release offered by the update server. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val sha256: String,
    val size: Long,
)

object UpdateInfo {
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")

    /**
     * Reads the server's `/latest` answer. Anything incomplete or newer-looking but
     * malformed gives null, so a bad answer just means no update is offered.
     */
    fun parse(text: String, arabic: Boolean): AvailableUpdate? = try {
        val json = JSONObject(text)
        val update = AvailableUpdate(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            notes = json.optString(if (arabic) "notesAr" else "notesEn"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.getLong("size"),
        )
        val valid = update.versionCode > 0 && update.versionName.isNotBlank() &&
            SHA256.matches(update.sha256) && update.size in 1..MAX_APK_BYTES
        if (valid) update else null
    } catch (_: JSONException) {
        null
    }
}
