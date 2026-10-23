package com.cx.cakepet

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import net.lingala.zip4j.ZipFile

/**
 * 角色素材管理页（即“导入页”）：
 * - 列出全部素材模板（预装 + 用户上传 FILES），每张卡片含动态预览、名称、删除（仅 FILES）、加入按钮。
 * - 「加入」：把该素材模板加为一个首页实例（同模板可重复，pet_config_<instanceId> 完全独立）。直接加入，不弹窗。
 * - 「删除」（仅用户上传）：二次确认后级联清该素材包的全部首页实例、配置与私有存储目录，
 *   仅删软件内存储，不动用户本地的原文件。
 * - 页面级「＋导入素材包(.ZIP)」：直接访问存储选择 .zip 并导入，不再跳转额外页面。
 */
class RoleManageActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CatalogAdapter

    // 直接访问存储选择 .zip 素材包（不跳转额外页面）。
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importZip(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_manage)
        findViewById<View>(R.id.status_bar_spacer).apply {
            layoutParams.height = getStatusBarHeight()
            requestLayout()
        }
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_import_zip).setOnClickListener {
            // 直接打开存储选择器，不再跳转单独页面（设计细节不全部抛给用户）。
            pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }

        // 素材模板清单改为 RecyclerView + ItemTouchHelper：长按拖拽重排（排序仅本页生效）+ 实时预览。
        recycler = findViewById(R.id.role_list)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CatalogAdapter()
        recycler.adapter = adapter

        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from !in adapter.items.indices || to !in adapter.items.indices) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            // 拖拽落定：仅持久化素材页自身的模板顺序，不影响首页实例排序。
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                persistOrder()
            }
        })
        touch.attachToRecyclerView(recycler)
    }

    override fun onResume() {
        super.onResume()
        // 导入/删除后回到本页时刷新缓存与列表。
        RoleRegistry.refreshUserRoles(this)
        loadList()
    }

    override fun onPause() {
        adapter.stopAll()
        super.onPause()
    }

    /** 重建素材模板列表（按素材页持久化顺序；导入/删除/重排后刷新）。预览播放器由适配器自管。 */
    private fun loadList() {
        adapter.stopAll()
        // 计数一次性读入：getHomeRoles 是 runBlocking 的 DataStore 读，不能在 bind 里逐项调用。
        val counts = RoleStore.getHomeRoles(this).groupingBy { it.templateId }.eachCount()
        adapter.submit(RoleStore.orderedCatalog(this), counts)
    }

    /** 拖拽落定后持久化素材页的模板顺序（仅本页展示用，与首页实例排序互不干扰）。 */
    private fun persistOrder() {
        RoleStore.setCatalogOrder(this, adapter.items.map { it.id })
    }

    /** 素材模板 → 首页实例（可重复导入，配置按 instanceId 完全独立）。直接加入，不弹窗。 */
    private fun importToHome(role: RoleDescriptor) {
        val instanceId = "inst_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        RoleStore.addHomeRole(this, HomeRoleInstance(instanceId, role.id))
        Toast.makeText(this, "已加入：${role.displayName}", Toast.LENGTH_SHORT).show()
        // 实时更新该卡片的已加入数量（局部刷新，预览动画不中断）。
        adapter.updateCount(role.id, adapter.countFor(role.id) + 1)
    }

    /** 从本机选择 .zip 角色素材包导入：
     *  解压到 filesDir/roles/<uuid>/（软件私有存储），校验 modes.json，
     *  把素材模板写入 user_roles 清单（source=FILES）。不做校验和、不按内容去重——
     *  重复上传同一包会生成两个独立素材包。删除时仅清理本私有目录，不动用户原文件。 */
    private fun importZip(uri: Uri) {
        val id = "u_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val dest = File(filesDir, "roles/$id")
        dest.mkdirs()
        // 先把用户选择的流落地成临时文件：ZipFile(File, Charset) 才能指定文件名编码，
        // 而 ZipInputStream 只能按平台默认（UTF-8）解码文件名，遇到 GBK 编码（常见中文 Windows
        // 打的包、未置 UTF-8 标志位）会抛 ZipException: MALFORMED[1]。
        val tmp = File(cacheDir, "zip_import_$id.tmp")
        val opened = try {
            contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { ins.copyTo(it) }
            } != null
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            dest.deleteRecursively(); tmp.delete()
            Toast.makeText(this, "无法打开所选文件", Toast.LENGTH_LONG).show()
            return
        }
        // 候选文件名编码：先 UTF-8（规范包），再回退 GB18030 / GBK（中文 Windows 打的包、未置
        // UTF-8 标志位的中文名包）。接收他人 zip 时编码不可预知，逐编码完整解压后，以【能否通过
        // 资源包校验】作为该编码是否正确的判据——zip4j 不因平台差异抛 MALFORMED，且能按指定字符集
        // 稳定解码文件名；用"完整校验通过"而非"仅看 modes.json 是否存在"来判定，避免错误编码解出的
        // 乱码文件名骗过浅校验导致后续才报错。
        val charsets = listOfNotNull(
            StandardCharsets.UTF_8,
            runCatching { Charset.forName("GB18030") }.getOrNull(),
            runCatching { Charset.forName("GBK") }.getOrNull()
        )
        var ok = false
        var lastErrors: List<String> = emptyList()
        for (cs in charsets) {
            try {
                ZipFile(tmp).use { zip ->
                    zip.setCharset(cs)
                    val destCanon = dest.canonicalPath
                    for (header in zip.fileHeaders) {
                        val name = header.fileName
                        val outFile = File(dest, name)
                        val outCanon = outFile.canonicalPath
                        // Zip Slip 防护：条目路径不可越出目标目录（含 ../ 穿越、绝对路径等非法包）。
                        if (outCanon != destCanon && !outCanon.startsWith("$destCanon${File.separator}")) {
                            throw SecurityException("非法的压缩包路径：$name")
                        }
                        if (header.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(header).use { ins ->
                                FileOutputStream(outFile).use { ins.copyTo(it) }
                            }
                        }
                    }
                }
                // 若 zip 自带一层包裹目录，把内容上提，使 modes.json 位于根目录。
                val modesAtRoot = File(dest, "modes.json")
                if (!modesAtRoot.exists()) {
                    val sub = dest.listFiles()?.firstOrNull { it.isDirectory && File(it, "modes.json").exists() }
                    if (sub != null) {
                        sub.listFiles()?.forEach { f -> f.renameTo(File(dest, f.name)) }
                        sub.delete()
                    }
                }
                // 以"通过完整资源包校验"判定该编码正确，否则换下一个编码重试。
                val modesFile = File(dest, "modes.json")
                if (!modesFile.exists()) { dest.deleteRecursively(); dest.mkdirs(); continue }
                val json = try { JSONObject(modesFile.readText()) } catch (_: Exception) { null }
                if (json == null) { dest.deleteRecursively(); dest.mkdirs(); continue }
                val validation = RolePackageContract.validate(json)
                val missingAssets = RolePackageContract.verifyAssets(dest, json)
                val errors = validation.errors + missingAssets.map { "缺少图片：$it" }
                if (errors.isNotEmpty()) {
                    lastErrors = errors
                    dest.deleteRecursively(); dest.mkdirs(); continue
                }
                ok = true
                break
            } catch (_: Exception) {
                dest.deleteRecursively(); dest.mkdirs() // 当前编码/解压失败，换下一个编码重试
            }
        }
        if (!ok) {
            tmp.delete()
            // 若曾解出过合法包结构但资源缺失，给出具体校验错误；否则提示压缩包无效。
            if (lastErrors.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("资源包校验失败")
                    .setMessage(lastErrors.joinToString("\n"))
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                Toast.makeText(this, "解压失败，请确认压缩包是否完整", Toast.LENGTH_LONG).show()
            }
            return
        }
        tmp.delete() // 解压成功，清理临时文件

        // 校验已在上方通过，重新读取 modes.json 写入清单。
        val modesFile = File(dest, "modes.json")
        val json = try { JSONObject(modesFile.readText()) } catch (_: Exception) { null }
        if (json == null) {
            dest.deleteRecursively()
            Toast.makeText(this, "modes.json 解析失败", Toast.LENGTH_LONG).show()
            return
        }
        val packId = try { json.getInt("packId") } catch (_: Exception) { -1 }
        val displayName = json.getString("name")
        val shortName = json.optString("shortName", "")
        RoleStore.addUserRole(
            this,
            UserRoleTemplate(
                id = id,
                displayName = displayName,
                rootPath = id,
                packId = packId,
                shortName = shortName,
                source = "FILES",
                authors = parseAuthors(json)
            )
        )
        RoleRegistry.refreshUserRoles(this)
        loadList()
        Toast.makeText(this, "已导入：$displayName", Toast.LENGTH_SHORT).show()
    }

    /** 删除素材包：二次确认（沿用设置页确认弹窗样式）。 */
    private fun confirmDelete(role: RoleDescriptor) {
        showConfirmDialog(
            title = "删除角色素材",
            bodyHtml = "确定删除「${role.displayName}」？",
            subText = "该素材及实例都将被移除！",
            positiveText = "删除"
        ) { doDelete(role) }
    }

    /** 级联删除：召回激活实例 → 清配置 DataStore → 移实例 → 移素材清单 → 删私有目录。 */
    private fun doDelete(role: RoleDescriptor) {
        val home = RoleStore.getHomeRoles(this).filter { it.templateId == role.id }
        home.forEach { PetService.instance?.recall(it.instanceId) }
        home.forEach { iid ->
            lifecycleScope.launch(Dispatchers.IO) {
                applicationContext.preferencesDataStoreFile("pet_config_$iid").delete()
                applicationContext.preferencesDataStoreFile("pet_bounds_$iid").delete()
            }
        }
        RoleStore.removeHomeRolesByTemplate(this, role.id)
        RoleStore.removeUserRole(this, role.id)
        val dir = File(filesDir, "roles/${role.rootPath}")
        if (dir.exists()) dir.deleteRecursively()
        loadList()
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    /** payload：仅刷新卡片计数（避免重建预览播放器）。 */
    private val PAYLOAD_COUNT = Any()

    /** 素材模板列表适配器：动态预览（PreviewPlayer）+ 长按拖动重排（排序仅本页生效）。 */
    inner class CatalogAdapter : RecyclerView.Adapter<CatalogAdapter.VH>() {

        val items = mutableListOf<RoleDescriptor>()
        private val players = mutableMapOf<String, PreviewPlayer>()
        private val mgrCache = mutableMapOf<String, ImageModeManager>()
        /** templateId → 已加入首页的实例数量（由 loadList 一次性读入，不随 bind 逐项查盘）。 */
        private val counts = mutableMapOf<String, Int>()

        fun submit(list: List<RoleDescriptor>, countMap: Map<String, Int> = emptyMap()) {
            items.clear()
            items.addAll(list)
            counts.clear()
            counts.putAll(countMap)
            notifyDataSetChanged()
        }

        fun countFor(templateId: String): Int = counts[templateId] ?: 0

        /** 计数文案：始终显示，含 "(0)"——便于一眼看出该素材包尚未加入首页。 */
        private fun bindCount(tv: TextView, templateId: String) {
            tv.visibility = View.VISIBLE
            tv.text = "(${countFor(templateId)})"
        }

        /** 单项计数变更：仅局部刷新该卡片的计数文案，避免重建预览播放器导致动画重启。 */
        fun updateCount(templateId: String, newCount: Int) {
            counts[templateId] = newCount
            val pos = items.indexOfFirst { it.id == templateId }
            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_COUNT)
        }

        fun move(from: Int, to: Int) {
            if (from == to) return
            val it = items.removeAt(from)
            items.add(to, it)
            notifyItemMoved(from, to)
        }

        fun stopAll() {
            players.values.forEach { it.stop() }
            players.clear()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_role_catalog, parent, false)
            return VH(v)
        }

        /** 仅刷新计数时走此重载（payload），不触碰预览播放器。 */
        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
                return
            }
            val role = items.getOrNull(position) ?: return
            holder.roleId = role.id
            holder.count?.let { bindCount(it, role.id) }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val role = items[position]
            holder.roleId = role.id
            val ctx = holder.itemView.context
            // 仅素材卡片布局含这些视图。
            val name = holder.name ?: return
            val thumb = holder.thumb ?: return
            val delete = holder.delete ?: return
            val importBtn = holder.importBtn ?: return
            name.text = role.displayName
            // 已加入首页的实例数量：小号灰字紧跟标题后实时显示（如 "(2)"）。
            holder.count?.let { bindCount(it, role.id) }
            // 作者署名：小号灰字显示在标题下方；无作者信息时隐藏该行，避免留空行。
            val authorsTv = holder.authors
            if (authorsTv != null) {
                if (role.authors.isEmpty()) {
                    authorsTv.visibility = View.GONE
                } else {
                    authorsTv.visibility = View.VISIBLE
                    authorsTv.text = "by " + role.authors.joinToString(", ")
                }
            }

            // 复用/重建该模板的预览播放器（静坐一遍 → 之后随机动作循环）。
            players[role.id]?.stop()
            val mgr = mgrCache.getOrPut(role.id) {
                ImageModeManager(ctx, buildSpritePackForRole(ctx, role))
            }
            val player = PreviewPlayer(mgr, thumb, ctx.resources.displayMetrics.density)
            players[role.id] = player
            thumb.setOnClickListener { player.triggerPat() }
            player.start()

            // 删除（仅用户上传 FILES 可见；预装 GONE）、加入（导入为首页实例）。
            val isFiles = role.source == RoleSource.FILES
            delete.visibility = if (isFiles) View.VISIBLE else View.GONE
            delete.setOnClickListener { confirmDelete(role) }
            importBtn.setOnClickListener { importToHome(role) }
        }

        override fun onViewRecycled(holder: VH) {
            val id = holder.roleId
            if (id.isNotEmpty()) {
                players[id]?.stop()
                players.remove(id)
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var roleId: String = ""
            // 视图字段可空：布局稳定含这些 id，但保留空安全以稳妥。
            val thumb: ImageView? = itemView.findViewById(R.id.role_thumb)
            val name: TextView? = itemView.findViewById(R.id.role_name)
            val count: TextView? = itemView.findViewById(R.id.role_count)
            val authors: TextView? = itemView.findViewById(R.id.role_authors)
            val delete: ImageView? = itemView.findViewById(R.id.iv_delete)
            val importBtn: Button? = itemView.findViewById(R.id.btn_import)
        }
    }
}
