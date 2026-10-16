package top.niunaijun.blackdex.util

import java.io.File
import java.util.zip.ZipFile

/**
 * Best-effort static identification of the DEX packer/protector an APK ships, by matching known
 * file/entry signatures. Useful before a dump so the user knows what to expect — in particular,
 * packers that use method virtualization (VMP) cannot be fully recovered by any dex dumper.
 */
data class PackerInfo(
    val name: String,
    val isProtected: Boolean,
    val vmpLikely: Boolean,
) {
    companion object {
        val NONE = PackerInfo("None", false, false)
    }
}

object PackerDetector {

    private data class Sig(
        val packer: String,
        val markers: List<String>,
        val vmp: Boolean = false,
    )

    // Order matters: more specific first.
    private val SIGNATURES = listOf(
        Sig("Ijiami (爱加密)", listOf("assets/ijm_lib/", "libexec.so", "libexecmain.so", "ijiami", "ijmdal"), vmp = true),
        Sig("360 Jiagu (加固保)", listOf("libjiagu.so", "libjiagu_art.so", "libjiagu_a64.so", "assets/libjiagu")),
        Sig("Tencent Legu (乐固)", listOf("libshella", "libshellx", "libtup.so", "libtxfdc.so", "libnmr.so", "com/tencent/StubShell")),
        Sig("Bangcle / SecNeo", listOf("libsecexe.so", "libsecmain.so", "libdexhelper.so", "libsecshell.so", "bangcle_classes.jar", "assets/bangcleplugin")),
        Sig("Baidu (百度加固)", listOf("libbaiduprotect.so", "baiduprotect1.jar", "libbaiduprotect_x86.so")),
        Sig("Alibaba (阿里聚安全)", listOf("libmobisec.so", "libsgmain.so", "libsgsecuritybody.so", "aliprotect")),
        Sig("NetEase (易盾)", listOf("libnesec.so", "assets/nesec")),
        Sig("Kiwi / Nagapt", listOf("libnagapt.so", "libkiwiexec.so", "libkiwi.so")),
        Sig("DexProtector", listOf("assets/dp.arm", "assets/dp.arm-v7", "libdexprotector.so")),
        Sig("APKProtect", listOf("libAPKProtect.so", "apkprotect")),
        Sig("Qihoo VMP", listOf("libprotectClass.so")),
        // Google Play Integrity / pairip (2024+ wrapping)
        Sig("Google pairip", listOf("libpairipcore.so", "com/pairip/"), vmp = true),
    )

    fun detect(apk: File): PackerInfo {
        val entries = HashSet<String>()
        try {
            ZipFile(apk).use { zip ->
                val e = zip.entries()
                var count = 0
                while (e.hasMoreElements() && count < 6000) {
                    entries.add(e.nextElement().name.lowercase())
                    count++
                }
            }
        } catch (t: Throwable) {
            return PackerInfo.NONE
        }
        for (sig in SIGNATURES) {
            for (marker in sig.markers) {
                val m = marker.lowercase()
                if (entries.any { it.contains(m) }) {
                    return PackerInfo(sig.packer, true, sig.vmp)
                }
            }
        }
        return PackerInfo.NONE
    }
}
