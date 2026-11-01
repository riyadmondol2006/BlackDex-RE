package top.niunaijun.blackdex.view.setting

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackdex.BuildConfig
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.app.App
import top.niunaijun.blackdex.app.AppManager
import top.niunaijun.blackdex.app.BlackDexLoader

class SettingFragment : PreferenceFragmentCompat() {

    private lateinit var savePathPreference: Preference
    private lateinit var saveEnablePreference: SwitchPreferenceCompat
    private lateinit var fixCodeItemPreference: SwitchPreferenceCompat
    private lateinit var hookDumpPreference: SwitchPreferenceCompat

    private val loader get() = AppManager.mBlackBoxLoader

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.setting)

        savePathPreference = findPreference("save_path")!!
        saveEnablePreference = findPreference("save_enable")!!
        fixCodeItemPreference = findPreference("fix_code_item")!!
        hookDumpPreference = findPreference("hook_dump")!!

        saveEnablePreference.isChecked = loader.saveEnable()
        saveEnablePreference.onPreferenceChangeListener = mSaveEnableChange

        savePathPreference.summary = effectivePathSummary()

        fixCodeItemPreference.isChecked = loader.isFixCodeItem()
        fixCodeItemPreference.onPreferenceChangeListener = mFixCodeItemChange

        hookDumpPreference.isChecked = loader.isHookDump()
        hookDumpPreference.onPreferenceChangeListener = mHookDumpChange

        // Appearance
        findPreference<ListPreference>("theme_mode")?.apply {
            value = loader.getThemeMode().toString()
            summary = entry
            setOnPreferenceChangeListener { pref, newValue ->
                val mode = (newValue as String).toIntOrNull() ?: -1
                loader.setThemeMode(mode)
                (pref as ListPreference).value = newValue
                pref.summary = pref.entry
                // AppCompat recreates every started activity (including Main) with the new mode.
                (requireActivity().application as App).applyTheme()
                false
            }
        }
        findPreference<SwitchPreferenceCompat>("dynamic_color")?.apply {
            if (!DynamicColors.isDynamicColorAvailable()) {
                // Wallpaper colors need Android 12+; hide the switch where it cannot work.
                isVisible = false
            } else {
                isChecked = loader.isDynamicColor()
                setOnPreferenceChangeListener { _, newValue ->
                    loader.setDynamicColor(newValue as Boolean)
                    // Re-theme this screen now; MainActivity recreates itself on return.
                    requireActivity().recreate()
                    true
                }
            }
        }
        findPreference<SwitchPreferenceCompat>("show_system_apps")?.apply {
            isChecked = loader.isShowSystemApps()
            setOnPreferenceChangeListener { _, newValue ->
                loader.setShowSystemApps(newValue as Boolean)
                true
            }
        }

        findPreference<Preference>("about_version")?.summary = BuildConfig.VERSION_NAME
        findPreference<Preference>("about_source")?.setOnPreferenceClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/riyadmondol2006/BlackDex-RE"))
                )
            }
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = listView
        list.clipToPadding = false
        val top = list.paddingTop
        val bottom = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, top = top, bottom = bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(list)
    }

    private fun effectivePathSummary(): String {
        return if (loader.saveEnable() || loader.getSavePath().isEmpty()) {
            BlackDexLoader.getDexDumpDir(App.getContext())
        } else {
            loader.getSavePath()
        }
    }

    private val mSaveEnableChange = Preference.OnPreferenceChangeListener { _, newValue ->
        if (newValue == false) {
            // User wants a custom (public) path → needs all-files access.
            (requireActivity() as SettingActivity).setRequestCallback(requestResult)
            false // don't flip until permission result arrives
        } else {
            loader.saveEnable(true)
            savePathPreference.summary = effectivePathSummary()
            true
        }
    }

    private val mHookDumpChange = Preference.OnPreferenceChangeListener { _, newValue ->
        loader.setHookDump(newValue as Boolean)
        true
    }

    private val mFixCodeItemChange = Preference.OnPreferenceChangeListener { _, newValue ->
        if (newValue as Boolean) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.warn)
                .setMessage(R.string.fix_code_item_message)
                .setPositiveButton(R.string.confirm) { _, _ -> loader.setFixCodeItem(true) }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    fixCodeItemPreference.isChecked = false
                    loader.setFixCodeItem(false)
                }
                .show()
        } else {
            loader.setFixCodeItem(false)
        }
        true
    }

    private val requestResult = { hasPermission: Boolean ->
        loader.saveEnable(!hasPermission)
        saveEnablePreference.isChecked = !hasPermission
        if (hasPermission) {
            val path = BlackDexLoader.getPublicDumpDir()
            loader.setSavePath(path)
        }
        savePathPreference.summary = effectivePathSummary()
    }
}
