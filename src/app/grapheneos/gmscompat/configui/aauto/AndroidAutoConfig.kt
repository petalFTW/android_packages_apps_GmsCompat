package app.grapheneos.gmscompat.configui.aauto

import android.Manifest
import android.app.compat.gms.AndroidAutoPackageFlag
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.ext.PackageId
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import app.grapheneos.gmscompat.R
import app.grapheneos.gmscompat.BaseCollapsingToolbarFragment
import app.grapheneos.gmscompat.configui.BaseGosConfigFragment
import app.grapheneos.gmscompat.configui.IssueCheck
import app.grapheneos.gmscompat.configui.addPref
import app.grapheneos.gmscompat.configui.addCategory
import app.grapheneos.gmscompat.getAppInfoOrNull

private const val CONFIG_PKG_NAME = PackageId.ANDROID_AUTO_NAME

// the stubs we ship in assets/aa4mg and can drop in on demand
private data class BundledStub(
    val pkg: String,
    val titleRes: Int,
    val asset: String,
)

private val BUNDLED_STUBS = listOf(
    BundledStub(PackageId.ANDROID_AUTO_NAME, R.string.android_auto, "AndroidAutoStubPrebuilt.apk"),
    BundledStub("com.google.android.apps.maps", R.string.google_maps_app, "Maps.apk"),
    BundledStub("com.google.android.tts", R.string.speech_services_app, "GoogleTTS.apk"),
    BundledStub(PackageId.G_SEARCH_APP_NAME, R.string.google_search_app, "Velvet.apk"),
)

val aautoVoiceIssueChecks: List<IssueCheck> = listOf(
    IssueCheck.PermissionOnly(
        packageName = CONFIG_PKG_NAME,
        permission = Manifest.permission.RECORD_AUDIO,
        issueStringRes = R.string.aauto_issue_aauto_no_microphone_perm
    ),
    IssueCheck.App(
        packageName = PackageId.G_SEARCH_APP_NAME,
        packageId = PackageId.G_SEARCH_APP,
        notInstalledStringRes = R.string.aauto_issue_gsa_not_installed,
        notEnabledStringRes = R.string.aauto_issue_gsa_disabled,
        appChecks = listOf(
            IssueCheck.App.Permission(
                Manifest.permission.INTERNET,
                R.string.aauto_issue_gsa_no_network_perm
            ),
            IssueCheck.App.Permission(
                Manifest.permission.RECORD_AUDIO,
                R.string.aauto_issue_gsa_no_microphone_perm
            ),
        )
    )
)

class AndroidAutoConfigWrapperFragment : BaseCollapsingToolbarFragment() {
    override fun createPreferenceFragment() = AndroidAutoConfigFragment()
}

