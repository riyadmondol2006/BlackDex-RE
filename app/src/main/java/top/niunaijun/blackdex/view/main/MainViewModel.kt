package top.niunaijun.blackdex.view.main

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor
import top.niunaijun.blackbox.entity.dump.DumpResult
import top.niunaijun.blackdex.app.App
import top.niunaijun.blackdex.app.AppManager
import top.niunaijun.blackdex.data.DexDumpRepository
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpInfo
import top.niunaijun.blackdex.data.entity.DumpUiState
import top.niunaijun.blackdex.data.entity.DumpUiState.Error
import top.niunaijun.blackdex.data.entity.DumpUiState.Phase
import top.niunaijun.blackdex.util.ExportUtil
import top.niunaijun.blackdex.view.base.BaseViewModel
import java.io.File

/**
 * Owns the app list and the (single) active dump operation. The dump monitor binder is
 * registered here rather than in the Activity, so progress keeps flowing across rotation and
 * the callback is unregistered exactly once in [onCleared].
 */
class MainViewModel(private val repo: DexDumpRepository) : BaseViewModel() {

    // ---- app list -------------------------------------------------------------------------

    val mAppListLiveData = MutableLiveData<List<AppInfo>>()

    /** The "show system apps" value the current list was loaded with. */
    private var loadedWithSystemApps: Boolean? = null

