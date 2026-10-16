package top.niunaijun.blackdex.view.widget

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.databinding.DialogProgressBinding

/**
 * Non-cancelable dump progress sheet. Starts indeterminate ("launching"), then
 * switches to a determinate class-count bar once the native dumper reports totals.
 */
class ProgressDialog : DialogFragment() {

    private val viewBinding: DialogProgressBinding by lazy {
        DialogProgressBinding.inflate(layoutInflater)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        return MaterialAlertDialogBuilder(requireContext())
            .setView(viewBinding.root)
            .setCancelable(false)
            .create()
    }

    fun setProgress(progress: Int, maxProgress: Int) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            if (maxProgress <= 0) return@runOnUiThread
            if (viewBinding.progress.isIndeterminate) {
                viewBinding.progress.isIndeterminate = false
                viewBinding.progress.max = maxProgress
            }
            viewBinding.progress.setProgressCompat(progress, true)
            viewBinding.status.text = getString(R.string.classes_progress, progress, maxProgress)
        }
    }
}
