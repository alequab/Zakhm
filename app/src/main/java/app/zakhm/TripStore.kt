package app.zakhm

import app.zakhm.core.TripFile
import app.zakhm.core.TripPoint
import app.zakhm.core.TripSummary
import app.zakhm.core.Trips
import java.io.File
import java.io.IOException

data class SavedTrip(val file: File, val summary: TripSummary)

/**
 * The drives saved on this phone, one small file each. They hold where the car
 * went, so they stay in the app's own storage and leave only when shared.
 */
class TripStore(private val dir: File) {
    /** Newest first. A file that can't be read, or holds no path, is left out. */
    fun list(): List<SavedTrip> = files().mapNotNull { file ->
        val summary = try {
            Trips.summary(read(file))
        } catch (_: IOException) {
            null
        }
        summary?.let { SavedTrip(file, it) }
    }

    /** A new drive's file, after making room by removing the oldest. */
    @Throws(IOException::class)
    fun newFile(stamp: String): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no folder for drives")
        files().drop(MAX_TRIPS - 1).forEach { it.delete() }
        return File(dir, "drive-$stamp.csv")
    }

    fun delete(file: File) {
        // Only ever a drive of this store, whatever is asked.
        if (file.parentFile == dir) file.delete()
    }

    private fun files(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".csv") }.orEmpty().sortedByDescending { it.name }

    companion object {
        private const val MAX_TRIPS = 100

        @Throws(IOException::class)
        fun read(file: File): List<TripPoint> = file.useLines { TripFile.parse(it) }
    }
}