class AndroidAutoConfigFragment : BaseGosConfigFragment(
    configuringPkgName = CONFIG_PKG_NAME,
    titleStringRes = R.string.android_auto
) {
    lateinit var aautoSettingsPref: Preference
    lateinit var potentialIssues: PreferenceGroup
    lateinit var aautoVoiceCommandIssues: Preference
    private val stubPrefs = mutableMapOf<String, Preference>()

    override fun configurePreferenceScreen(screen: PreferenceScreen) {
        aautoSettingsPref = screen.addPref(getText(R.string.aauto_settings)).apply {
            intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).apply {
                `package` = configuringPkgName
            }
        }

        screen.addCategory(R.string.permissions_category).apply {
            addPkgFlagPerm(
                this, AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRED_ANDROID_AUTO,
                R.string.aauto_wired_perms_title,
                R.string.aauto_wired_perms_confirm,
            )
            addPkgFlagPerm(
                this, AndroidAutoPackageFlag.GRANT_PERMS_FOR_WIRELESS_ANDROID_AUTO,
                R.string.aauto_wireless_perms_title,
                R.string.aauto_wireless_perms_confirm,
            )
            addPkgFlagPerm(
                this, AndroidAutoPackageFlag.GRANT_AUDIO_ROUTING_PERM,
                R.string.audio_routing_perm_title,
                R.string.audio_routing_perm_confirm,
            )
            addPkgFlagPerm(
                this, AndroidAutoPackageFlag.GRANT_PERMS_FOR_ANDROID_AUTO_PHONE_CALLS,
                R.string.aauto_phone_perm_title,
                R.string.aauto_phone_perm_confirm,
            )

            addPref(getText(R.string.aauto_app_info_title)).apply {
                setSummary(R.string.aauto_app_info_summary)
                intent = createAppInfoIntent(configuringPkgName)
            }

            addPref(getText(R.string.notif_listener_settings_title)).apply {
                setSummary(R.string.notif_listener_settings_summary)
                intent = getNotifListenerSettingsIntent()
            }
        }

        potentialIssues = screen.addCategory(R.string.potential_issues_category).apply {
            addPref(getText(R.string.aauto_issue_voice_commands)).let {
                aautoVoiceCommandIssues = it
            }
        }

        screen.addCategory(R.string.optional_deps_category).apply {
            addAppDependencyPref("com.google.android.apps.maps", R.string.google_maps_app)
            addAppDependencyPref("com.google.android.tts", R.string.speech_services_app)
            addAppDependencyPref(PackageId.G_SEARCH_APP_NAME, R.string.google_search_app)
        }

        // petalOS bundled stubs, one tap to install or rip back out
        screen.addCategory(R.string.bundled_stubs_category).apply {
            BUNDLED_STUBS.forEach { addStubPref(this, it) }
        }
    }

    private fun addStubPref(group: PreferenceGroup, stub: BundledStub) {
        val pref = Preference(group.context)
        pref.title = getString(stub.titleRes)
        pref.key = "stub:" + stub.pkg
        stubPrefs[stub.pkg] = pref
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val app = pkgManager.getAppInfoOrNull(stub.pkg)
            when {
                app == null -> StubInstaller.install(
                    requireContext(), stub.asset, stub.pkg, pref.title.toString()
                )
                isOurStub(stub.pkg) -> AlertDialog.Builder(requireContext())
                    .setTitle(pref.title)
                    .setMessage(R.string.stub_uninstall_confirm)
                    .setPositiveButton(R.string.stub_uninstall_action) { _, _ ->
                        StubInstaller.uninstall(
                            requireContext(), stub.pkg, pref.title.toString()
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                // someone else's build (play store etc), leave it alone
                else -> startActivity(createAppInfoIntent(stub.pkg))
            }
            true
        }
        group.addPreference(pref)
    }

    // did we install this one, or is it the real deal?
    private fun isOurStub(pkg: String): Boolean = try {
        pkgManager.getInstallSourceInfo(pkg).installingPackageName == requireContext().packageName
    } catch (e: Exception) {
        false
    }

    private fun refreshStubPrefs() {
        stubPrefs.forEach { (pkg, pref) ->
            val app = pkgManager.getAppInfoOrNull(pkg)
            pref.summary = when {
                app == null -> getText(R.string.stub_not_installed)
                !app.enabled -> getText(R.string.stub_disabled)
                isOurStub(pkg) -> getText(R.string.stub_installed)
                else -> getText(R.string.stub_installed_real)
            }
        }
    }

    override fun updateNonPkgStateUi(applicationInfo: ApplicationInfo) {
        refreshStubPrefs()

        aautoSettingsPref.apply {
            isEnabled = applicationInfo.enabled
            if (applicationInfo.enabled) {
                summary = null
            } else {
                setSummary(R.string.aauto_settings_summary_disabled)
            }
        }

        aautoVoiceCommandIssues.updateWithIssues(
            R.string.aauto_issue_voice_commands_header,
            aautoVoiceIssueChecks
        )
        potentialIssues.isVisible = aautoVoiceCommandIssues.isVisible
    }

    private fun getNotifListenerSettingsIntent(): Intent? {
        val intent = Intent(NotificationListenerService.SERVICE_INTERFACE).apply {
            `package` = PackageId.ANDROID_AUTO_NAME
        }

        val notifListenerServices = pkgManager.queryIntentServices(intent, 0)
        if (notifListenerServices.size != 1) {
            return null
        }

        val nls: ServiceInfo = notifListenerServices.first().serviceInfo

        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            val nlsComponent = ComponentName(nls.packageName, nls.name)
            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, nlsComponent.flattenToString())
        }
    }
}
