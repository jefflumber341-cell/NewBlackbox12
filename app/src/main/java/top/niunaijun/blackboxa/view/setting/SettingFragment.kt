package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity

class SettingFragment : PreferenceFragmentCompat() {
    private var cameraImagePreference: Preference? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = context ?: return@registerForActivityResult
        val target = File(context.filesDir, "camera_injection.jpg")
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Input stream is null" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppManager.mBlackBoxLoader.invalidCameraInjectionImagePath(target.absolutePath)
            refreshCameraInjectionSummary()
            toast(R.string.camera_injection_set_success)
        }.onFailure {
            toast(R.string.camera_injection_set_failed)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }

        initSendLogs()
        initCameraInjection()
    }

    private fun initCameraInjection() {
        cameraImagePreference = findPreference("camera_injection_image")
        cameraImagePreference?.setOnPreferenceClickListener {
            imagePicker.launch("image/*")
            true
        }
        findPreference<Preference>("camera_injection_clear")?.setOnPreferenceClickListener {
            AppManager.mBlackBoxLoader.invalidCameraInjectionImagePath(null)
            refreshCameraInjectionSummary()
            toast(R.string.camera_injection_cleared)
            true
        }
        refreshCameraInjectionSummary()
    }

    private fun refreshCameraInjectionSummary() {
        val path = AppManager.mBlackBoxLoader.cameraInjectionImagePath()
        cameraImagePreference?.summary =
                if (path.isBlank()) getString(R.string.camera_injection_not_set) else path
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(tmpHide)
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }
    private fun initSendLogs() {
        val sendLogsPreference: Preference? = findPreference("send_logs")
        sendLogsPreference?.setOnPreferenceClickListener {
            it.isEnabled = false
            BlackBoxCore.get()
                    .sendLogs(
                            "Manual Log Upload from Settings",
                            true,
                            object : BlackBoxCore.LogSendListener {
                                override fun onSuccess() {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }

                                override fun onFailure(error: String?) {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }
                            }
                    )
            toast("Sending logs... (Check notifications for status)")
            true
        }
    }
}
