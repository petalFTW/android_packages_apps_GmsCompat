package app.grapheneos.gmscompat

import android.ext.PackageId
import android.permission.PermissionManager
import android.util.Log
import com.android.internal.gmscompat.WalletCompat

object WalletCompatSettings {
    private const val KEY = "wallet_attestation_serial_access"
    private const val TAG = "WalletCompatSettings"

    @JvmStatic
    fun isEnabled(): Boolean = App.preferences().getBoolean(KEY, false)

    private fun isSupported(): Boolean = try {
        val info = App.ctx().packageManager.getApplicationInfo(PackageId.GMS_CORE_NAME, 0)
        info.ext().packageId == PackageId.GMS_CORE &&
            info.longVersionCode == WalletCompat.SUPPORTED_GMS_VERSION
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun setPermission(enabled: Boolean): Boolean =
        App.ctx().getSystemService(PermissionManager::class.java)!!
            .setGmsWalletPrivilegedPhoneState(enabled)

    /** Reconcile on config reload/startup, including removal of the old manual test grant. */
    @JvmStatic
    @Synchronized
    fun reconcilePermission(): Boolean {
        val enabled = isEnabled() && isSupported()
        return try {
            val applied = setPermission(enabled)
            if (!applied) Log.e(TAG, "Unable to reconcile Wallet permission")
            applied && enabled
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to reconcile Wallet permission", e)
            false
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !isSupported()) return false
        val previous = isEnabled()
        // Config updates can restart this process; persist the opt-in/opt-out first.
        if (!App.preferences().edit().putBoolean(KEY, enabled).commit()) {
            // SharedPreferences changes its in-memory value even if the disk write fails.
            App.preferences().edit().putBoolean(KEY, previous).commit()
            return false
        }
        var applied = false
        try {
            applied = setPermission(enabled)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to change Wallet permission", e)
        }
        if (!applied) {
            // Keep the UI/config at the previous state on failure. Startup reconciliation
            // retries a persisted intent after a process or Binder failure.
            if (!App.preferences().edit().putBoolean(KEY, previous).commit()) {
                Log.e(TAG, "Unable to restore Wallet preference after permission failure")
            }
        }
        BinderGms2Gca.updateWalletConfig()
        return applied
    }
}
