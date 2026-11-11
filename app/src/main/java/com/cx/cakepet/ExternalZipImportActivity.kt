package com.cx.cakepet

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Entry point for opening or sharing a ZIP from another app.
 * Inspect only the archive structure; each existing importer owns validation,
 * user consent and final changes to CakePet's stored data.
 */
class ExternalZipImportActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_EXTERNAL_IMPORT = "cakepet_external_zip_import"
        private const val MAX_INPUT_BYTES = 2_000_000_000L
        private const val MAX_ENTRIES = 50_000
    }

    private enum class Kind { BACKUP, MATERIAL }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.getItemAt(0)?.uri
            }
            else -> null
        }
        if (uri == null || uri.scheme !in setOf("content", "file")) {
            Toast.makeText(this, "无法读取选中的 ZIP 文件", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { detect(uri) }
            }
            if (!isFinishing && !isDestroyed) {
                found.onSuccess { kind ->
                    val target = if (kind == Kind.BACKUP) GlobalSettingsActivity::class.java
                        else RoleManageActivity::class.java
                    startActivity(Intent(this@ExternalZipImportActivity, target).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(contentResolver, "CakePet ZIP", uri)
                        putExtra(EXTRA_EXTERNAL_IMPORT, true)
                    })
                }.onFailure { error ->
                    Toast.makeText(this@ExternalZipImportActivity,
                        error.message ?: "不是 CakePet 可导入的 ZIP 文件",
                        Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun detect(uri: Uri): Kind {
        val temp = File(cacheDir, "cakepet_identify_${System.nanoTime()}.zip")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= MAX_INPUT_BYTES) { "ZIP 文件过大" }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("无法打开 ZIP 文件")
            // Scan filenames without extracting potentially untrusted content.
            // Also try legacy Chinese Windows filename encodings.
            val candidates = listOfNotNull(StandardCharsets.UTF_8,
                runCatching { Charset.forName("GB18030") }.getOrNull(),
                runCatching { Charset.forName("GBK") }.getOrNull()).distinct()
            for (charset in candidates) {
                val kind = runCatching {
                    ZipFile(temp).use { zip ->
                        zip.setCharset(charset)
                        val entries = zip.fileHeaders
                        require(entries.size <= MAX_ENTRIES) { "ZIP 文件数量过多" }
                        val files = entries.filterNot { it.isDirectory }
                            .associateBy { it.fileName.replace('\\', '/').removePrefix("./") }
                        val backup = files["manifest.json"]
                        if (backup != null) {
                            require(backup.uncompressedSize in 1..131072) {
                                "软件备份标识文件异常"
                            }
                            val text = zip.getInputStream(backup).use {
                                it.readBytes().also { data ->
                                    require(data.size <= 131072) { "软件备份标识文件过大" }
                                }.toString(Charsets.UTF_8)
                            }
                            require(JSONObject(text).optString("type") == "cakepet-backup") {
                                "备份标识无效"
                            }
                            Kind.BACKUP
                        } else {
                            val found = files.keys.any { path ->
                                val pieces = path.split('/')
                                // Either an ordinary material pack or a single wrapper folder.
                                pieces.lastOrNull() == "modes.jsonc" && pieces.size in 1..2
                            }
                            if (found) Kind.MATERIAL else null
                        }
                    }
                }.getOrNull()
                if (kind != null) return kind
            }
            error("未找到 CakePet 素材包或软件备份")
        } finally {
            temp.delete()
        }
    }
}
