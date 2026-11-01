package top.niunaijun.blackdex.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * Application entry point.
 *
 * Boots the BlackDex sandbox core in [attachBaseContext]/[onCreate] and applies
 * Material You dynamic color to every activity (when enabled in settings).
 */
class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        mContext = base!!
        AppManager.doAttachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppManager.doOnCreate(mContext)
        registerDynamicColors()
        applyTheme()
    }

    /**
     * Registered exactly once. The precondition is evaluated for every activity as it is
     * created, so toggling the setting takes effect on the next (re)created activity — and
     * turning it off really turns it off.
     */
    private fun registerDynamicColors() {
        runCatching {
            DynamicColors.applyToActivitiesIfAvailable(
                this,
                DynamicColorsOptions.Builder()
                    .setPrecondition { _, _ ->
                        runCatching { AppManager.mBlackBoxLoader.isDynamicColor() }.getOrDefault(true)
                    }
                    .build()
            )
        }
    }

    /** Applies the saved light/dark/system night mode. Safe to call repeatedly. */
    fun applyTheme() {
        runCatching {
            AppCompatDelegate.setDefaultNightMode(
                when (AppManager.mBlackBoxLoader.getThemeMode()) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
