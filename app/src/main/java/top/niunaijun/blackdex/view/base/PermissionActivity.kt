package top.niunaijun.blackdex.view.base

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackbox.utils.compat.BuildCompat
import top.niunaijun.blackdex.R

/**
 * Base activity that requests write/all-files access only when the user opts into a
 * custom (public) output path. The default output path is the app's private external
 * files dir and needs no permission.
 */
open class PermissionActivity : BaseActivity() {

    protected var requestPermissionCallback: ((Boolean) -> Unit)? = null

    protected fun requestStoragePermission() {
        if (BuildCompat.isR()) {
            if (Environment.isExternalStorageManager()) {
                requestPermissionCallback?.invoke(true)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.grant_permission)
                    .setMessage(R.string.request_storage_msg)
                    .setNegativeButton(R.string.request_later) { _, _ ->
                        requestPermissionCallback?.invoke(false)
                    }
                    .setPositiveButton(R.string.jump_grant) { _, _ ->
                        runCatching {
                            val intent =
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.fromParts("package", packageName, null)
                            startActivity(intent)
                        }
                    }
                    .show()
            }
        } else if (BuildCompat.isM() &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_DENIED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requestPermissionCallback?.invoke(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestPermissionCallback?.invoke(true)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.request_fail)
                    .setMessage(R.string.denied_msg)
                    .apply {
                        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            setPositiveButton(R.string.request_again) { _, _ -> requestStoragePermission() }
                        } else {
                            setPositiveButton(R.string.jump_grant) { _, _ ->
                                runCatching {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = Uri.fromParts("package", packageName, null)
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.request_later) { _, _ ->
                        requestPermissionCallback?.invoke(false)
                    }
                    .show()
            }
        }
}
