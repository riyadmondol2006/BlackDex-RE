package top.niunaijun.blackdex.view.widget

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.data.entity.DumpUiState
import top.niunaijun.blackdex.data.entity.DumpUiState.Phase
import top.niunaijun.blackdex.databinding.DialogProgressBinding
import top.niunaijun.blackdex.util.InjectionUtil
import top.niunaijun.blackdex.view.main.MainViewModel

/**
 * Non-cancelable dump progress dialog. Renders [MainViewModel.dumpState]: indeterminate while
 * the sandbox launches, determinate only once the dumper reports valid totals, then "Finishing…".
 * It is restored by the FragmentManager on rotation and dismisses itself when the operation ends.
 */
class ProgressDialog : DialogFragment() {

    private var binding: DialogProgressBinding? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val viewBinding = DialogProgressBinding.inflate(layoutInflater)
        binding = viewBinding

        val viewModel =
            ViewModelProvider(requireActivity(), InjectionUtil.getMainFactory())[MainViewModel::class.java]
        viewModel.dumpState.observe(this) { state -> render(state) }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dumping)
            .setView(viewBinding.root)
            .setCancelable(false)
            .create()
    }

    private fun render(state: DumpUiState?) {
        val b = binding ?: return
        if (state == null || !state.isActive) {
            dismissAllowingStateLoss()
            return
        }
        when (state.phase) {
            Phase.DUMPING, Phase.FINISHING -> if (state.hasValidProgress) {
                // Max must be set before the progress value.
                if (b.progress.isIndeterminate) b.progress.isIndeterminate = false
                if (b.progress.max != state.total) b.progress.max = state.total
                b.progress.setProgressCompat(state.current, true)
                b.status.text = if (state.phase == Phase.FINISHING) {
                    getString(R.string.dump_finishing)
                } else {
                    getString(R.string.dump_progress_dex, state.current, state.total)
                }
            }
            else -> {
                if (!b.progress.isIndeterminate) b.progress.isIndeterminate = true
                b.status.setText(R.string.dump_preparing)
            }
        }
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "dump_progress"

        fun show(fm: FragmentManager) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ProgressDialog().show(fm, TAG)
        }

        fun dismiss(fm: FragmentManager) {
            (fm.findFragmentByTag(TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }
    }
}
