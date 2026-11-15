package top.niunaijun.blackdex.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.app.App
import top.niunaijun.blackdex.app.AppManager
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpInfo
import top.niunaijun.blackdex.util.PackerDetector
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/23 14:29
 */
class DexDumpRepository {

    fun getAppList(mAppListLiveData: MutableLiveData<List<AppInfo>>) {

        val installedApplications: List<ApplicationInfo> =
                getPackageManager().getInstalledApplications(0)
        val installedList = mutableListOf<AppInfo>()

        val showSystem = AppManager.mBlackBoxLoader.isShowSystemApps()
        for (installedApplication in installedApplications) {
            val file = File(installedApplication.sourceDir)

            val isSystem = (installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !showSystem) continue
            if (installedApplication.packageName == App.getContext().packageName) continue

            if (!AbiUtils.isSupport(file)) continue

            val info = AppInfo(
                    installedApplication.loadLabel(getPackageManager()).toString(),
                    installedApplication.packageName,
                    installedApplication.loadIcon(getPackageManager()),
                    PackerDetector.detect(file),
                    isSystem
            )
            installedList.add(info)
        }

        installedList.sortBy { it.name.lowercase() }
        mAppListLiveData.postValue(installedList)
    }

    /**
     * Installs [source] into the sandbox, launches it, and waits for the dump to finish.
     *
     * This is a `suspend` function run from the ViewModel's scope, so the wait is cancelled
     * automatically when the ViewModel is cleared — the old implementation launched a detached
     * `GlobalScope` coroutine that leaked past the Activity and raced a non-volatile counter.
     *
     * The dump monitor is the primary completion signal (see MainViewModel); this poll is the
     * fallback for when the monitor never fires (e.g. the sandbox process was killed). It resolves
     * as soon as a `.dex` is on disk instead of blind-waiting a fixed interval.
     */
    suspend fun dumpDex(source: String, dexDumpLiveData: MutableLiveData<DumpInfo>) {
        dexDumpLiveData.postValue(DumpInfo(DumpInfo.LOADING))
        val result = withContext(Dispatchers.IO) {
            when {
                URLUtil.isValidUrl(source) -> BlackDexCore.get().dumpDex(Uri.parse(source))
                source.contains("/") -> BlackDexCore.get().dumpDex(File(source))
                else -> BlackDexCore.get().dumpDex(source)
            }
        }
        if (result == null) {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.TIMEOUT))
            return
        }

        val packageName = result.packageName
        // fixCodeItem rebuilds every method body and can legitimately run for minutes.
        val capMs = if (AppManager.mBlackBoxLoader.isFixCodeItem()) FIX_CODE_CAP_MS else NORMAL_CAP_MS
        val start = System.currentTimeMillis()
        while (coroutineContext.isActive) {
            delay(POLL_MS)
            if (BlackDexCore.get().isExistDexFile(packageName)) break
            val elapsed = System.currentTimeMillis() - start
            // Grace period before trusting isRunning==false: launchApk may return a beat before
            // the guest process is actually up, and we must not call TIMEOUT on that gap.
            if (!BlackDexCore.get().isRunning && elapsed > SETTLE_MS) break
            if (elapsed > capMs) break
        }

        if (BlackDexCore.get().isExistDexFile(packageName)) {
            val dir = File(BlackBoxCore.get().dexDumpDir, packageName).absolutePath
            dexDumpLiveData.postValue(
                DumpInfo(DumpInfo.SUCCESS, App.getContext().getString(R.string.dex_save, dir), dir)
            )
        } else {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.TIMEOUT))
        }
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val SETTLE_MS = 3_000L
        private const val NORMAL_CAP_MS = 90_000L
        private const val FIX_CODE_CAP_MS = 10 * 60_000L
    }
}
