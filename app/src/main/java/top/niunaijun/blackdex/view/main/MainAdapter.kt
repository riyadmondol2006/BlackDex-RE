package top.niunaijun.blackdex.view.main

import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.databinding.ItemPackageBinding
import top.niunaijun.blackdex.util.newBindingViewHolder
import top.niunaijun.blackdex.view.base.BaseAdapter

/**
 * Flat Material 3 list rows. Tap = unpack (via the item click listener); long-press = copy the
 * package name, also exposed to TalkBack as a named "Copy package name" action.
 */
class MainAdapter : BaseAdapter<ItemPackageBinding, AppInfo>() {

    /** Invoked for long-press and the TalkBack "Copy package name" action. */
    var onCopyPackageName: ((AppInfo) -> Unit)? = null

    override fun getViewBinding(parent: ViewGroup): ItemPackageBinding {
        return newBindingViewHolder(parent, false)
    }

    override fun initView(binding: ItemPackageBinding, position: Int, data: AppInfo) {
        val context = binding.root.context
        binding.icon.setImageDrawable(data.icon)
        binding.name.text = data.name
        binding.packageName.text = data.packageName

        // Packer status label: always reset recycled state, hidden when nothing was detected.
        val chip = binding.protectionChip
        if (data.packer.isProtected) {
            val warn = data.packer.vmpLikely
            chip.isVisible = true
            chip.text = if (warn) {
                context.getString(R.string.protection_vmp_label, data.packer.name)
            } else {
                data.packer.name
            }
            chip.contentDescription = context.getString(R.string.protection_label, chip.text)
            chip.setBackgroundResource(if (warn) R.drawable.chip_bg_warn else R.drawable.chip_bg)
            val fg = MaterialColors.getColor(
                chip,
                if (warn) com.google.android.material.R.attr.colorOnErrorContainer
                else com.google.android.material.R.attr.colorOnTertiaryContainer
            )
            chip.setTextColor(fg)
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (warn) R.drawable.ic_warn else R.drawable.ic_shield, 0, 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(
                chip, android.content.res.ColorStateList.valueOf(fg)
            )
        } else {
            chip.isVisible = false
            chip.text = null
            chip.contentDescription = null
        }

        ViewCompat.replaceAccessibilityAction(
            binding.root,
            AccessibilityActionCompat.ACTION_CLICK,
            context.getString(R.string.unpack),
            null
        )
        ViewCompat.replaceAccessibilityAction(
            binding.root,
            AccessibilityActionCompat.ACTION_LONG_CLICK,
            context.getString(R.string.copy_package_name)
        ) { _, _ ->
            onCopyPackageName?.invoke(data)
            onCopyPackageName != null
        }
    }
}
