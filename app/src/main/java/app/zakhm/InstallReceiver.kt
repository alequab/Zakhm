package app.zakhm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.flow.MutableSharedFlow

/** Acts on the answer from Android's installer: ask for confirmation, or reopen the app once it's updated. */
private fun handleInstallResult(context: Context, intent: Intent) {
    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmationScreen(intent)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PackageInstaller.STATUS_SUCCESS -> context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.ended.tryEmit(true)
        else -> {
            InstallEvents.ended.tryEmit(false)
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}

/** Tells the open app that an install it started ended without updating. True means the user cancelled. */
object InstallEvents {
    val ended = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
}

private fun confirmationScreen(intent: Intent): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

/**
 * Where the installer reports. It's an invisible screen because Android stops a
 * background receiver from opening an app, but lets the installer open this.
 */
class InstallResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleInstallResult(this, intent)
        finish()
    }
}

/** Updates started by versions before 1.4.3 report here. Android may block the reopen from a receiver. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = handleInstallResult(context, intent)
}
