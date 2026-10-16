package top.niunaijun.blackdex.app

import android.content.Context
import android.os.Environment
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.utils.FileUtils
import top.niunaijun.blackbox.utils.compat.BuildCompat
import top.niunaijun.blackdex.biz.cache.AppSharedPreferenceDelegate
import java.io.File

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/6 23:38
 */
class BlackDexLoader {


    private var mSavePath by AppSharedPreferenceDelegate(App.getContext(), "")

    private var mSaveEnable by AppSharedPreferenceDelegate(App.getContext(), true)

    private var mFixCodeItem by AppSharedPreferenceDelegate(App.getContext(),false)

    private var mHookDump by AppSharedPreferenceDelegate(App.getContext(),true)

    private var mShowSystemApps by AppSharedPreferenceDelegate(App.getContext(), false)

    // -1 = follow system, 1 = light (MODE_NIGHT_NO), 2 = dark (MODE_NIGHT_YES)
    private var mThemeMode by AppSharedPreferenceDelegate(App.getContext(), -1)

    private var mDynamicColor by AppSharedPreferenceDelegate(App.getContext(), true)

    /**
     * Effective output dir, resolved ONCE and cached. It must be computed before the sandbox
     * storage hooks are installed: inside a guest process, calling getExternalFilesDir() after
     * the hooks are active throws (the redirected StorageManager rejects the mkdir). The value
     * is a plain path string shared by every process via [sCachedDir].
     */
    private val mDir: String
        get() = resolveDir()

    private fun resolveDir(): String {
        sCachedDir?.let { return it }
        val dir = if (mSaveEnable || mSavePath.isNullOrEmpty()) {
            getDexDumpDir(App.getContext())
        } else {
            mSavePath!!
        }
        sCachedDir = dir
        return dir
    }

    fun invalidateDirCache() {
        sCachedDir = null
    }

    fun addLifecycleCallback() {

    }

    fun attachBaseContext(context: Context) {
        // Resolve the output dir NOW, before the sandbox hooks are installed by doAttachBaseContext.
        // Afterwards (especially inside a guest process) getExternalFilesDir() would be redirected
        // and throw, so this first call both computes and caches the plain path for later use.
        resolveDir()
        BlackDexCore.get().doAttachBaseContext(context, object : ClientConfiguration() {
            override fun getHostPackageName(): String {
                return context.packageName
            }

            override fun getDexDumpDir(): String {
                return mDir
            }

            override fun isFixCodeItem(): Boolean {
                return mFixCodeItem
            }

            override fun isEnableHookDump(): Boolean {
                return mHookDump
            }
        })
    }

    fun doOnCreate(context: Context) {
        BlackDexCore.get().doCreate()
    }

    fun saveEnable(): Boolean {
        return mSaveEnable
    }

    fun saveEnable(state: Boolean) {
        this.mSaveEnable = state
        invalidateDirCache()
    }

    fun getSavePath(): String {
        return mSavePath
    }

    fun setSavePath(path: String) {
        this.mSavePath = path
        invalidateDirCache()
    }

    fun setFixCodeItem(enable:Boolean){
        this.mFixCodeItem = enable
    }

    fun isFixCodeItem():Boolean{
        return this.mFixCodeItem
    }

    fun setHookDump(enable: Boolean){
        this.mHookDump = enable
    }

    fun isHookDump(): Boolean {

        return this.mHookDump
    }

    fun isShowSystemApps(): Boolean = mShowSystemApps

    fun setShowSystemApps(enable: Boolean) { this.mShowSystemApps = enable }

    fun getThemeMode(): Int = mThemeMode ?: -1

    fun setThemeMode(mode: Int) { this.mThemeMode = mode }

    fun isDynamicColor(): Boolean = mDynamicColor ?: true

    fun setDynamicColor(enable: Boolean) { this.mDynamicColor = enable }


    companion object {

        val TAG: String = BlackDexLoader::class.java.simpleName

        /** Cached output path, resolved before the sandbox hooks are installed in each process. */
        @Volatile
        private var sCachedDir: String? = null

        /**
         * Default output dir: the app's private external files dir
         * (/sdcard/Android/data/<pkg>/files/dump). No storage permission required on any
         * Android version, and writable by the native dumper via a real filesystem path.
         */
        fun getDexDumpDir(context: Context): String {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dump = File(base, "dump")
            FileUtils.mkdirs(dump)
            return dump.absolutePath
        }

        /** Public output dir used when the user opts into a custom path (needs all-files access). */
        fun getPublicDumpDir(): String {
            val dump = File(Environment.getExternalStorageDirectory(), "Download/BlackDex")
            FileUtils.mkdirs(dump)
            return dump.absolutePath
        }
    }
}