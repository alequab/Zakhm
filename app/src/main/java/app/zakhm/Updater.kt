package app.zakhm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import app.zakhm.core.AvailableUpdate
import app.zakhm.core.UpdateInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the app from its update server. `GET {server}/latest` describes the
 * newest release and `GET {server}/apk/{versionCode}` serves its APK. The APK's
 * size and SHA-256 are checked first, and Android itself refuses one that isn't
 * signed with this app's key. The user always confirms on Android's own screen.
 */
object Updater {
    const val EXTRA_SHOW_UPDATE = "show_update"

    private const val JOB_ID = 7001
    private const val NOTIFICATION_ID = 7002
    private const val CHANNEL = "updates"
    private const val PREFERENCES = "updater"
    private const val KEY_NOTIFIED = "notified"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_INFO_BYTES = 64 * 1024

    private val server: String get() = BuildConfig.UPDATE_URL.trim().trimEnd('/')

    private val enabled: Boolean get() = server.startsWith("https://")

    private fun isArabic(context: Context) =
        context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** The newest release if it's newer than this app. Blocks on the network. */
    fun check(context: Context): AvailableUpdate? {
        if (!enabled) return null
        val text = open("$server/latest").use { connection ->
            val received = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = connection.stream.read(buffer)
                if (count <= 0) break
                received.write(buffer, 0, count)
                if (received.size() > MAX_INFO_BYTES) throw IOException("Answer too large")
            }
            received.toString("UTF-8")
        }
        return UpdateInfo.parse(text, isArabic(context))?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    /** Fetches and verifies the APK. Blocks on the network. */
    fun download(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit): File {
        val file = File(context.cacheDir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        open("$server/apk/${update.versionCode}").use { connection ->
            file.outputStream().use { out ->
                val buffer = ByteArray(16 * 1024)
                var lastPercent = -1
                while (true) {
                    val count = connection.stream.read(buffer)
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    done += count
                    if (done > update.size) throw IOException("File is larger than expected")
                    val percent = (done * 100 / update.size).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (done != update.size || hash != update.sha256) {
            file.delete()
            throw IOException("Downloaded file is damaged")
        }
        return file
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "install unknown apps" page for this app. */
    fun openInstallPermission(context: Context) {
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands the APK to Android's installer. The answer arrives in [InstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val result = Intent(context, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
        }
    }

    /** Keeps a background check running, even after the phone restarts. */
    fun schedule(context: Context) {
        if (!enabled) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(CHECK_INTERVAL_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** Tells the user about a release, once per version. */
    fun notifyOnce(context: Context, update: AvailableUpdate) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_NOTIFIED, 0) >= update.versionCode) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channelName = context.getString(R.string.update_channel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_DEFAULT))

        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(R.string.update_available, update.versionName)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(update.notes)
            .setStyle(Notification.BigTextStyle().bigText(update.notes))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            preferences.edit().putInt(KEY_NOTIFIED, update.versionCode).apply()
        } catch (_: SecurityException) {
            // Notifications are refused. The banner in the app still offers the update.
        }
    }

    private class Connection(private val connection: HttpURLConnection) : AutoCloseable {
        val stream = connection.inputStream
        override fun close() = connection.disconnect()
    }

    private fun open(url: String): Connection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("Server answered $code")
        }
        return Connection(connection)
    }
}
