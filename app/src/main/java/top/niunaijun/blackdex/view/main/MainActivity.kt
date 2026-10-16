package top.niunaijun.blackdex.view.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor
import top.niunaijun.blackbox.entity.dump.DumpResult
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpInfo
import top.niunaijun.blackdex.databinding.ActivityMainBinding
import top.niunaijun.blackdex.util.ExportUtil
import top.niunaijun.blackdex.util.InjectionUtil
import top.niunaijun.blackdex.util.inflate
import top.niunaijun.blackdex.util.toast
import top.niunaijun.blackdex.view.base.PermissionActivity
import top.niunaijun.blackdex.view.setting.SettingActivity
import top.niunaijun.blackdex.view.widget.ProgressDialog
import java.io.File

class MainActivity : PermissionActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var viewModel: MainViewModel

    private lateinit var mAdapter: MainAdapter

    private var loadingView: ProgressDialog? = null

    private var appList: List<AppInfo> = ArrayList()

    private val openApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { importAndDump(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        setSupportActionBar(viewBinding.toolbar)
        viewBinding.toolbar.subtitle = "non-root · " + top.niunaijun.blackbox.BlackDexCore.get().hostAbi
        applyEdgeToEdgeInsets()
        initView()
        initViewModel()
        initSearch()

        BlackDexCore.get().registerDumpMonitor(mMonitor)
    }

    private fun applyEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            viewBinding.appBar.updatePadding(top = bars.top)
            viewBinding.recyclerView.updatePadding(bottom = bars.bottom + 96)
            insets
        }
    }

    private fun initView() {
        mAdapter = MainAdapter()
        viewBinding.recyclerView.adapter = mAdapter
        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

        mAdapter.setOnItemClick { _, _, data ->
            hideKeyboard()
            confirmDump(data)
        }

        viewBinding.swipeRefresh.setOnRefreshListener {
            viewModel.getAppList()
        }

        viewBinding.fab.setOnClickListener {
            openApkLauncher.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip",
                    "*/*"
                )
            )
        }
    }

    private fun confirmDump(data: AppInfo) {
        val sb = StringBuilder(data.packageName)
        if (data.packer.isProtected) {
            sb.append("\n\n").append(getString(R.string.protection_label, data.packer.name))
            if (data.packer.vmpLikely) {
                sb.append("\n").append(getString(R.string.vmp_warning))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(data.name)
            .setMessage(sb.toString())
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dumping) { _, _ ->
                viewModel.startDexDump(data.packageName)
            }
            .show()
    }

    private fun importAndDump(uri: Uri) {
        showLoading()
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(cacheDir, "import_${System.currentTimeMillis()}.apk")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                    dst.absolutePath
                }.getOrNull()
            }
            if (path == null) {
                hideLoading()
                toast(getString(R.string.unpack_fail))
            } else if (!BlackDexCore.get().isAbiSupported(File(path))) {
                hideLoading()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.abi_mismatch_title)
                    .setMessage(getString(R.string.abi_mismatch_msg, BlackDexCore.get().hostAbi))
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            } else {
                viewModel.startDexDump(path)
            }
        }
    }

    private fun initViewModel() {
        viewModel =
            ViewModelProvider(this, InjectionUtil.getMainFactory())[MainViewModel::class.java]
        showListLoading(true)
        viewModel.getAppList()

        viewModel.mAppListLiveData.observe(this) {
            it?.let {
                this.appList = it
                viewBinding.swipeRefresh.isRefreshing = false
                showListLoading(false)
                filterApp(viewBinding.searchEdit.text?.toString() ?: "")
            }
        }

        viewModel.mDexDumpLiveData.observe(this) {
            it?.let {
                when (it.state) {
                    DumpInfo.LOADING -> showLoading()
                    DumpInfo.TIMEOUT -> {
                        hideLoading()
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.unpack_fail)
                            .setMessage(R.string.jump_issue)
                            .setNegativeButton(R.string.github) { _, _ ->
                                openUrl("https://github.com/riyadmondol2006/BlackDex-RE/issues")
                            }
                            .setPositiveButton(R.string.confirm, null)
                            .show()
                    }
                    else -> {
                        viewModel.dexDumpSuccess()
                        hideLoading()
                        val success = it.state == DumpInfo.SUCCESS
                        val builder = MaterialAlertDialogBuilder(this)
                            .setTitle(if (success) R.string.unpack_success else R.string.unpack_fail)
                            .setMessage(it.msg)
                            .setPositiveButton(R.string.confirm, null)
                        if (success && it.dir.isNotEmpty()) {
                            val dir = File(it.dir)
                            builder.setNeutralButton(R.string.share) { _, _ -> shareDump(dir) }
                            builder.setNegativeButton(R.string.open_folder) { _, _ ->
                                ExportUtil.openFolder(this, dir)
                            }
                        }
                        builder.show()
                    }
                }
            }
        }
    }

    private val mMonitor = object : IBDumpMonitor.Stub() {
        override fun onDump(result: DumpResult?) {
            result?.let {
                if (result.isRunning) {
                    loadingView?.setProgress(result.currProcess, result.totalProcess)
                    return
                }
                if (result.isSuccess) {
                    viewModel.mDexDumpLiveData.postValue(
                        DumpInfo(DumpInfo.SUCCESS, getString(R.string.dex_save, result.dir))
                    )
                } else {
                    viewModel.mDexDumpLiveData.postValue(
                        DumpInfo(DumpInfo.FAIL, getString(R.string.error_msg, result.msg))
                    )
                }
            }
        }
    }

    private fun initSearch() {
        viewBinding.searchEdit.doAfterTextChanged { text ->
            filterApp(text?.toString() ?: "")
        }
    }

    private fun filterApp(newText: String) {
        val newList = this.appList.filter {
            it.name.contains(newText, true) or it.packageName.contains(newText, true)
        }
        mAdapter.replaceData(newList)
        viewBinding.emptyView.isVisible = newList.isEmpty() && !viewBinding.listLoading.isVisible
    }

    private fun showListLoading(loading: Boolean) {
        viewBinding.listLoading.isVisible = loading
        if (loading) viewBinding.emptyView.isVisible = false
    }

    private fun showLoading() {
        if (this.loadingView == null) {
            loadingView = ProgressDialog()
        }
        if (loadingView?.isAdded != true) {
            loadingView?.show(supportFragmentManager, "progress")
        }
    }

    private fun hideLoading() {
        loadingView?.dismissAllowingStateLoss()
        loadingView = null
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
    }

    private fun shareDump(dir: File) {
        lifecycleScope.launch {
            val zip = withContext(Dispatchers.IO) {
                ExportUtil.zipDump(this@MainActivity, dir, dir.name)
            }
            if (zip != null) {
                ExportUtil.shareZip(this@MainActivity, zip)
            } else {
                toast(getString(R.string.unpack_fail))
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.main_git -> openUrl("https://github.com/riyadmondol2006/BlackDex-RE")
            R.id.main_setting -> startActivity(Intent(this, SettingActivity::class.java))
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { BlackDexCore.get().unregisterDumpMonitor(mMonitor) }
    }
}
