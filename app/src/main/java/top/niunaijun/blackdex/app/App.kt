package top.niunaijun.blackdex.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Application entry point.
 *
 * Boots the BlackDex sandbox core in [attachBaseContext]/[onCreate] and applies
 * Material You dynamic color to every activity.
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
        applyTheme()
    }

    fun applyTheme() {
        runCatching {
            val loader = AppManager.mBlackBoxLoader
            AppCompatDelegate.setDefaultNightMode(
                when (loader.getThemeMode()) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            if (loader.isDynamicColor()) {
                DynamicColors.applyToActivitiesIfAvailable(this)
            }
        }
    }
}
