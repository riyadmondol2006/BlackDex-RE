package top.niunaijun.blackdex.view.main

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackdex.R

/** Callbacks the dump dialogs deliver to their host activity. */
interface DumpDialogHost {
    fun onConfirmDump(packageName: String)
    fun onShareDump(dir: String)
    fun onOpenDumpFolder(dir: String)
    fun onReportIssue()
}

/**
 * "Unpack <app>?" confirmation. A restorable DialogFragment so it survives rotation exactly once.
 */
class ConfirmDumpDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val packageName = args.getString(ARG_PACKAGE).orEmpty()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.unpack) { _, _ ->
                (activity as? DumpDialogHost)?.onConfirmDump(packageName)
            }
            .create()
    }

    companion object {
        const val TAG = "dump_confirm"
        private const val ARG_PACKAGE = "package"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"

        /** Shows the dialog unless one is already showing. */
        fun show(fm: FragmentManager, packageName: String, title: String, message: String) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            ConfirmDumpDialog().apply {
                arguments = bundleOf(
                    ARG_PACKAGE to packageName,
                    ARG_TITLE to title,
                    ARG_MESSAGE to message,
                )
            }.show(fm, TAG)
        }
    }
}

/**
 * Dump result: success offers Done / Share / Open folder; failure offers Done / Report issue.
 */
class DumpResultDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val dir = args.getString(ARG_DIR)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
            .setPositiveButton(R.string.done, null)
        if (!dir.isNullOrEmpty()) {
            builder.setNeutralButton(R.string.share) { _, _ ->
                (activity as? DumpDialogHost)?.onShareDump(dir)
            }
            builder.setNegativeButton(R.string.open_folder) { _, _ ->
                (activity as? DumpDialogHost)?.onOpenDumpFolder(dir)
            }
        } else if (args.getBoolean(ARG_REPORT)) {
            builder.setNegativeButton(R.string.report_issue) { _, _ ->
                (activity as? DumpDialogHost)?.onReportIssue()
            }
        }
        return builder.create()
    }

    companion object {
        const val TAG = "dump_result"
        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DIR = "dir"
        private const val ARG_REPORT = "report"

        /** Returns false if it could not be shown now (state already saved). */
        fun show(
            fm: FragmentManager,
            title: String,
            message: String,
            dir: String?,
            offerReport: Boolean,
        ): Boolean {
            if (fm.isStateSaved) return false
            (fm.findFragmentByTag(TAG) as? DialogFragment)?.dismissAllowingStateLoss()
            DumpResultDialog().apply {
                arguments = bundleOf(
                    ARG_TITLE to title,
                    ARG_MESSAGE to message,
                    ARG_DIR to dir,
                    ARG_REPORT to offerReport,
                )
            }.show(fm, TAG)
            return true
        }
    }
}
