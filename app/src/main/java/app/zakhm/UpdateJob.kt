package app.zakhm

import android.app.job.JobParameters
import android.app.job.JobService
import java.io.IOException

/** The periodic background check scheduled by [Updater.schedule]. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Updater.check(this)?.let { Updater.notifyOnce(this, it) }
            } catch (_: IOException) {
                // Offline or the server is down. The next period tries again.
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
