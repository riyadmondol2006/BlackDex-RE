package top.niunaijun.blackdex.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a dump directory of `.dex` files into a single jadx-friendly zip and shares it, so the
 * user can get the output off the app's private storage. Inside the zip the dex files are renamed
 * classes.dex / classes2.dex / … (largest first), the layout tools like jadx expect.
 */
object ExportUtil {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun zipDump(context: Context, dumpDir: File, packageName: String): File? {
        val dexFiles = dumpDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?.sortedByDescending { it.length() } ?: return null
        if (dexFiles.isEmpty()) return null

        val outDir = File(context.cacheDir, "export").apply { mkdirs() }
        val zipFile = File(outDir, "${packageName}_dex.zip")
        runCatching {
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                dexFiles.forEachIndexed { index, dex ->
                    val entryName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                    zos.putNextEntry(ZipEntry(entryName))
                    dex.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }.onFailure { return null }
        return zipFile
    }

    fun shareZip(context: Context, zipFile: File) {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share dumped DEX"))
    }

    /** Try to open the dump folder in a file manager (best effort). */
    fun openFolder(context: Context, dumpDir: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), dumpDir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