    fun getAppList() {
        loadedWithSystemApps = AppManager.mBlackBoxLoader.isShowSystemApps()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.getAppList(mAppListLiveData) }
                .onFailure {
                    it.printStackTrace()
                    mAppListLiveData.postValue(mAppListLiveData.value ?: emptyList())
                }
        }
    }

    /** Loads the list unless it is already loaded (e.g. after rotation). */
    fun loadAppListIfNeeded(): Boolean {
        if (loadedWithSystemApps != null) return false
        getAppList()
        return true
    }

    /** Reloads if "show system apps" changed since the list was loaded. */
    fun reloadIfFilterChanged(): Boolean {
        val loaded = loadedWithSystemApps ?: return false
        if (loaded == AppManager.mBlackBoxLoader.isShowSystemApps()) return false
        getAppList()
        return true
    }

    // ---- dump operation -------------------------------------------------------------------

    private val _dumpState = MutableLiveData<DumpUiState?>()
    val dumpState: LiveData<DumpUiState?> get() = _dumpState

    private val mainHandler = Handler(Looper.getMainLooper())
    private var nextOperationId = 0L

    /** Id of the running operation, read from the binder thread. */
    @Volatile
    private var activeOperationId = NO_OPERATION

    /** Per-operation channel the repository posts its [DumpInfo] into. */
    private var repoChannel: Pair<MutableLiveData<DumpInfo>, Observer<DumpInfo>>? = null

    /** Immutable copy of a [DumpResult], taken on the binder thread. */
    private class MonitorEvent(
        val running: Boolean,
        val success: Boolean,
        val current: Int,
        val total: Int,
        val dir: String?,
        val msg: String?,
        val packageName: String?,
    )

    private val monitor = object : IBDumpMonitor.Stub() {
        override fun onDump(result: DumpResult?) {
            result ?: return
            val event = MonitorEvent(
                result.isRunning, result.isSuccess, result.currProcess, result.totalProcess,
                result.dir, result.msg, result.packageName
            )
            val operationId = activeOperationId
            mainHandler.post { onMonitorEvent(operationId, event) }
        }
    }

    init {
        runCatching { BlackDexCore.get().registerDumpMonitor(monitor) }
            .onFailure { it.printStackTrace() }
    }

    val isDumping: Boolean get() = _dumpState.value?.isActive == true

    /** Starts dumping an installed package. Returns false (and does nothing) if one is running. */
    fun startDexDump(packageName: String): Boolean {
        val operationId = beginOperation(packageName, packageName) ?: return false
        runEngine(operationId, packageName)
        return true
    }

    /** Copies a picked APK into the cache and dumps it. Returns false if a dump is running. */
    fun importAndDump(uri: Uri): Boolean {
        val operationId = beginOperation(uri.toString(), null) ?: return false
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val context = App.getContext()
                    val dst = File(context.cacheDir, "import_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    dst.absolutePath
                }.getOrNull()
            }
            if (!isCurrent(operationId)) return@launch
            when {
                path == null -> finish(operationId) { it.copy(phase = Phase.FAILED, error = Error.IMPORT) }
                !withContext(Dispatchers.IO) { BlackDexCore.get().isAbiSupported(File(path)) } ->
                    finish(operationId) { it.copy(phase = Phase.FAILED, error = Error.ABI_MISMATCH) }
                else -> runEngine(operationId, path)
            }
        }
        return true
    }

    /** Called by the Activity once it has shown the result for [operationId]. */
    fun markResultDelivered(operationId: Long) {
        val state = _dumpState.value ?: return
        if (state.operationId == operationId && state.isFinished && !state.resultDelivered) {
            _dumpState.value = state.copy(resultDelivered = true)
        }
    }

    private fun beginOperation(source: String, packageName: String?): Long? {
        if (isDumping) return null
        val operationId = ++nextOperationId
        activeOperationId = operationId
        _dumpState.value = DumpUiState(operationId, source, packageName, Phase.LAUNCHING)
        return operationId
    }

    private fun isCurrent(operationId: Long): Boolean {
        val state = _dumpState.value ?: return false
        return state.operationId == operationId && state.isActive
    }

    /** Hands [source] to the existing engine/repository logic, bound to [operationId]. */
    private fun runEngine(operationId: Long, source: String) {
        val channel = MutableLiveData<DumpInfo>()
        val observer = Observer<DumpInfo> { info -> onRepoDumpInfo(operationId, info) }
        channel.observeForever(observer)
        repoChannel = channel to observer
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.dumpDex(source, channel) }
                .onFailure {
                    it.printStackTrace()
                    channel.postValue(DumpInfo(DumpInfo.FAIL, it.message ?: it.toString()))
                }
        }
    }

    private fun onRepoDumpInfo(operationId: Long, info: DumpInfo) {
        if (!isCurrent(operationId)) return
        when (info.state) {
            DumpInfo.LOADING -> Unit // already LAUNCHING
            DumpInfo.SUCCESS -> finish(operationId) {
                it.copy(phase = Phase.SUCCESS, outputDir = info.dir.ifEmpty { it.outputDir })
            }
            DumpInfo.FAIL -> finish(operationId) {
                it.copy(phase = Phase.FAILED, error = Error.ENGINE, errorMessage = info.msg)
            }
            else -> finish(operationId) { it.copy(phase = Phase.FAILED, error = Error.UNKNOWN) }
        }
    }

    private fun onMonitorEvent(operationId: Long, event: MonitorEvent) {
        // Late progress after completion, or events for a different operation, are dropped.
        if (!isCurrent(operationId)) return
        val state = _dumpState.value ?: return
        if (state.packageName != null && event.packageName != null &&
            state.packageName != event.packageName
        ) return

        when {
            event.running -> {
                if (event.total > 0 && event.current in 0..event.total) {
                    _dumpState.value = state.copy(
                        phase = if (event.current >= event.total) Phase.FINISHING else Phase.DUMPING,
                        current = event.current,
                        total = event.total,
                        outputDir = event.dir ?: state.outputDir,
                    )
                }
            }
            event.success -> finish(operationId) {
                // Keep the output dir so the result can offer Open folder / Share.
                it.copy(phase = Phase.SUCCESS, outputDir = event.dir ?: it.outputDir ?: fallbackDir(it))
            }
            else -> finish(operationId) {
                it.copy(phase = Phase.FAILED, error = Error.ENGINE, errorMessage = event.msg)
            }
        }
    }

    private fun fallbackDir(state: DumpUiState): String? {
        val pkg = state.packageName ?: return null
        return runCatching {
            File(top.niunaijun.blackbox.BlackBoxCore.get().dexDumpDir, pkg).absolutePath
        }.getOrNull()
    }

    private inline fun finish(operationId: Long, update: (DumpUiState) -> DumpUiState) {
        val state = _dumpState.value ?: return
        if (state.operationId != operationId || !state.isActive) return
        activeOperationId = NO_OPERATION
        // Invalidate the repository's countdown so it cannot report this operation again.
        repo.dumpSuccess()
        repoChannel?.let { (channel, observer) -> channel.removeObserver(observer) }
        repoChannel = null
        _dumpState.value = update(state)
    }

    // ---- share ----------------------------------------------------------------------------

    sealed class ShareEvent {
        object Preparing : ShareEvent()
        class Ready(val zip: File) : ShareEvent()
        object Failed : ShareEvent()
    }

    /** One-shot share events; the Activity consumes them with [consumeShareEvent]. */
    private val _shareEvent = MutableLiveData<ShareEvent?>()
    val shareEvent: LiveData<ShareEvent?> get() = _shareEvent

    private var sharing = false

    /** Zips [dir] for sharing. Returns false if a share is already being prepared. */
    fun prepareShare(dir: File): Boolean {
        if (sharing) return false
        sharing = true
        _shareEvent.value = ShareEvent.Preparing
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching { ExportUtil.zipDump(App.getContext(), dir, dir.name) }.getOrNull()
            }
            sharing = false
            _shareEvent.value = if (zip != null) ShareEvent.Ready(zip) else ShareEvent.Failed
        }
        return true
    }

    fun consumeShareEvent() {
        _shareEvent.value = null
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        repoChannel?.let { (channel, observer) -> channel.removeObserver(observer) }
        repoChannel = null
        runCatching { BlackDexCore.get().unregisterDumpMonitor(monitor) }
        super.onCleared()
    }

    companion object {
        private const val NO_OPERATION = -1L
    }
}
