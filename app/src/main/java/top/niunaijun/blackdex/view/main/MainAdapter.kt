package top.niunaijun.blackdex.view.main

import android.view.ViewGroup
import androidx.core.view.isVisible
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.databinding.ItemPackageBinding
import top.niunaijun.blackdex.util.newBindingViewHolder
import top.niunaijun.blackdex.view.base.BaseAdapter

class MainAdapter : BaseAdapter<ItemPackageBinding, AppInfo>() {
    override fun getViewBinding(parent: ViewGroup): ItemPackageBinding {
        return newBindingViewHolder(parent, false)
    }

    override fun initView(binding: ItemPackageBinding, position: Int, data: AppInfo) {
        binding.icon.setImageDrawable(data.icon)
        binding.name.text = data.name
        binding.packageName.text = data.packageName
        if (data.packer.isProtected) {
            binding.protectionChip.isVisible = true
            val label = if (data.packer.vmpLikely) "${data.packer.name} · VMP" else data.packer.name
            binding.protectionChip.text = label
            binding.protectionChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (data.packer.vmpLikely)
                    top.niunaijun.blackdex.R.drawable.ic_warn
                else
                    top.niunaijun.blackdex.R.drawable.ic_shield,
                0, 0, 0
            )
        } else {
            binding.protectionChip.isVisible = false
        }
    }
}
