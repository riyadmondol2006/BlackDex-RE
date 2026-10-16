package top.niunaijun.blackdex.data.entity

import android.graphics.drawable.Drawable
import top.niunaijun.blackdex.util.PackerInfo

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val packer: PackerInfo = PackerInfo.NONE,
    val system: Boolean = false,
)
