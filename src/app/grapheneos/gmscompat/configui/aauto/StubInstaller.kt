package app.grapheneos.gmscompat.configui.aauto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.widget.Toast
import app.grapheneos.gmscompat.R

// petalOS: install/uninstall the bundled AA stubs without a privileged installer.
// non-priv callers get STATUS_PENDING_USER_ACTION and we forward that to the
// system confirm dialog.
object StubInstaller {
    const val ACTION_RESULT = "app.grapheneos.gmscompat.STUB_RESULT"
    const val EXTRA_LABEL = "label"

    fun install(ctx: Context, assetName: String, pkg: String, label: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(pkg)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                ctx.assets.open("aa4mg/$assetName").use { input ->
                    session.openWrite("stub.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(statusIntent(ctx, sessionId, label))
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "$label: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun uninstall(ctx: Context, pkg: String, label: String) {
        try {
            ctx.packageManager.packageInstaller.uninstall(
                pkg, statusIntent(ctx, pkg.hashCode(), label)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "$label: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun statusIntent(ctx: Context, request: Int, label: String): IntentSender {
        val intent = Intent(ctx, StubResultReceiver::class.java)
            .setAction(ACTION_RESULT)
            .putExtra(EXTRA_LABEL, label)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, request, intent, flags).intentSender
    }
}

// the system answers back here, pending-user-action means "show the dialog"
class StubResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val label = intent.getStringExtra(StubInstaller.EXTRA_LABEL).orEmpty()
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(ctx, ctx.getString(R.string.stub_done, label), Toast.LENGTH_SHORT)
                    .show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    ctx, ctx.getString(R.string.stub_failed, label, msg), Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
