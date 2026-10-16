package top.niunaijun.blackdex.view.setting

import android.os.Bundle
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
