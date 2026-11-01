package top.niunaijun.blackdex.view.setting

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.databinding.ActivitySettingBinding
import top.niunaijun.blackdex.util.inflate
import top.niunaijun.blackdex.view.base.PermissionActivity

class SettingActivity : PermissionActivity() {

    private val viewBinding: ActivitySettingBinding by inflate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)
        setSupportActionBar(viewBinding.toolbar)
        viewBinding.toolbar.setNavigationOnClickListener { finish() }

        // AppBarLayout (fitsSystemWindows) owns the top inset; sides are padded here and the
        // list's bottom/sides in SettingFragment.
        // (Listener sits on the root: setting one on the AppBarLayout would replace its own.)
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            viewBinding.appBar.updatePadding(left = bars.left, right = bars.right)
            insets
        }

        // Single fragment instance: added here once, restored by the FragmentManager afterwards.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment, SettingFragment())
                .commit()
        }
    }

    fun setRequestCallback(callback: ((Boolean) -> Unit)?) {
        this.requestPermissionCallback = callback
        requestStoragePermission()
    }
}
