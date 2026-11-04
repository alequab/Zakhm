package app.zakhm.core

/** A newer release the user can download. */
data class AvailableUpdate(val version: String, val url: String)

object UpdatePolicy {
    /** Only ever open downloads from this project's own GitHub releases. */
    private const val TRUSTED_URL_PREFIX = "https://github.com/alequab/Zakhm/releases/"

    fun isTrustedUrl(url: String) = url.startsWith(TRUSTED_URL_PREFIX) && ".." !in url

    /** Compares dotted versions such as `1.2.0` or `v1.2.0`. Anything unparsable is not newer. */
    fun isNewer(current: String, latest: String): Boolean {
        val a = parse(current) ?: return false
        val b = parse(latest) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val parts = version.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
        return parts.ifEmpty { null }
    }
}
