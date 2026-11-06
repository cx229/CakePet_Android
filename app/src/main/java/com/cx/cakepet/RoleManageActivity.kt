package com.cx.cakepet

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
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
 * - 列出全部素材模板（预装 + 用户上传 FILES），每张卡片含动态预览、名称及含“加入首页”的操作菜单。
 * - 「加入」：把该素材模板加为一个首页实例（同模板可重复，pet_config_<instanceId> 完全独立）。直接加入，不弹窗。
 * - 「删除」（仅用户上传）：二次确认后级联清该素材包的全部首页实例、配置与私有存储目录，
 *   仅删软件内存储，不动用户本地的原文件。
 * - 页面级「＋导入素材包(.ZIP)」：直接访问存储选择 .zip 并导入，不再跳转额外页面。
 */
class RoleManageActivity : AppCompatActivity() {

    companion object {
        /** 导入相关日志标签：解压失败时用于在 logcat 定位完整异常栈。 */
        private const val TAG_IMPORT = "RoleImport"

        /**
         * 从 org.json 的异常消息（形如 "Expected a ',' or ']' at 1234 [character 5 line 20]"）
         * 中提取出错【字符位置】，并截取其前后各 100 字符作为上下文返回。
         * 用于一眼识别多余逗号、混入注释、文件被截断等非法 JSON 情形。
         * 返回内容已限长并压平换行，避免巨大 JSON 刷屏 logcat。
         */
        private fun jsonErrorContext(raw: String, message: String?): String {
            val idx = Regex("""at\s+(\d+)""").find(message ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return ""
            if (idx !in 0..raw.length) return ""
            val from = (idx - 100).coerceAtLeast(0)
            val to = (idx + 100).coerceAtMost(raw.length)
            return "\n  >>> 出错位置 $idx 附近:\n  ${raw.substring(from, to).replace("\n", " ")}"
        }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: CatalogAdapter
    private var exportRole: RoleDescriptor? = null

    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val role = exportRole
        if (uri != null && role != null) runCatching { RolePackEditing.export(this, role, uri) }
            .onSuccess { Toast.makeText(this, "ZIP 已导出", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, it.message ?: "导出失败", Toast.LENGTH_LONG).show() }
    }

    /** Match Settings' rounded rename dialog; only the material name is required here. */
    private fun askMaterialName(title: String, initial: String, submit: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_rename_role, null)
        view.findViewById<TextView>(R.id.tv_title).text = title
        val card = (view as ViewGroup).getChildAt(0) as ViewGroup
        val content = card.getChildAt(0) as LinearLayout
        (content.getChildAt(1) as TextView).text = "素材包名称"
        content.getChildAt(3).visibility = View.GONE
        view.findViewById<EditText>(R.id.et_short).visibility = View.GONE
        val input = view.findViewById<EditText>(R.id.et_name)
        input.hint = "请输入素材包名称"
        input.setText(initial)
        input.setSelectAllOnFocus(initial.isNotBlank())
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.btn_negative).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btn_positive).apply {
            text = "确定"
            setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> input.error = "请填写素材包名称"
                    name.length > 60 -> input.error = "名称不能超过 60 个字符"
                    else -> runCatching { submit(name) }
                        .onSuccess { dialog.dismiss() }
                        .onFailure { input.error = it.message ?: "操作失败" }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun newRole() {
        askMaterialName("创建素材包", "") { name ->
            val id = RolePackEditing.duplicate(this, RoleRegistry.DEFAULT, name)
            startActivity(android.content.Intent(this, RolePackEditorActivity::class.java)
                .putExtra("role_id", id))
        }
    }

    private fun copyRole(role: RoleDescriptor) {
        askMaterialName("复制素材包", role.displayName) { name ->
            RolePackEditing.duplicate(this, role, name)
            loadList()
        }
    }

    private fun editRole(role: RoleDescriptor) {
        if (role.source == RoleSource.ASSETS) {
            showConfirmDialog(
                title = "模板不可编辑",
                bodyHtml = "模板「${role.displayName}」不可编辑，请通过复制或新建素材包创建可编辑副本。",
                positiveText = "知道了",
                showNegative = false
            ) { }
        } else startActivity(android.content.Intent(this, RolePackEditorActivity::class.java).putExtra("role_id", role.id))
    }

    private fun showRoleOperations(anchor: View, role: RoleDescriptor) {
        val options = listOf("加入首页", "编辑", "复制", "导出 ZIP", "删除")
        UnifiedOptionMenu.show(anchor, options, setOf("删除")) { choice ->
            when (choice) {
                "加入首页" -> importToHome(role)
                "编辑" -> editRole(role)
                "复制" -> copyRole(role)
                "删除" -> confirmDelete(role)
                "导出 ZIP" -> {
                    exportRole = role
                    exportZip.launch(role.displayName + ".zip")
                }
            }
        }
    }


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
        findViewById<Button>(R.id.btn_new_role).setOnClickListener { newRole() }
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
        val errors = runCatching {
            RolePackEditing.validationErrorsForRole(this, role)
        }.getOrElse { listOf("读取素材包失败：${it.message}") }
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("素材包暂时无法加入角色")
                .setMessage(errors.mapIndexed { index, error -> "${index + 1}. $error" }.joinToString("\n"))
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val instanceId = "inst_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        RoleStore.addHomeRole(this, HomeRoleInstance(instanceId, role.id))
        Toast.makeText(this, "已加入：${role.displayName}", Toast.LENGTH_SHORT).show()
        // 实时更新该卡片的已加入数量（局部刷新，预览动画不中断）。
        adapter.updateCount(role.id, adapter.countFor(role.id) + 1)
    }

    /** 从本机选择 .zip 角色素材包导入：
     *  解压到 filesDir/roles/<uuid>/（软件私有存储），校验 modes.jsonc，
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
        // 稳定解码文件名；用"完整校验通过"而非"仅看 modes.jsonc 是否存在"来判定，避免错误编码解出的
        // 乱码文件名骗过浅校验导致后续才报错。
        val charsets = listOfNotNull(
            StandardCharsets.UTF_8,
            runCatching { Charset.forName("GB18030") }.getOrNull(),
            runCatching { Charset.forName("GBK") }.getOrNull()
        )
        var ok = false
        var lastErrors: List<String> = emptyList()
        // 记录最后一次解压异常：原先 catch 直接吞掉异常，失败时无从判断是文件损坏、
        // 文件名过长还是路径非法。现保留异常本体，失败时打印完整栈并提示摘要。
        var lastThrowable: Throwable? = null
        // modes.jsonc 读取/解析失败单独留存：它走 continue 而非抛异常，若不记录，
        // 最终提示会误报成"未找到 modes.jsonc"（实则文件存在、只是内容非法）。
        var jsonError: Throwable? = null
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
                // 若 zip 自带一层包裹目录，把内容上提，使 modes.jsonc 位于根目录。
                val modesAtRoot = File(dest, "modes.jsonc")
                if (!modesAtRoot.exists()) {
                    val sub = dest.listFiles()?.firstOrNull { it.isDirectory && File(it, "modes.jsonc").exists() }
                    if (sub != null) {
                        sub.listFiles()?.forEach { f -> f.renameTo(File(dest, f.name)) }
                        sub.delete()
                    }
                }
                // ZIP 解压成功即接受；资源或配置缺陷留到“加入角色”时诊断。
                // 仍需使用合理的文件名编码，并保留可恢复的原始配置。
                val modesFile = File(dest, "modes.jsonc")
                if (!modesFile.exists()) {
                    val sub = dest.listFiles()?.firstOrNull { it.isDirectory && File(it, "modes.jsonc").isFile }
                    if (sub != null) {
                        sub.listFiles()?.forEach { it.renameTo(File(dest, it.name)) }
                        sub.delete()
                    }
                }
                val originalName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() } ?: "导入素材包"
                RolePackEditing.prepareEditable(dest, id, originalName)
                ok = true
                break
            } catch (e: Exception) {
                lastThrowable = e
                Log.e(TAG_IMPORT, "解压失败（charset=$cs，文件=${tmp.length()}字节）", e)
                dest.deleteRecursively(); dest.mkdirs() // 当前编码/解压失败，换下一个编码重试
            }
        }
        if (!ok) {
            // 先记录大小再删除：tmp.delete() 之后 length() 恒为 0，日志会失去参考价值。
            val zipSize = tmp.length()
            tmp.delete()
            // 若曾解出过合法包结构但资源缺失，给出具体校验错误；否则提示压缩包无效。
            if (lastErrors.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("资源包校验失败")
                    .setMessage(lastErrors.joinToString("\n"))
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                // 把真正的失败原因暴露出来：原先只给一句笼统提示，异常被 catch 吞掉，
                // 无法区分【压缩包损坏】【文件名过长】【Zip Slip 拦截】【磁盘空间不足】等情况。
                // - logcat（tag=RoleImport）：完整异常栈；
                // - Toast：异常摘要（受显示长度限制，完整信息看 logcat）。
                // 优先取解压异常；其次取 modes.jsonc 的读取/解析异常。
                val t = lastThrowable ?: jsonError
                val summary = if (t != null) {
                    "${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"
                } else {
                    "未捕获到异常（多为未找到 modes.jsonc，属包结构问题）"
                }
                Log.e(TAG_IMPORT, "解压失败汇总：$summary（已试编码=$charsets，文件大小=${zipSize}字节）", t)
                Toast.makeText(this, "解压失败：$summary", Toast.LENGTH_LONG).show()
            }
            return
        }
        tmp.delete() // 解压成功，清理临时文件

        // 导入时仅整理结构，严格检查留给加入角色。
        val modesFile = File(dest, "modes.jsonc")
        val json = try { parseJsonc(modesFile.readText()) } catch (_: Exception) { null }
        if (json == null) {
            dest.deleteRecursively()
            Toast.makeText(this, "modes.jsonc 解析失败", Toast.LENGTH_LONG).show()
            return
        }
        val packId = try { json.getInt("packId") } catch (_: Exception) { -1 }
        val displayName = json.optString("name", "导入素材包")
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
        if (role.source == RoleSource.ASSETS) {
            showConfirmDialog(
                title = "无法删除",
                bodyHtml = "预装素材包「${role.displayName}」不可删除。",
                positiveText = "知道了",
                showNegative = false
            ) { }
            return
        }
        showConfirmDialog(
            title = "删除角色素材",
            bodyHtml = "确定删除「${role.displayName}」？",
            subText = "该素材及实例都将被移除！",
            positiveText = "删除"
        ) { doDelete(role) }
    }

    /** 级联删除：召回激活实例 → 清配置 DataStore → 移实例 → 移素材清单 → 删私有目录。 */
    private fun doDelete(role: RoleDescriptor) {
        if (role.source != RoleSource.FILES) return
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
            holder.updateTitleWidth()
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val role = items[position]
            holder.roleId = role.id
            val ctx = holder.itemView.context
            // 仅素材卡片布局含这些视图。
            val name = holder.name ?: return
            val thumb = holder.thumb ?: return
            name.text = role.displayName
            // 已加入首页的实例数量：小号灰字紧跟标题后实时显示（如 "(2)"）。
            holder.count?.let { bindCount(it, role.id) }
            holder.updateTitleWidth()
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

            // 删除统一置于更多菜单，预装模板通过弹窗提示不可删除。
            holder.itemView.setOnClickListener {
                showRoleOperations(holder.options ?: holder.itemView, role)
            }
            holder.options?.setOnClickListener { showRoleOperations(it, role) }
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
            private val titleRow: LinearLayout? = itemView.findViewById(R.id.role_title_row)

            /**
             * Reserve the count label's natural (single-line) width first.
             * A short name stays wrap_content; a long name wraps within the
             * remaining width. Recalculate for both layout width and count changes.
             */
            fun updateTitleWidth() {
                val row = titleRow ?: return
                val title = name ?: return
                val badge = count ?: return
                if (row.width <= 0) return
                val naturalCountWidth = kotlin.math.ceil(
                    badge.paint.measureText(badge.text.toString()).toDouble()
                ).toInt() + badge.compoundPaddingLeft + badge.compoundPaddingRight
                val margin = (badge.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?.let { it.marginStart + it.marginEnd } ?: 0
                val available = (row.width - naturalCountWidth - margin).coerceAtLeast(1)
                if (title.maxWidth != available) title.maxWidth = available
            }

            init {
                titleRow?.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    if (right > left) updateTitleWidth()
                }
            }
            val authors: TextView? = itemView.findViewById(R.id.role_authors)
            val options: View? = itemView.findViewById(R.id.btn_role_options)
        }
    }
}
