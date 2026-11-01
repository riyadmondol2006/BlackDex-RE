package top.niunaijun.blackdex.view.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.app.AppManager
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpUiState
import top.niunaijun.blackdex.data.entity.DumpUiState.Error
import top.niunaijun.blackdex.data.entity.DumpUiState.Phase
import top.niunaijun.blackdex.databinding.ActivityMainBinding
import top.niunaijun.blackdex.util.ExportUtil
import top.niunaijun.blackdex.util.InjectionUtil
import top.niunaijun.blackdex.util.inflate
import top.niunaijun.blackdex.view.base.PermissionActivity
import top.niunaijun.blackdex.view.setting.SettingActivity
import top.niunaijun.blackdex.view.widget.ProgressDialog
import java.io.File

class MainActivity : PermissionActivity(), DumpDialogHost {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var viewModel: MainViewModel

    private lateinit var mAdapter: MainAdapter

    private var appList: List<AppInfo> = ArrayList()

    /** Dynamic-color setting this activity was themed with; recreate if it changes. */
    private var createdWithDynamicColor = true

    private val openApkLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                if (!viewModel.importAndDump(it)) showSnackbar(getString(R.string.dump_busy))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdWithDynamicColor = AppManager.mBlackBoxLoader.isDynamicColor()
        setContentView(viewBinding.root)
        setSupportActionBar(viewBinding.toolbar)
        viewBinding.toolbar.subtitle =
            getString(R.string.main_subtitle, BlackDexCore.get().hostAbi)
        applyWindowInsets()
        initView()
        initViewModel()
        initSearch()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while we were in the background.
        if (AppManager.mBlackBoxLoader.isDynamicColor() != createdWithDynamicColor) {
            recreate()
            return
        }
        if (viewModel.reloadIfFilterChanged()) {
            viewBinding.swipeRefresh.isRefreshing = true
        }
    }

    /**
     * Edge-to-edge insets. The AppBarLayout (fitsSystemWindows) owns the top inset; here we only
     * handle sides and bottom: list padding clears the FAB + navigation bar, FAB margins clear the
     * navigation bar. The keyboard and nav bar overlap, so the larger of the two is used.
     */
    private fun applyWindowInsets() {
        val fabMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        val fabHeight = resources.getDimensionPixelSize(R.dimen.fab_height)
        val endSpacing = resources.getDimensionPixelSize(R.dimen.list_end_spacing)
        val listTop = viewBinding.recyclerView.paddingTop
        val insetTypes =
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { _, insets ->
            val bars = insets.getInsets(insetTypes)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)

            viewBinding.appBar.updatePadding(left = bars.left, right = bars.right)
            viewBinding.recyclerView.updatePadding(
                left = bars.left,
                right = bars.right,
                top = listTop,
                bottom = bottom + fabHeight + fabMargin + endSpacing,
            )
            viewBinding.emptyView.updatePadding(left = bars.left, right = bars.right, bottom = bottom)
            viewBinding.fab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = fabMargin + bottom
                leftMargin = fabMargin + bars.left
                rightMargin = fabMargin + bars.right
            }
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
        mAdapter.setOnItemLongClick { _, _, data -> copyPackageName(data) }
        mAdapter.onCopyPackageName = { copyPackageName(it) }

        viewBinding.swipeRefresh.setOnRefreshListener {
            viewModel.getAppList()
        }

        viewBinding.fab.setOnClickListener {
            if (viewModel.isDumping) {
                showSnackbar(getString(R.string.dump_busy))
                return@setOnClickListener
            }
            openApkLauncher.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip",
                    "*/*"
                )
            )
        }
        initFabScrollBehavior()
    }

    /** Shrinks the extended FAB when scrolling down past a threshold, extends it on the way up. */
    private fun initFabScrollBehavior() {
        val threshold = resources.getDimensionPixelSize(R.dimen.fab_scroll_threshold)
        viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var accumulated = 0

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                // Restart accumulation whenever the direction flips.
                if ((dy > 0) != (accumulated > 0)) accumulated = 0
                accumulated += dy
                val fab = viewBinding.fab
                if (!recyclerView.canScrollVertically(-1)) {
                    if (!fab.isExtended) fab.extend()
                    accumulated = 0
                } else if (accumulated > threshold && fab.isExtended) {
                    fab.shrink()
                    accumulated = 0
                } else if (accumulated < -threshold && !fab.isExtended) {
                    fab.extend()
                    accumulated = 0
                }
            }
        })
    }

    private fun confirmDump(data: AppInfo) {
        if (viewModel.isDumping) {
            showSnackbar(getString(R.string.dump_busy))
            return
        }
        val sb = StringBuilder(data.packageName)
        if (data.packer.isProtected) {
            sb.append("\n\n").append(getString(R.string.protection_label, data.packer.name))
            if (data.packer.vmpLikely) {
                sb.append("\n").append(getString(R.string.vmp_warning))
            }
        }
        ConfirmDumpDialog.show(supportFragmentManager, data.packageName, data.name, sb.toString())
    }

    private fun copyPackageName(data: AppInfo) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(data.packageName, data.packageName))
        }.onFailure { return }
        // Android 13+ shows its own clipboard confirmation; avoid a duplicate message there.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            showSnackbar(getString(R.string.package_name_copied, data.packageName))
        }
    }

    private fun initViewModel() {
        viewModel =
            ViewModelProvider(this, InjectionUtil.getMainFactory())[MainViewModel::class.java]
        if (viewModel.loadAppListIfNeeded()) {
            showListLoading(true)
        }

        viewModel.mAppListLiveData.observe(this) {
            it?.let {
                this.appList = it
                viewBinding.swipeRefresh.isRefreshing = false
                showListLoading(false)
                filterApp(viewBinding.searchEdit.text?.toString() ?: "")
            }
        }

        viewModel.dumpState.observe(this) { renderDumpState(it) }

        viewModel.shareEvent.observe(this) { event ->
            when (event) {
                null -> Unit
                MainViewModel.ShareEvent.Preparing ->
                    showSnackbar(getString(R.string.preparing_share))
                is MainViewModel.ShareEvent.Ready -> {
                    viewModel.consumeShareEvent()
                    runCatching { ExportUtil.shareZip(this, event.zip) }
                        .onFailure { showSnackbar(getString(R.string.share_failed)) }
                }
                MainViewModel.ShareEvent.Failed -> {
                    viewModel.consumeShareEvent()
                    showSnackbar(getString(R.string.share_failed))
                }
            }
        }
    }

    private fun renderDumpState(state: DumpUiState?) {
        val fm = supportFragmentManager
        if (state == null) {
            ProgressDialog.dismiss(fm)
            return
        }
        if (state.isActive) {
            ProgressDialog.show(fm)
            return
        }
        ProgressDialog.dismiss(fm)
        if (state.resultDelivered) return

        val success = state.phase == Phase.SUCCESS
        val title: String
        val message: String
        var offerReport = false
        when {
            success -> {
                title = getString(R.string.unpack_success)
                message = getString(R.string.dex_save, state.outputDir.orEmpty())
            }
            state.error == Error.ABI_MISMATCH -> {
                title = getString(R.string.abi_mismatch_title)
                message = getString(R.string.abi_mismatch_msg, BlackDexCore.get().hostAbi)
            }
            state.error == Error.IMPORT -> {
                title = getString(R.string.unpack_fail)
                message = getString(R.string.import_failed)
            }
            state.error == Error.ENGINE && !state.errorMessage.isNullOrBlank() -> {
                title = getString(R.string.unpack_fail)
                message = getString(R.string.error_msg, state.errorMessage)
                offerReport = true
            }
            else -> {
                title = getString(R.string.unpack_fail)
                message = getString(R.string.jump_issue)
                offerReport = true
            }
        }
        val dir = if (success) state.outputDir else null
        if (DumpResultDialog.show(fm, title, message, dir, offerReport)) {
            viewModel.markResultDelivered(state.operationId)
        }
    }

    private fun initSearch() {
        viewBinding.searchEdit.doAfterTextChanged { text ->
            filterApp(text?.toString() ?: "")
        }
        viewBinding.searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    private fun filterApp(newText: String) {
        val query = newText.trim()
        val newList = if (query.isEmpty()) appList else appList.filter {
            it.name.contains(query, true) || it.packageName.contains(query, true)
        }
        mAdapter.replaceData(newList)
        viewBinding.emptyView.isVisible = newList.isEmpty() && !viewBinding.listLoading.isVisible
        // A short (non-scrollable) result list can't trigger the scroll listener; re-extend here.
        viewBinding.recyclerView.post {
            if (!viewBinding.recyclerView.canScrollVertically(-1) && !viewBinding.fab.isExtended) {
                viewBinding.fab.extend()
            }
        }
    }

    private fun showListLoading(loading: Boolean) {
        viewBinding.listLoading.isVisible = loading
        if (loading) viewBinding.emptyView.isVisible = false
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }
        viewBinding.searchEdit.clearFocus()
    }

    private fun showSnackbar(message: String, actionRes: Int = 0, action: (() -> Unit)? = null) {
        Snackbar.make(viewBinding.root, message, Snackbar.LENGTH_SHORT).apply {
            anchorView = viewBinding.fab
            if (actionRes != 0 && action != null) {
                duration = Snackbar.LENGTH_LONG
                setAction(actionRes) { action() }
            }
        }.show()
    }

    // ---- DumpDialogHost -------------------------------------------------------------------

    override fun onConfirmDump(packageName: String) {
        if (!viewModel.startDexDump(packageName)) {
            showSnackbar(getString(R.string.dump_busy))
        }
    }

    override fun onShareDump(dir: String) {
        viewModel.prepareShare(File(dir))
    }

    override fun onOpenDumpFolder(dir: String) {
        if (!ExportUtil.openFolder(this, File(dir))) {
            showSnackbar(getString(R.string.open_folder_failed), R.string.share) {
                onShareDump(dir)
            }
        }
    }

    override fun onReportIssue() {
        openUrl(ISSUES_URL)
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
            R.id.main_git -> openUrl(REPO_URL)
            R.id.main_setting -> startActivity(Intent(this, SettingActivity::class.java))
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val REPO_URL = "https://github.com/riyadmondol2006/BlackDex-RE"
        private const val ISSUES_URL = "https://github.com/riyadmondol2006/BlackDex-RE/issues"
    }
}
