package com.cx.cakepet

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.content.Intent
import com.google.android.material.snackbar.Snackbar
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.method.LinkMovementMethod
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.text.TextUtils
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Edit incomplete packs incrementally; validate strictly only when adding a home role. */
open class RolePackEditorActivity : AppCompatActivity() {
    private val linkedMode: Boolean get() = intent.hasExtra("linked_title")
    private var linkedReadOnly = false
    private var linkedApproved = false
    private var linkedTitle = ""
    private var linkedPaths = emptySet<String>()
    private var linkedPackName = ""
    private var linkedTreeContainer: LinearLayout? = null
    private lateinit var linkedPack: RoleDescriptor

    /** Reuses the existing editor's actual recursive folder tree and Function dialogs. */
    private fun setupLinkedOverlay() {
        val instance = intent.getStringExtra("source_instance_id") ?: run { finish(); return }
        val pack = RoleStore.descriptorForInstance(this, instance) ?: run { finish(); return }
        linkedPack = pack
        id = pack.id
        linkedReadOnly = pack.source == RoleSource.ASSETS
        linkedPackName = pack.displayName
        linkedTitle = intent.getStringExtra("linked_title").orEmpty()
        linkedPaths = intent.getStringArrayListExtra("linked_paths").orEmpty().toSet()
        try {
            root = if (linkedReadOnly) assets.open("${pack.rootPath}/modes.jsonc")
                .bufferedReader().use { parseJsonc(it.readText()) }
            else {
                draft = RolePackEditing.draft(this, id)
                RolePackEditing.prepareEditable(draft, id, pack.displayName)
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "读取素材包失败", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) })
        showLinkedFunctions()
    }

    private fun linkedConsent(action: () -> Unit) {
        if (!linkedMode || linkedApproved) { action(); return }
        showConfirmDialog(
            title = "确认编辑共享素材",
            bodyHtml = "正在编辑素材包「${linkedPackName}」，修改将影响使用此素材包的全部角色。",
            subText = "不会修改角色各自的开关、概率等独立设置。",
            positiveText = "继续编辑"
        ) {
            linkedApproved = true
            action()
        }
    }

    /** Localized entry points start fully expanded; the full material editor retains its own folding state. */
    private fun expandLinkedFolders(node: JSONObject, parent: List<String>) {
        node.keys().asSequence().filter {
            it !in setOf("thinking", "modes", "note", "usage")
        }.forEach { name ->
            val path = parent + name
            val key = path.joinToString("/")
            val relevant = linkedPaths.any {
                it == key || it.startsWith(key + "/") || key.startsWith(it + "/")
            }
            if (!relevant) return@forEach
            val child = node.optJSONObject(name) ?: return@forEach
            if (!child.has("modes")) {
                openedFunctionFolders.add(key)
                expandLinkedFolders(child, path)
            }
        }
    }

    private fun showLinkedFunctions() {
        val count = RoleStore.getHomeRoles(this).count { it.templateId == id }
        val dialog = editor("${linkedTitle} · 功能/动画", compact = true,
            readOnly = true, subtitle = "素材包：${linkedPackName}") { form ->
            addDetailSection(form, sharedNotice(
                if (linkedReadOnly) "预装素材包 · 只读" else "共享素材配置",
                count,
                if (linkedReadOnly) "可查看功能、动画与碎碎念，复制素材包后可编辑。"
                else "修改后将影响使用此素材包的所有角色。",
                linkedReadOnly
            ))
            val tree = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            form.addView(tree)
            linkedTreeContainer = tree
            openedFunctionFolders.clear()
            expandLinkedFolders(root.getJSONObject("functions"), emptyList())
            functionTree(tree, root.getJSONObject("functions"), emptyList())
            ({ })
        }
        dialog.setOnDismissListener {
            detailDialogs.remove(dialog)
            if (!isFinishing) finish()
        }
    }

    private fun linkedReadonlyFunction(path: List<String>, selected: List<String>) {
        val config = PackThinking.parse(root).functions[path] ?: ThinkingConfig()
        editor(functionPathName(path), compact = true, readOnly = true,
            subtitle = functionDescription(path)) { form ->
            val note = dialogSection()
            val summary = when (config.policy) {
                "off" -> "不播放"
                "hold" -> "持续 · ${config.pool.size} 条"
                else -> "轮播 · ${config.pool.size} 条"
            }
            note.addView(detailSectionHeader("碎碎念配置", summary) { linkedReadonlyThinking(path) })
            addDetailSection(form, note)
            val animations = dialogSection()
            animations.addView(detailSectionHeader("当前动画", "动画链 ${selected.size} 个"))
            val modes = root.getJSONObject("modes")
            selected.forEachIndexed { index, key ->
                val row = detailListRow()
                row.addView(detailNumber(index))
                row.addView(linkedModeChains(listOf(key), modes, "", 13f) {
                    linkedReadonlyMode(it)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                animations.addView(row)
            }
            if (selected.isEmpty()) animations.addView(text("暂无关联动画", 12f))
            addDetailSection(form, animations)
            ({ })
        }
    }

    private fun linkedReadonlyThinking(path: List<String>) {
        val config = PackThinking.parse(root).functions[path] ?: ThinkingConfig()
        editor("碎碎念配置", compact = true, readOnly = true,
            subtitle = "功能 · ${functionPathName(path)}") { form ->
            val card = dialogSection()
            val policy = when (config.policy) {
                "off" -> "不播放"
                "hold" -> "持续"
                else -> "轮播"
            }
            card.addView(detailSectionHeader("播放方式", policy))
            val node = functionNode(root, path)
            thinkingPoolJson(node.optJSONObject("thinking")).forEach { (name, times) ->
                val row = detailListRow()
                row.addView(text(thinkingBody(name), 12f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("${times.first}–${times.second}ms", 11f).apply {
                    setTextColor(getColor(R.color.cp_sub))
                    setPadding(px(5), 0, 0, 0)
                })
                card.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = px(5)
                })
            }
            addDetailSection(form, card)
            ({ })
        }
    }

    private fun linkedReadonlyMode(key: String) {
        val mode = root.optJSONObject("modes")?.optJSONObject(key) ?: return
        editor(key, compact = true, readOnly = true) { form ->
            val frames = mode.optJSONArray("frames")
            val firstRes = frames?.optJSONObject(0)?.optString("res").orEmpty()
            if (firstRes.isNotBlank()) {
                runCatching {
                    assets.open("${linkedPack.rootPath}/" + firstRes).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.let { preview ->
                    form.addView(ImageView(this).apply {
                        setImageBitmap(preview)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(-1, px(136)))
                }
            }
            val playback = dialogSection()
            playback.addView(detailSectionHeader("播放循环", when (playbackKind(mode)) {
                "timed" -> "定时循环"
                "infinite" -> "无限循环"
                else -> "播放一次"
            }))
            val next = mode.optString("nextMode").takeUnless { it == "null" }.orEmpty()
            playback.addView(detailSectionHeader("后续动画", next.ifBlank { "无" }))
            addDetailSection(form, playback)
            val thinking = dialogSection()
            thinking.addView(detailSectionHeader("碎碎念配置", modeThinkingSummary(mode)))
            addDetailSection(form, thinking)
            val frameCard = dialogSection()
            frameCard.addView(detailSectionHeader("动画帧", "${frames?.length() ?: 0} 帧"))
            addDetailSection(form, frameCard)
            ({ })
        }
    }

    private lateinit var id: String
    private lateinit var draft: File
    private lateinit var root: JSONObject
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private val expandedResourceFolders = mutableSetOf<String>()
    private var screen = "home"
    private data class EditorPage(val name: String, val mode: String, val scrollY: Int)
    private val pageHistory = java.util.ArrayDeque<EditorPage>()
    private var restoringPage = false
    private val detailDialogs = mutableListOf<android.app.Dialog>()
    private fun openModeFromDetail(key: String) = modeDetails(key)

    /** Enter a real page from nested detail dialogs.
     * Dismiss the whole detail stack rather than hide it: Dialog.hide() retains
     * windows and leaves stale popups when returning through multiple sources.
     * Page history still returns to the originating resource/function list.
     */
    private fun openModeEditorFromDetail(key: String) {
        if (linkedMode) {
            linkedConsent {
                startActivity(Intent(this, RolePackEditorActivity::class.java)
                    .putExtra("role_id", id).putExtra("initial_mode_id", key))
                finish()
            }
            return
        }
        detailDialogs.toList().asReversed().forEach { it.dismiss() }
        showMode(key)
    }
    private val openedFunctionFolders = mutableSetOf<String>()
    // 仅缓存当前功能页的白酥静态图片；链路计算始终读取最新 modes JSON。
    private val templateExamples = mutableMapOf<String, android.graphics.Bitmap?>()
    private val templateJson: JSONObject? by lazy {
        runCatching {
            assets.open("${RoleRegistry.DEFAULT.rootPath}/modes.jsonc")
                .bufferedReader().use { parseJsonc(it.readText()) }
        }.getOrNull()
    }

    /**
     * 按运行时语义展开 nextMode：无限循环是 →∞，nextMode 回到已访问动作是 →◎。
     * timed 的后续仍然可达；infinite 的 nextMode 属于未启用属性，不追踪。
     * 无缓存，以免重命名、循环选项及后继关系修改后展示旧内容。
     */
    private fun modeChain(start: String, modes: JSONObject): List<String> {
        val route = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = start
        while (current.isNotBlank()) {
            if (!visited.add(current)) {
                route.add(current)
                route.add("◎")
                break
            }
            route.add(current)
            val mode = modes.optJSONObject(current) ?: break
            val configured = mode.optString("loopMode")
            val kind = if (configured in listOf("off", "timed", "infinite")) configured
                else if (!mode.optBoolean("loop", false)) "off"
                else if (mode.optJSONObject("duration") != null) "timed" else "infinite"
            if (kind == "infinite") {
                route.add("∞")
                break
            }
            val next = mode.optString("nextMode").takeUnless { it == "null" }.orEmpty()
            if (next.isEmpty()) break
            current = next
        }
        return route
    }

    private fun modeChainText(start: String, modes: JSONObject): String =
        "[" + modeChain(start, modes).joinToString(" > ") + "]"

    /**
     * Keep the existing chain syntax, and make each actual Mode name a link.
     * The infinity/loop symbols, arrows, brackets and labels remain plain text.
     * Span callbacks navigate using real Mode keys, not displayed text.
     */
    private fun linkedModeChains(
        starts: List<String>, modes: JSONObject, label: String, size: Float,
        onModeClick: (String) -> Unit
    ): TextView {
        val richText = SpannableStringBuilder(label)
        starts.forEachIndexed { chainIndex, first ->
            if (chainIndex > 0) richText.append("，")
            richText.append("[")
            modeChain(first, modes).forEachIndexed { index, segment ->
                if (index > 0) richText.append(" > ")
                val begin = richText.length
                richText.append(segment)
                if (modes.optJSONObject(segment) != null) {
                    richText.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { onModeClick(segment) }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = getColor(R.color.cp_sub)
                            ds.isUnderlineText = true
                        }
                    }, begin, richText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            richText.append("]")
        }
        return text("", size).apply {
            setText(richText, TextView.BufferType.SPANNABLE)
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        }
    }

    /** 白酥模板的相同功能路径，随机选链路及链路中的动作，最后随机选一帧。 */
    private fun templateRandomFrame(path: List<String>): android.graphics.Bitmap? {
        val key = path.joinToString("/")
        if (templateExamples.containsKey(key)) return templateExamples[key]
        var cursor = templateJson?.optJSONObject("functions")
        for (segment in path.dropLast(1)) cursor = cursor?.optJSONObject(segment)
        val candidates = cursor?.optJSONArray(path.last()) ?: cursor?.optJSONObject(path.last())?.optJSONArray("modes")
        val modes = templateJson?.optJSONObject("modes")
        val sourcePaths = mutableListOf<List<String>>()
        if (candidates != null && modes != null) {
            for (i in 0 until candidates.length()) {
                val chain = modeChain(candidates.optString(i), modes)
                val valid = chain.distinct().filter { modes.optJSONObject(it) != null }
                if (valid.isNotEmpty()) sourcePaths.add(valid)
            }
        }
        var bitmap: android.graphics.Bitmap? = null
        // 随机链路后随机动作、随机帧；有损坏帧时继续寻找其他有效资源。
        for (chain in sourcePaths.shuffled()) {
            if (bitmap != null) break
            for (modeId in chain.shuffled()) {
                val frames = modes?.optJSONObject(modeId)?.optJSONArray("frames") ?: continue
                val names = (0 until frames.length()).mapNotNull { index ->
                    frames.optJSONObject(index)?.optString("res")?.takeIf { res ->
                        res.isNotBlank() && !res.startsWith("/") &&
                            !res.contains('\\') && res.split('/').none { it == ".." }
                    }
                }
                for (res in names.shuffled()) {
                    bitmap = runCatching {
                        assets.open("${RoleRegistry.DEFAULT.rootPath}/$res").use {
                            BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                    if (bitmap != null) break
                }
                if (bitmap != null) break
            }
        }
        templateExamples[key] = bitmap
        return bitmap
    }
    private var modeId = ""
    private data class ImagePickRequest(val mode: String, val action: String,
                                        val index: Int = -1, val batch: List<Int> = emptyList())
    private var pendingPick: ImagePickRequest? = null
    private var uploadedSelection = mutableListOf<String>()
    private var uploadFolder = "images"
    private var uploadAction = "library"
    private var uploadMode = ""
    private val bulkSelected = mutableSetOf<Int>()
    private var bulkBar: View? = null
    private var bulkCountView: TextView? = null
    private var activeFrameRecycler: RecyclerView? = null
    private var modePageRecycler: RecyclerView? = null
    /** Guard programmatic checkbox updates from re-entering selection callbacks. */
    private var syncingBulkSelection = false
    private var resourceReplacePath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var animation: Runnable? = null

    private fun showWatermarkNotice(corner: ImportWatermark.Corner?) {
        if (corner == null || isFinishing) return
        Snackbar.make(findViewById(android.R.id.content),
            "检测到${corner.label}疑似水印并已去除", Snackbar.LENGTH_LONG)
            .setAction("设置") {
                startActivity(Intent(this, GlobalSettingsActivity::class.java))
            }
            .setActionTextColor(getColor(android.R.color.holo_blue_light))
            .show()
    }

    private fun pickedFileName(uri: Uri): String {
        val cursor = contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "素材"
    }

    /** Reveal imported resources even when their parent folders were previously collapsed. */
    private fun expandResourceParents(relative: String) {
        var parent = File(relative).parent?.replace('\\', '/')
        while (!parent.isNullOrEmpty()) {
            expandedResourceFolders.add(parent)
            parent = File(parent).parent?.replace('\\', '/')
        }
    }

    private fun importPng(uri: Uri, folder: String): String {
        var path = ""
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("无法解码图片")
        val (cleaned, removed) = if (ImportWatermark.enabled(this)) ImportWatermark.clean(bmp, ImportWatermark.level(this))
            else bmp to null
        try {
            require(bmp.width > 0 && bmp.height > 0 &&
                bmp.width.toLong() * bmp.height <= 8_000_000) { "图片尺寸过大" }
            resourceChange { work, _ ->
                val parent = File(work, folder).canonicalFile
                require(parent.toPath().startsWith(work.canonicalFile.toPath())) { "非法文件夹" }
                parent.mkdirs()
                val file = File(parent, RolePackEditing.uniqueName(parent,
                    pickedFileName(uri).substringBeforeLast('.'), ".png"))
                file.outputStream().use { check(cleaned.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                path = file.canonicalFile.relativeTo(work.canonicalFile).invariantSeparatorsPath
            }
        } finally {
            if (cleaned !== bmp) cleaned.recycle()
            bmp.recycle()
        }
        showWatermarkNotice(removed)
        return path
    }

    private val pngPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null && uploadAction == "chooser") {
            pendingPick?.let { showImageChooser(it, uploadedSelection) }
        }
        if (uri != null) attempt {
            val path = importPng(uri, uploadFolder)
            when (uploadAction) {
                "chooser" -> pendingPick?.let {
                    showImageChooser(it, if (it.action == "batch") listOf(path)
                        else (uploadedSelection + path).distinct())
                }
                "append" -> appendFrames(uploadMode, listOf(path))
                else -> {
                    expandResourceParents(path)
                    images()
                }
            }
        }
    }
    private val resourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replacing = resourceReplacePath
        resourceReplacePath = null
        if (uri != null) attempt {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            val (cleaned, removed) = if (ImportWatermark.enabled(this)) ImportWatermark.clean(bitmap, ImportWatermark.level(this))
                else bitmap to null
            try {
                require(bitmap.width > 0 && bitmap.height > 0 &&
                    bitmap.width.toLong() * bitmap.height <= 8_000_000) { "图片尺寸过大" }
                resourceChange { work, _ ->
                    val dest = if (replacing == null) {
                        val parent = File(work, "images").apply { mkdirs() }
                        File(parent, RolePackEditing.uniqueName(parent,
                            pickedFileName(uri).substringBeforeLast('.'), ".png"))
                    } else File(work, replacing)
                    if (replacing != null) require(isImage(dest)) { "原图片不存在" }
                    dest.parentFile?.mkdirs()
                    val format = when (dest.extension.lowercase()) {
                        "jpg", "jpeg" -> android.graphics.Bitmap.CompressFormat.JPEG
                        "webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                        else -> android.graphics.Bitmap.CompressFormat.PNG
                    }
                    dest.outputStream().use {
                        check(cleaned.compress(format, 100, it)) { "图片写入失败" }
                    }
                }
            } finally {
                if (cleaned !== bitmap) cleaned.recycle()
                bitmap.recycle()
            }
            images()
            showWatermarkNotice(removed)
        }
    }
    private val gifPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt {
            val mode = uploadMode
            var paths = emptyList<String>()
            var removed: ImportWatermark.Corner? = null
            resourceChange { work, json ->
                val imported = RolePackEditing.gif(this, uri, work, uploadFolder, pickedFileName(uri)) { corner ->
                    removed = corner
                }
                require(imported.length() > 0) { "GIF 没有可用帧" }
                paths = (0 until imported.length()).map { imported.getJSONObject(it).getString("res") }
                // Diagnose missing images before updating any Mode references or committing the pack.
                paths.forEach { relative ->
                    val file = File(work, relative).canonicalFile
                    require(file.toPath().startsWith(work.canonicalFile.toPath()) &&
                        file.isFile && file.length() > 0L) { "GIF 帧输出缺失：$relative" }
                }
                if (uploadAction == "overwrite" || uploadAction == "append") {
                    val current = json.getJSONObject("modes").getJSONObject(mode).getJSONArray("frames")
                    val updated = JSONArray()
                    if (uploadAction == "append")
                        for (i in 0 until current.length()) updated.put(current.getJSONObject(i))
                    for (i in 0 until imported.length()) updated.put(imported.getJSONObject(i))
                    json.getJSONObject("modes").getJSONObject(mode).put("frames", updated)
                }
            }
            when (uploadAction) {
                "append", "overwrite" -> showMode(mode)
                else -> {
                    paths.forEach(::expandResourceParents)
                    images()
                }
            }
            showWatermarkNotice(removed)
        }
    }

    private fun startUpload(gif: Boolean, folder: String = "", action: String = "library",
                            mode: String = "", request: ImagePickRequest? = null,
                            previous: List<String> = emptyList()) {
        uploadFolder = folder
        uploadAction = action
        uploadMode = mode
        pendingPick = request
        uploadedSelection = previous.toMutableList()
        if (gif) gifPicker.launch(arrayOf("image/gif"))
        else pngPicker.launch(arrayOf("image/*"))
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (linkedMode) { setupLinkedOverlay(); return }
        id = intent.getStringExtra("role_id") ?: run { finish(); return }
        try {
            draft = RolePackEditing.draft(this, id)
            val name = RoleRegistry.byId(id)?.displayName ?: id
            root = RolePackEditing.prepareEditable(draft, id, name)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "打开失败", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_role_pack_editor)
        findViewById<View>(R.id.status_bar_spacer).apply {
            layoutParams.height = getStatusBarHeight()
            requestLayout()
        }
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { navigateBack() }
        titleText = findViewById(R.id.tv_title)
        subtitleText = findViewById(R.id.tv_title_sub)
        findViewById<TextView>(R.id.tv_mode_more).setOnClickListener { anchor ->
            if (screen != "mode") return@setOnClickListener
            val current = modeId
            UnifiedOptionMenu.show(anchor, listOf("重命名", "动画组"), emptySet()) { choice ->
                when (choice) {
                    "重命名" -> renameMode(current) { renamed -> showMode(renamed) }
                    "动画组" -> editModeGroups(current) { /* Stay on current editor. */ }
                }
            }
        }
        scroll = findViewById(R.id.editor_scroll)
        panel = findViewById(R.id.editor_content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        val initialMode = intent.getStringExtra("initial_mode_id")
        home()
        if (initialMode != null && root.optJSONObject("modes")?.has(initialMode) == true)
            showMode(initialMode)
    }
    override fun onDestroy() {
        removeBulkBar()
        stopAnimation()
        if (::draft.isInitialized) draft.deleteRecursively()
        super.onDestroy()
    }
    private fun currentPageScrollY(): Int = modePageRecycler?.scrollY ?: scroll.scrollY

    private fun navigateBack() {
        val previous = pageHistory.pollLast() ?: run { finish(); return }
        restoringPage = true
        try {
            modeId = previous.mode
            when (previous.name) {
                "mode" -> showMode(previous.mode)
                "modes" -> modes()
                "functions" -> functions()
                "images" -> images()
                "thinkings" -> thinkingResources()
                else -> home()
            }
        } finally {
            restoringPage = false
        }
        if (previous.name == "mode") {
            modePageRecycler?.post {
                (modePageRecycler?.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, -previous.scrollY)
            }
        } else scroll.post { scroll.scrollTo(0, previous.scrollY) }
    }
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    /** Keep the editor card surface while matching the native ripple color used on home cards. */
    private fun rippleCard(view: View) {
        val mask = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(android.graphics.Color.WHITE)
        }
        view.foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(getColor(R.color.cp_ripple_on_light)),
            null, mask)
        view.isClickable = true
        view.isFocusable = true
    }

    /**
     * Cards which navigate or redraw immediately otherwise disappear before a short tap ripple
     * becomes visible. Delay only their click action; Android still owns press, release and
     * long-press feedback, without extra touch listeners or interfering with frame drag.
     */
    private fun rippleClick(view: View, action: () -> Unit) {
        view.setOnClickListener {
            view.postDelayed({
                if (!isFinishing && !isDestroyed) action()
            }, 110L)
        }
    }
    private fun button(label: String, click: () -> Unit) =
        com.google.android.material.button.MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            minHeight = px(42)
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = px(14)
            strokeWidth = px(1)
            strokeColor = android.content.res.ColorStateList.valueOf(
                getColor(R.color.cp_btn_outline_stroke))
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.cp_btn_dark_bg))
            setTextColor(getColor(R.color.cp_btn_outline_text))
            setOnClickListener { click() }
        }


    /** Reuse the actual Material button inflated from the rename XML. */
    private fun dialogButton(label: String, primary: Boolean, click: () -> Unit):
        com.google.android.material.button.MaterialButton {
        val source = layoutInflater.inflate(R.layout.dialog_rename_role, null)
        val id = if (primary) R.id.btn_positive else R.id.btn_negative
        val control = source.findViewById<com.google.android.material.button.MaterialButton>(id)
        (control.parent as android.view.ViewGroup).removeView(control)
        control.text = label
        control.setOnClickListener { click() }
        return control
    }

    private fun actionPair(first: String, firstClick: () -> Unit,
                           second: String, secondClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(first, firstClick),
            LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(5) })
        row.addView(button(second, secondClick),
            LinearLayout.LayoutParams(0, px(44), 1f).apply { leftMargin = px(5) })
        panel.addView(row, LinearLayout.LayoutParams(-1, px(44)).apply {
            topMargin = px(4)
        })
    }

    private fun menuCard(title: String, note: String, iconRes: Int, click: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            rippleCard(this)
            setPadding(px(16), px(10), px(12), px(10))
            isClickable = true
            isFocusable = true
            rippleClick(this) { click() }
        }
        card.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.cp_note))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }, LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(12) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(text(title, 16f).apply { setPadding(0, 0, 0, 0) })
        labels.addView(heading)
        labels.addView(text(note, 12f).apply {
            setPadding(0, px(4), 0, 0)
            setTextColor(getColor(R.color.cp_note))
        })
        card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(text("›", 24f))
        panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(8)
        })
    }
    private fun text(value: String, size: Float = 14f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.cp_title))
        setPadding(px(4), px(8), px(4), px(8))
    }
    private fun section(title: String, dest: String) {
        removeBulkBar()
        // Only the active page owns a vertical scroller. Restore normal page
        // ScrollView before creating ordinary editor pages.
        modePageRecycler?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            modePageRecycler = null
        }
        activeFrameRecycler = null
        scroll.visibility = View.VISIBLE
        scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, 0)
        stopAnimation()
        val pageChanged = screen != dest
        if (!restoringPage && pageChanged)
            pageHistory.addLast(EditorPage(screen, modeId, currentPageScrollY()))
        screen = dest
        panel.removeAllViews()
        titleText.text = title
        findViewById<View>(R.id.tv_mode_more).visibility =
            if (dest == "mode") View.VISIBLE else View.GONE
        subtitleText.text = root.optString("name")
        if (pageChanged && !restoringPage) {
            scroll.clearFocus()
            panel.requestFocus()
            scroll.scrollTo(0, 0)
            scroll.post { if (screen == dest) scroll.scrollTo(0, 0) }
        }
    }
    private fun action(title: String, click: () -> Unit) {
        panel.addView(button(title, click), LinearLayout.LayoutParams(-1, px(46)).apply { topMargin = px(6) })
    }
    private fun attempt(block: () -> Unit) {
        runCatching(block).onFailure {
            Toast.makeText(this, it.message ?: "操作失败", Toast.LENGTH_LONG).show()
        }
    }
    private fun change(silent: Boolean = false, edit: (JSONObject) -> Unit) {
        require(!linkedMode || !linkedReadOnly && linkedApproved) { "请先确认共享素材修改" }
        val candidate = JSONObject(root.toString())
        edit(candidate)
        RolePackEditing.commit(this, id, draft, candidate)
        root = candidate
        draft = RolePackEditing.draft(this, id)
        if (linkedMode) linkedTreeContainer?.let { tree ->
            tree.removeAllViews()
            functionTree(tree, root.getJSONObject("functions"), emptyList())
        }
        if (!silent) Toast.makeText(this,
            if (linkedMode) "素材包已保存，关联角色将使用最新配置" else "已保存",
            Toast.LENGTH_SHORT).show()
    }
    /** Common content shell; inner widgets retain their existing layout and save semantics. */
    private fun editor(title: String, compact: Boolean = false, readOnly: Boolean = false,
                       extraAction: (() -> Unit)? = null, confirmLabel: String = "保存",
                       readOnlyActionLabel: String = "重命名", subtitle: String? = null,
                       renameLayout: Boolean = false,
                       create: (LinearLayout) -> (() -> Unit)): android.app.Dialog {
        val frame = layoutInflater.inflate(R.layout.dialog_content_shell, null)
        if (renameLayout) {
            // Only the four compact rename dialogs use the home-role margins.
            val shell = frame as FrameLayout
            shell.setPadding(px(20), 0, px(20), 0)
            val card = shell.getChildAt(0) as android.view.ViewGroup
            card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
                width = minOf(px(420), resources.displayMetrics.widthPixels - px(40))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            (card.getChildAt(0) as LinearLayout).setPadding(px(20), px(20), px(20), px(20))
            frame.findViewById<LinearLayout>(R.id.dialog_actions).gravity = Gravity.CENTER
            // A single-field rename has no outlined module; match the field label instead.
            frame.findViewById<LinearLayout>(R.id.dialog_heading).setPadding(0, 0, 0, 0)
        }
        frame.findViewById<TextView>(R.id.tv_title).text = title
        frame.findViewById<TextView>(R.id.tv_subtitle).apply {
            if (subtitle.isNullOrBlank()) visibility = View.GONE
            else { visibility = View.VISIBLE; text = subtitle }
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = create(form)
        val viewport = ScrollView(this).apply {
            isFillViewport = false
            addView(form)
        }
        frame.findViewById<FrameLayout>(R.id.dialog_body_slot).addView(
            viewport, FrameLayout.LayoutParams(-1, -2))
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        frame.findViewById<View>(R.id.btn_close_x).setOnClickListener { dialog.dismiss() }
        frame.findViewById<TextView>(R.id.btn_negative).apply {
            text = if (readOnly) "关闭" else "取消"
            setOnClickListener { dialog.dismiss() }
        }
        frame.findViewById<TextView>(R.id.btn_positive).apply {
            if (readOnly && extraAction == null) visibility = View.GONE
            text = if (readOnly) readOnlyActionLabel else confirmLabel
            setOnClickListener {
                if (readOnly) {
                    extraAction?.invoke()
                    return@setOnClickListener
                }
                runCatching(save).onSuccess { dialog.dismiss() }
                    .onFailure { Toast.makeText(this@RolePackEditorActivity,
                        it.message ?: "输入错误", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
        if (readOnly) {
            detailDialogs.add(dialog)
            dialog.setOnDismissListener { detailDialogs.remove(dialog) }
        }
        // Keep title and footer anchored, with only the content scrolling.
        fun resizeForm() {
            if (!dialog.isShowing || viewport.width <= 0) return
            val maxHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
            val contentWidth = viewport.width.coerceAtLeast(1)
            form.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val desired = minOf(form.measuredHeight, maxHeight)
            if (viewport.layoutParams.height != desired) {
                viewport.layoutParams = viewport.layoutParams.apply { height = desired }
                dialog.window?.setLayout(-1, -2)
            }
        }
        form.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            viewport.post { resizeForm() }
        }
        viewport.post { resizeForm() }
        return dialog
    }

    /** Same title / X / outer spacing for pickers; picker logic owns its own footer. */
    private fun pickerShell(dialog: android.app.Dialog, title: String,
                            subtitle: String? = null): Pair<View, LinearLayout> {
        val frame = layoutInflater.inflate(R.layout.dialog_content_shell, null)
        frame.findViewById<TextView>(R.id.tv_title).text = title
        frame.findViewById<TextView>(R.id.tv_subtitle).apply {
            if (subtitle.isNullOrBlank()) visibility = View.GONE
            else { text = subtitle; visibility = View.VISIBLE }
        }
        frame.findViewById<View>(R.id.btn_close_x).setOnClickListener { dialog.dismiss() }
        dialog.setCanceledOnTouchOutside(true)
        frame.findViewById<View>(R.id.dialog_actions).visibility = View.GONE
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.findViewById<FrameLayout>(R.id.dialog_body_slot)
            .addView(body, FrameLayout.LayoutParams(-1, -2))
        return frame to body
    }

    /** Shared visual surface for hierarchical resource/selection rows.
     *  Only styling is shared; each picker keeps its own selection behavior.
     */
    private fun selectionSurface(view: View, isFolder: Boolean = false) {
        view.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(9).toFloat()
            setColor(getColor(if (isFolder) R.color.cp_card_bg else R.color.cp_bg))
            setStroke(px(1).coerceAtLeast(1), getColor(R.color.cp_divider))
        }
        rippleCard(view)
    }

    private fun selectionRowParams(depth: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = px((depth * 12).coerceAtMost(48))
            topMargin = px(4)
            bottomMargin = px(2)
        }

    /** Existing editor-page card styling. Do not change when adjusting dialogs. */
    private fun detailSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(getColor(R.color.cp_card_bg))
            setStroke(px(1), getColor(R.color.cp_divider))
        }
        setPadding(px(10), px(9), px(10), px(9))
    }
    /** Dialog-only module: an outlined surface, with gray independent list rows. */
    private fun dialogSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(getColor(R.color.cp_card_bg))
            setStroke(px(1), getColor(R.color.cp_divider))
        }
        setPadding(px(11), px(9), px(11), px(9))
    }
    private fun sharedNotice(title: String, instanceCount: Int,
                             message: String, locked: Boolean): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(getColor(R.color.cp_bg))
            }
            setPadding(px(11), px(11), px(11), px(10))
            val headline = LinearLayout(this@RolePackEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headline.addView(ImageView(this@RolePackEditorActivity).apply {
                setImageResource(if (locked) R.drawable.ic_dialog_lock else R.drawable.ic_dialog_info)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.cp_sub))
            }, LinearLayout.LayoutParams(px(18), px(18)).apply { rightMargin = px(7) })
            headline.addView(text(title, 13f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            headline.addView(text(instanceCount.toString() + " 个角色实例", 11f).apply {
                setTextColor(getColor(R.color.cp_sub))
            })
            addView(headline)
            addView(text(message, 12f).apply {
                setTextColor(getColor(R.color.cp_sub))
                setPadding(0, px(5), 0, 0)
            })
        }
    private fun detailListRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(8).toFloat()
            setColor(getColor(R.color.cp_bg))
        }
        setPadding(px(8), px(2), px(8), px(2))
    }
    private fun detailSectionHeader(title: String, info: String, click: (() -> Unit)? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(2), 0, px(2))
            addView(text(title, 15f).apply { setPadding(px(2), px(4), px(2), px(4)) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(if (click != null) "$info  ›" else info, 12f).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.cp_note))
                setPadding(px(3), px(4), px(2), px(4))
            })
            if (click != null) rippleClick(this) { click() }
        }
    private fun detailNumber(index: Int): TextView = text("${index + 1}.", 12f).apply {
        setTextColor(getColor(R.color.cp_note))
        setPadding(0, px(3), px(4), px(3))
    }
    private fun addDetailSection(form: LinearLayout, section: LinearLayout) {
        form.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(9) })
    }

    private fun optionsDialog(title: String, items: List<String>, choose: (Int) -> Unit) {
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val (frame, body) = pickerShell(dialog, title)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { index, option ->
            list.addView(text(option, 15f).apply {
                selectionSurface(this)
                setPadding(px(14), px(12), px(14), px(12))
                isClickable = true
                setOnClickListener { dialog.dismiss(); choose(index) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5) })
        }
        body.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1,
                minOf(px(56 * items.size + 10), resources.displayMetrics.heightPixels / 2)))
        body.addView(dialogButton("关闭", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, px(42)).apply { topMargin = px(10) })
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    private fun entry(box: LinearLayout, title: String, initial: String,
                      compact: Boolean = false, placeholder: String = "请输入$title",
                      helper: String? = null): EditText {
        // Use the same permanently notched label in full and compact form dialogs.
        val item = FormInputField(this)
        val field = item.configure(title, initial, placeholder, helper)
        box.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(if (compact) 8 else 12)
        })
        return field
    }
    /** TextInputLayout internally wraps EditText in a FrameLayout. The actual
     * horizontally weighted layout child is the outer FormInputField. */
    private fun formFieldOf(input: EditText): FormInputField {
        var node: android.view.ViewParent? = input.parent
        while (node != null && node !is FormInputField) node = node.parent
        return node as? FormInputField ?: error("Input has no form field container")
    }

    private fun ids(): List<String> = root.getJSONObject("modes").keys().asSequence().toList().sorted()
    private fun home() {
        section("素材包编辑", "home")
        val roleName = root.optString("name").ifBlank { "角色" }
        val report = RolePackNormalizer.report(draft)
        if (report != null) {
            val issues = report.optJSONArray("issues")
            val repaired = (0 until (issues?.length() ?: 0)).count {
                issues?.optJSONObject(it)?.optString("type") == "已补全"
            }
            val unsupported = (issues?.length() ?: 0) - repaired
            val invalid = report.has("parseError")
            if (repaired > 0 || unsupported > 0 || invalid) {
                menuCard("素材包兼容检查 · ${if (invalid) "原配置需要修复" else "$unsupported 项需检查"}",
                    "已补齐 $repaired 项缺失结构；点击查看导入诊断与原始配置",
                    android.R.drawable.ic_dialog_alert) { showPackRepairReport() }
            }
        }
        menuCard("基本信息", "定义了「$roleName」的身份与信息", android.R.drawable.ic_menu_info_details) { info() }
        menuCard("功能映射", "决定了「$roleName」在何时会触发什么动作动画", android.R.drawable.ic_menu_manage) { functions() }
        menuCard("动作动画", "制定了「$roleName」有什么动作动画", android.R.drawable.ic_media_play) { modes() }
        menuCard("图片资源", "列举了「$roleName」所用的图片", android.R.drawable.ic_menu_gallery) { images() }
        menuCard("碎碎念文本", "影响了「$roleName」的思考内容", android.R.drawable.ic_dialog_email) { thinkingResources() }
    }
    /** Report is an import snapshot, not a runtime validity verdict. */
    private fun showPackRepairReport() {
        val report = RolePackNormalizer.report(draft)
        val origin = RolePackNormalizer.original(draft)
        editor("素材包兼容检查", compact = true, readOnly = true,
            subtitle = "原始版本 · ${report?.optString("sourceVersion", "未知") ?: "未知"}",
            readOnlyActionLabel = "编辑原始配置",
            extraAction = {
                detailDialogs.lastOrNull { it.isShowing }?.dismiss()
                editPackSource()
            }) { form ->
            val error = report?.optString("parseError")?.takeIf { it.isNotBlank() }
            if (error != null) form.addView(text("JSONC 解析失败：$error", 13f).apply {
                setTextColor(getColor(R.color.cp_danger))
            })
            val issues = report?.optJSONArray("issues")
            if (issues == null || issues.length() == 0) {
                form.addView(text("没有保存的导入修复记录。", 13f))
            } else for (i in 0 until issues.length()) {
                val entry = issues.optJSONObject(i) ?: continue
                val type = entry.optString("type")
                val path = entry.optString("path")
                val description = entry.optString("detail")
                val card = dialogSection()
                card.addView(text("$type · $path", 13f))
                card.addView(text(description, 11f).apply {
                    setTextColor(getColor(R.color.cp_note))
                })
                val raw = entry.optString("raw")
                if (raw.isNotBlank()) card.addView(text("原值：$raw", 11f).apply {
                    setTextColor(getColor(R.color.cp_sub))
                    setTextIsSelectable(true)
                })
                addDetailSection(form, card)
            }
            form.addView(text(
                if (origin.exists()) "原始配置已保留，点击下方按钮可手动修复 JSONC。"
                else "原始配置没有单独备份；可以查看并编辑当前配置。", 11f
            ).apply { setTextColor(getColor(R.color.cp_note)) })
            ({ })
        }
    }

    /** Syntax recovery is explicit: do not silently accept an unparseable document. */
    private fun editPackSource() {
        val origin = RolePackNormalizer.original(draft)
        val current = File(draft, "modes.jsonc")
        // A malformed import needs its untouched original. Otherwise edit the
        // CURRENT document so subsequent edits are never overwritten by old backups.
        val invalidSource = RolePackNormalizer.report(draft)?.has("parseError") == true
        val initial = (if (invalidSource && origin.exists()) origin else current).readText()
        editor("编辑 JSONC 原始配置", compact = true,
            subtitle = "保存后按当前格式重新解析和补齐") { form ->
            val inputField = FormInputField(this)
            val input = inputField.configure("JSONC 内容", initial, "请输入有效 JSONC",
                helper = "保留已有配置；无法确定含义的旧字段请手动检查。", multiline = true)
            input.minLines = 10
            input.maxLines = 20
            form.addView(inputField, LinearLayout.LayoutParams(-1, -2))
            ({
                val updated = input.text.toString()
                // Fail before overwriting the draft if JSONC remains malformed.
                parseJsonc(updated)
                if (!origin.exists() && current.isFile) origin.writeText(current.readText())
                current.writeText(updated)
                File(draft, RolePackNormalizer.REPORT_FILE).delete()
                val corrected = RolePackEditing.prepareEditable(draft, id,
                    root.optString("name").ifBlank { id })
                RolePackEditing.commit(this, id, draft, corrected)
                root = corrected
                draft = RolePackEditing.draft(this, id)
                home()
                Toast.makeText(this, "配置已重新解析，缺失功能已补齐", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun info() {
        editor("基本信息", compact = true) { box ->
            val name = entry(box, "名称", root.optString("name"), compact = true)
            val short = entry(box, "简称", root.optString("shortName"), compact = true)
            val decode = entry(box, "图片解码尺寸 decodeTarget", root.optInt("decodeTarget").toString(), compact = true)
            val anchor = entry(box, "逻辑尺寸 anchorRes", root.optInt("anchorRes", 128).toString(), compact = true)
            val list = root.getJSONArray("authors")
            val author = entry(box, "作者（用逗号分隔）",
                (0 until list.length()).joinToString(",") { list.getString(it) }, compact = true)
            val save: () -> Unit = {
                change { json ->
                    json.put("name", name.text.toString().trim())
                    json.put("shortName", short.text.toString().trim())
                    json.put("decodeTarget", decode.text.toString().toInt())
                    json.put("anchorRes", anchor.text.toString().toInt())
                    json.put("authors", JSONArray().also { arr ->
                        author.text.toString().split(",", "，").map { it.trim() }
                            .filter { it.isNotEmpty() }.forEach { arr.put(it) }
                    })
                }
                home()
            }
            save
        }
    }
    /** Sampled thumbnails keep the action list small even for high-resolution artwork. */
    private fun thumb(file: File, size: Int = 112): android.graphics.Bitmap? {
        if (!file.isFile) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > size * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        })
    }

    private val expandedModeGroups = mutableSetOf<String>()
    private fun modeGroups(key: String): List<String> {
        val groups = root.getJSONObject("modes").getJSONObject(key).optJSONArray("groups") ?: return emptyList()
        return (0 until groups.length()).map { groups.optString(it) }.filter { it.isNotBlank() }.distinct()
    }
    private fun knownGroups(): List<String> = ids().flatMap { modeGroups(it) }.distinct()

    private fun modes() {
        section("动作动画", "modes")
        panel.addView(text("管理角色的动作动画与播放规则。\n点击“+”创建动画，点击卡片编辑。", 12f).apply {
            setPadding(px(4), 0, px(4), px(4))
            setTextColor(getColor(R.color.cp_note))
        })
        action("＋ 新建动画") { newMode() }
        val grouped = linkedMapOf<String, MutableList<String>>()
        grouped["全部"] = ids().toMutableList()
        knownGroups().filter { it != "全部" }.forEach { grouped[it] = mutableListOf() }
        ids().forEach { key ->
            val groups = modeGroups(key)
            if (groups.isEmpty()) grouped.getOrPut("未分组") { mutableListOf() }.add(key)
            else groups.forEach { grouped.getOrPut(it) { mutableListOf() }.add(key) }
        }
        val thumbnails = mutableMapOf<String, android.graphics.Bitmap?>()
        for ((group, names) in grouped) {
            val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                rippleCard(this)
                setPadding(px(12), px(6), px(12), px(6))
            }
            val heading = text("", 16f)
            header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            if (group != "全部") header.addView(text("+", 23f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { newMode(group.takeUnless { it == "未分组" }) }
            }, LinearLayout.LayoutParams(px(38), px(44)))
            if (group != "未分组" && group != "全部") header.addView(text("⋮", 23f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { anchor -> showModeGroupOptions(anchor, group) }
            }, LinearLayout.LayoutParams(px(38), px(44)))
            fun fillChildren() {
            for (key in names) {
                val mode = root.getJSONObject("modes").getJSONObject(key)
                val frames = mode.getJSONArray("frames")
                val source = frames.optJSONObject(0)?.optString("res").orEmpty()
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    rippleCard(this)
                    setPadding(px(8), px(5), px(4), px(5))
                }
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = false
                }
                content.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundResource(R.drawable.bg_thumb_border)
                    val transform = frames.optJSONObject(0)?.optString("transform", "none") ?: "none"
                    setImageBitmap(thumbnails.getOrPut(source + "|" + transform) {
                        thumb(File(draft, source))?.let { transformedThumb(it, transform) }
                    })
                }, LinearLayout.LayoutParams(px(54), px(54)))
                val label = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(8), 0, 0, 0)
                }
                val headline = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                headline.addView(text(key, 16f).apply { setPadding(0, 0, 0, 0) })
                if (playbackKind(mode) != "infinite") {
                    val nextMode = mode.optString("nextMode", "").takeUnless { it == "null" }.orEmpty()
                    headline.addView(text("  →" + nextMode.ifBlank { "无" }, 11f).apply {
                        setTextColor(getColor(R.color.cp_note))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                label.addView(headline)
                val duration = mode.optJSONObject("duration")
                val playback = when (playbackKind(mode)) {
                    "off" -> "播放一次"
                    "infinite" -> "无限循环"
                    else -> "循环（${duration?.optLong("min", 3000) ?: 3000} ms-${duration?.optLong("max", 4000) ?: 4000} ms）"
                }
                label.addView(text("${frames.length()} 帧 · $playback", 12f).apply {
                    setPadding(0, 0, 0, 0)
                    setTextColor(getColor(R.color.cp_note))
                })
                content.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                rippleClick(card) { modeDetails(key) }
                card.addView(text("⋮", 24f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { anchor -> showModeOptions(anchor, key) }
                }, LinearLayout.LayoutParams(px(42), px(54)))
                children.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(14)
                    topMargin = px(4)
                })
            }
            }
            fun refreshHeader() {
                val opened = group in expandedModeGroups
                heading.text = (if (opened) "▾ 📂  " else "▸ 📁  ") + group +
                    "  (${names.size})"
                if (opened && children.childCount == 0) fillChildren()
                children.visibility = if (opened) View.VISIBLE else View.GONE
            }
            header.setOnClickListener {
                if (!expandedModeGroups.add(group)) expandedModeGroups.remove(group)
                refreshHeader()
            }
            panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            panel.addView(children, LinearLayout.LayoutParams(-1, -2))
            refreshHeader()
        }
    }

    private fun showModeOptions(anchor: View, key: String) {
        UnifiedOptionMenu.show(anchor, listOf("详情 / 编辑", "复制动画", "动画组", "删除动画"),
            setOf("删除动画")) { choice ->
            when (choice) {
                "详情 / 编辑" -> modeDetails(key)
                "复制动画" -> copyMode(key)
                "动画组" -> editModeGroups(key)
                "删除动画" -> deleteMode(key)
            }
        }
    }

    private fun editModeGroups(key: String, onSaved: () -> Unit = { modes() }) {
        editor("$key · 动画组") { box ->
            val groups = knownGroups().toMutableList()
            val selected = modeGroups(key).toMutableSet()
            val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun redraw() {
                choices.removeAllViews()
                groups.forEach { group ->
                    choices.addView(CheckBox(this).apply {
                        text = group
                        isChecked = group in selected
                        setOnCheckedChangeListener { _, value ->
                            if (value) selected.add(group) else selected.remove(group)
                        }
                    })
                }
            }
            box.addView(choices)
            redraw()
            // A floating outline label replaces the separate heading.
            val addRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val inputField = FormInputField(this)
            val input = inputField.configure("新组名称", placeholder = "输入新组名称")
            addRow.addView(inputField, LinearLayout.LayoutParams(0, -2, 1f))
            addRow.addView(button("√") {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 40 || name == "未分组" || name == "全部" ||
                    name.any { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
                    input.error = "动画组名称不合法"
                } else {
                    if (name !in groups) groups.add(name)
                    selected.add(name)
                    input.setText("")
                    redraw()
                }
            }, LinearLayout.LayoutParams(px(48), px(42)).apply { leftMargin = px(8) })
            box.addView(addRow)
            val save: () -> Unit = {
                require(input.text.toString().isBlank()) { "请先点击添加新组" }
                change { json ->
                    val arr = JSONArray()
                    groups.filter { it in selected }.forEach { arr.put(it) }
                    json.getJSONObject("modes").getJSONObject(key).put("groups", arr)
                }
                onSaved()
            }
            save
        }
    }

    private fun showModeGroupOptions(anchor: View, group: String) {
        UnifiedOptionMenu.show(anchor, listOf("重命名", "解散"), setOf("解散")) { choice ->
            when (choice) {
                "重命名" -> editor("重命名动画组", compact = true,
                    subtitle = "当前分组 · $group", renameLayout = true) { box ->
                    val name = entry(box, "动画组名称", group, compact = true)
                    val save: () -> Unit = {
                        val target = name.text.toString().trim()
                        require(target.isNotEmpty() && target.length <= 40 && target != "未分组" &&
                            target.none { ch -> ch == '/' || ch == '\\' || ch == '\n' || ch == '\r' }) {
                            "动画组名称不合法"
                        }
                        require(target == group || target !in knownGroups()) { "动画组已存在" }
                        if (target != group) {
                            change { json ->
                                val all = json.getJSONObject("modes")
                                all.keys().forEach { id ->
                                    val mode = all.getJSONObject(id)
                                    val groups = mode.optJSONArray("groups") ?: return@forEach
                                    for (i in 0 until groups.length()) {
                                        if (groups.optString(i) == group) groups.put(i, target)
                                    }
                                }
                            }
                            if (expandedModeGroups.remove(group)) expandedModeGroups.add(target)
                        }
                        modes()
                    }
                    save
                }
                "解散" -> showConfirmDialog(
                    title = "解散动画组",
                    bodyHtml = "确认解散「$group」？组内动画不会删除，仍属于其他组的动画保持不变。",
                    positiveText = "解散"
                ) {
                    attempt {
                        change { json ->
                            val all = json.getJSONObject("modes")
                            all.keys().forEach { id ->
                                val mode = all.getJSONObject(id)
                                val old = mode.optJSONArray("groups") ?: return@forEach
                                val kept = JSONArray()
                                for (i in 0 until old.length()) {
                                    if (old.optString(i) != group) kept.put(old.optString(i))
                                }
                                mode.put("groups", kept)
                            }
                        }
                        expandedModeGroups.remove(group)
                        modes()
                    }
                }
            }
        }
    }

    private fun newMode(group: String? = null) {
        editor("新建动画") { box ->
            val key = entry(box, "动画名称", "")
            val save: () -> Unit = {
                val name = key.text.toString().trim()
                validId(name)
                require(!root.getJSONObject("modes").has(name)) { "动画名称已存在" }
                val source = ids().firstNotNullOfOrNull { mode ->
                    root.getJSONObject("modes").optJSONObject(mode)
                        ?.optJSONArray("frames")?.optJSONObject(0)?.optString("res")
                        ?.takeIf { it.isNotBlank() }
                }.orEmpty()
                change { json ->
                    json.getJSONObject("modes").put(name, JSONObject()
                        .put("loop", false)
                        .put("loopMode", "off")
                        .put("nextMode", JSONObject.NULL)
                        .put("duration", JSONObject.NULL).put("finishCycle", true)
                        .put("groups", JSONArray().apply { if (group != null) put(group) })
                        .put("frames", JSONArray().put(JSONObject().put("res", source).put("delay", 120))))
                }
                showMode(name)
            }
            save
        }
    }

    /** Count Functions leaves containing the animation, reading the canonical map. */
    private fun modeBindingCount(modeKey: String): Int {
        var count = 0
        fun visit(node: JSONObject) {
            node.keys().forEach { key ->
                when (val value = node.opt(key)) {
                    is JSONArray -> if ((0 until value.length()).any { value.optString(it) == modeKey }) count++
                    is JSONObject -> if (value.has("modes")) {
                        val choices = value.optJSONArray("modes")
                        if (choices != null && (0 until choices.length()).any { choices.optString(it) == modeKey }) count++
                    } else if (key != "thinking") visit(value)
                }
            }
        }
        visit(root.getJSONObject("functions"))
        return count
    }

    private fun modeThinkingSummary(mode: JSONObject): String {
        val thinking = mode.optJSONObject("thinking")
        return when (thinking?.optString("policy", "inherit") ?: "inherit") {
            "off" -> "不播放"
            "hold" -> "持续 · ${thinkingPoolJson(thinking).size} 条"
            "cycle" -> "轮播 · ${thinkingPoolJson(thinking).size} 条"
            else -> "跟随功能"
        }
    }

    /** Lightweight overview; child editors commit independently. */
    private fun modeDetails(initialKey: String) {
        if (linkedMode && linkedReadOnly) { linkedReadonlyMode(initialKey); return }
        if (root.optJSONObject("modes")?.has(initialKey) != true) return
        var currentKey = initialKey
        lateinit var redraw: () -> Unit
        val dialog = editor(initialKey, compact = true, readOnly = true,
            readOnlyActionLabel = "编辑动画 · ${root.getJSONObject("modes").getJSONObject(initialKey).getJSONArray("frames").length()}帧",
            extraAction = { openModeEditorFromDetail(currentKey) }) { form ->
            redraw = {
                form.removeAllViews()
                val mode = root.optJSONObject("modes")?.optJSONObject(currentKey)
                if (mode != null) {
                    val loop = playbackKind(mode)
                    val duration = mode.optJSONObject("duration")
                    val summary = when (loop) {
                        "timed" -> "定时循环 · ${duration?.optLong("min", 3000) ?: 3000}–${duration?.optLong("max", 4000) ?: 4000}ms"
                        "infinite" -> "无限循环"
                        else -> "播放一次"
                    }
                    val playbackCard = dialogSection()
                    playbackCard.addView(detailSectionHeader("播放循环", summary) {
                        linkedConsent { playbackSettings(currentKey) { redraw() } }
                    }.apply {
                        setPadding(0, px(8), 0, px(8))
                    })
                    if (loop != "infinite") {
                        playbackCard.addView(View(this).apply {
                            setBackgroundColor(getColor(R.color.cp_divider))
                        }, LinearLayout.LayoutParams(-1, px(1)).apply {
                            leftMargin = px(4)
                            rightMargin = px(4)
                        })
                        val next = mode.optString("nextMode").takeUnless { it == "null" }.orEmpty()
                        playbackCard.addView(detailSectionHeader("后续动画",
                            next.ifBlank { "自动（由系统决定）" }) {
                            linkedConsent { chooseModes(single = true,
                                initial = listOfNotNull(next.takeIf { it.isNotEmpty() }),
                                headerDescription = "当前动画 · $currentKey") { selected ->
                                val nextId = selected.firstOrNull().orEmpty()
                                change { json ->
                                    json.getJSONObject("modes").getJSONObject(currentKey).put("nextMode",
                                        if (nextId.isEmpty()) JSONObject.NULL else nextId)
                                }
                                redraw()
                            } }
                        }.apply {
                            setPadding(0, px(8), 0, px(8))
                        })
                    }
                    addDetailSection(form, playbackCard)
                    val functionCard = dialogSection()
                    functionCard.addView(detailSectionHeader("绑定功能", "${modeBindingCount(currentKey)} 个") {
                        linkedConsent { bindModeToFunctions(currentKey) { redraw() } }
                    })
                    addDetailSection(form, functionCard)
                    val thinkingCard = dialogSection()
                    thinkingCard.addView(detailSectionHeader("碎碎念配置", modeThinkingSummary(mode)) {
                        linkedConsent { thinkingConfigDialog(modeId = currentKey) { redraw() } }
                    })
                    addDetailSection(form, thinkingCard)
                }
            }
            redraw()
            ({ })
        }
        val heading = dialog.findViewById<TextView>(R.id.tv_title)
        fun refreshHeading() {
            val label = android.text.SpannableString("$currentKey  ✎")
            label.setSpan(android.text.style.UnderlineSpan(), 0, currentKey.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            heading.text = label
            heading.setOnClickListener {
                linkedConsent { renameMode(currentKey) { renamed ->
                    currentKey = renamed
                    refreshHeading()
                    redraw()
                    dialog.findViewById<TextView>(R.id.btn_positive).text =
                        "编辑动画 · ${root.getJSONObject("modes").getJSONObject(renamed).getJSONArray("frames").length()}帧"
                    if (screen == "modes") modes()
                } }
            }
        }
        refreshHeading()
    }

    private fun renameMode(key: String, onRenamed: (String) -> Unit) {
        editor("重命名动画", compact = true, subtitle = "当前动画 · $key",
            renameLayout = true) { form ->
            val name = entry(form, "动画名称", key, compact = true)
            ({
                val renamed = name.text.toString().trim()
                validId(renamed)
                require(renamed == key || !root.getJSONObject("modes").has(renamed)) { "动画名称已存在" }
                if (renamed != key) change { json ->
                    val modes = json.getJSONObject("modes")
                    val item = modes.getJSONObject(key)
                    modes.remove(key)
                    modes.put(renamed, item)
                    modes.keys().forEach { other ->
                        if (modes.getJSONObject(other).optString("nextMode") == key)
                            modes.getJSONObject(other).put("nextMode", renamed)
                    }
                    substitute(json.getJSONObject("functions"), key, renamed)
                }
                onRenamed(renamed)
            })
        }
    }

    /** Playback-only entry, retaining the former attributes() loop semantics. */
    private fun playbackSettings(key: String, onSaved: () -> Unit) {
        val current = root.getJSONObject("modes").getJSONObject(key)
        editor("播放循环", compact = true, subtitle = "动画 · $key") { form ->
            val settingsCard = dialogSection()
            val options = listOf("播放一次", "定时循环", "无限循环")
            val spinner = Spinner(this)
            SettingsSpinnerBinder.bind(spinner, options,
                when (playbackKind(current)) { "timed" -> 1; "infinite" -> 2; else -> 0 }) { }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text("播放循环", 14f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(spinner, LinearLayout.LayoutParams(0, px(48), 1f))
            settingsCard.addView(row)
            val timedFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val timerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val previous = current.optJSONObject("duration")
            val min = entry(timerRow, "最短时长（ms）",
                (previous?.optLong("min", 3000) ?: 3000).toString(), compact = true)
            val max = entry(timerRow, "最长时长（ms）",
                (previous?.optLong("max", 4000) ?: 4000).toString(), compact = true)
            formFieldOf(min).layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) }
            formFieldOf(max).layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) }
            timedFields.addView(timerRow)
            val finish = Switch(this).apply {
                text = "结束前播完当前一轮"
                isChecked = current.optBoolean("finishCycle", true)
            }
            timedFields.addView(finish)
            // Keep the expanded timed-loop controls visually grouped with their dropdown.
            timedFields.setPadding(px(8), px(10), px(8), px(12))
            settingsCard.addView(timedFields)

            // Keep nextMode and loop settings in a single playback dialog.
            // Infinite looping hides this field but must not erase its stored value.
            var nextModeId = current.optString("nextMode").takeUnless { it == "null" }.orEmpty()
            val nextRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(6), 0, px(4))
            }
            nextRow.addView(text("后续动画", 14f), LinearLayout.LayoutParams(0, -2, 1f))
            val nextChoice = text("", 13f).apply {
                setBackgroundResource(R.drawable.bg_input)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(px(9), 0, px(9), 0)
            }
            fun redrawNext() {
                nextChoice.text = if (nextModeId.isEmpty()) "自动（由系统决定）  ▾" else "$nextModeId  ▾"
            }
            redrawNext()
            nextChoice.setOnClickListener {
                chooseModes(single = true,
                    initial = listOfNotNull(nextModeId.takeIf { it.isNotEmpty() }),
                    headerDescription = "当前动画 · $key") { selected ->
                    nextModeId = selected.firstOrNull().orEmpty()
                    redrawNext()
                }
            }
            nextRow.addView(nextChoice, LinearLayout.LayoutParams(0, px(44), 1f))
            val nextDivider = View(this).apply {
                setBackgroundColor(getColor(R.color.cp_divider))
            }
            settingsCard.addView(nextDivider, LinearLayout.LayoutParams(-1, px(1)).apply {
                topMargin = px(9)
                bottomMargin = px(7)
            })
            settingsCard.addView(nextRow)
            addDetailSection(form, settingsCard)
            fun refreshFields() {
                timedFields.visibility = if (spinner.selectedItemPosition == 1) View.VISIBLE else View.GONE
                nextRow.visibility = if (spinner.selectedItemPosition == 2) View.GONE else View.VISIBLE
                nextDivider.visibility = nextRow.visibility
            }
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                    position: Int, id: Long) { refreshFields() }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            refreshFields()
            ({
                val kind = when (spinner.selectedItemPosition) {
                    1 -> "timed"
                    2 -> "infinite"
                    else -> "off"
                }
                val newDuration = if (kind == "timed") {
                    val lo = min.text.toString().toLong()
                    val hi = max.text.toString().toLong()
                    require(lo > 0 && hi >= lo) { "时长必须为正数，最长不得小于最短" }
                    JSONObject().put("min", lo).put("max", hi)
                } else null
                change { json ->
                    val mode = json.getJSONObject("modes").getJSONObject(key)
                    mode.put("loopMode", kind)
                    mode.put("loop", kind != "off")
                    mode.put("nextMode", if (nextModeId.isEmpty()) JSONObject.NULL else nextModeId)
                    if (newDuration != null) {
                        mode.put("duration", newDuration)
                        mode.put("finishCycle", finish.isChecked)
                    }
                }
                onSaved()
            })
        }
    }

    private fun showMode(key: String) {
        modeId = key
        bulkSelected.clear()
        section("动画·$key", "mode")
        val m = root.getJSONObject("modes").getJSONObject(key)

        val preview = RoleAnimationPreview(this)
        val previewContainer = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -2))
            addView(TextView(this@RolePackEditorActivity).apply {
                text = "↺"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.cp_title))
                setPadding(0, 0, 0, 0)
                contentDescription = "从头播放"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xDDFFFFFF.toInt())
                }
                elevation = px(3).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    animate(preview, key)
                }
            }, FrameLayout.LayoutParams(px(36), px(36), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = px(8)
                bottomMargin = px(8)
            })
        }
        panel.addView(previewContainer, LinearLayout.LayoutParams(-1, -2))
        animate(preview, key)
        // Mode-level controls remain visible, but don't dominate the frame editor.
        val mode = root.getJSONObject("modes").getJSONObject(key)
        val playbackCard = detailSection()
        val playbackSummary = when (playbackKind(mode)) {
            "infinite" -> "无限循环"
            "timed" -> "定时循环"
            else -> "播放一次"
        }
        playbackCard.addView(detailSectionHeader("播放循环", playbackSummary) {
            playbackSettings(key) { showMode(key) }
        })
        panel.addView(playbackCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })

        val associations = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun compactSetting(title: String, summary: String, click: () -> Unit): LinearLayout =
            detailSection().apply {
                val titleRow = LinearLayout(this@RolePackEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleRow.addView(text(title, 14f).apply {
                    setPadding(0, 0, 0, 0)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                titleRow.addView(text("›", 19f).apply {
                    setPadding(0, 0, 0, 0)
                    setTextColor(getColor(R.color.cp_note))
                })
                addView(titleRow)
                addView(text(summary, 11f).apply {
                    setPadding(0, px(3), 0, 0)
                    setTextColor(getColor(R.color.cp_note))
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
                rippleClick(this) { click() }
            }
        associations.addView(compactSetting("绑定功能", "已绑定 ${modeBindingCount(key)} 个") {
            bindModeToFunctions(key) { showMode(key) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) })
        associations.addView(compactSetting("碎碎念配置", modeThinkingSummary(mode)) {
            thinkingConfigDialog(modeId = key) { showMode(key) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) })
        panel.addView(associations, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(9)
            bottomMargin = px(8)
        })
        val frames = m.getJSONArray("frames")
        val frameHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Give the frame-section heading a little breathing room without
            // changing the RecyclerView surface or frame-card geometry.
            setPadding(px(4), px(4), px(4), px(4))
        }
        frameHeader.addView(text("逐帧编辑 · ${frames.length()} 帧", 18f),
            LinearLayout.LayoutParams(0, -2, 1f))
        frameHeader.addView(text("支持长按拖动排序", 11f).apply {
            setTextColor(getColor(R.color.cp_note))
        })
        // Frame title/actions and frame rows share one surface, drawn by the RecyclerView decoration.
        val frameSection = detailSection().apply { background = null }
        frameSection.addView(frameHeader, LinearLayout.LayoutParams(-1, -2))
        val frameActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frameActions.addView(button("＋ 添加帧") {
            val last = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").length() - 1
            showImageChooser(ImagePickRequest(key, "after", last))
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(5) })
        frameActions.addView(button("GIF 覆盖") {
            showConfirmDialog(
                title = "GIF 覆盖动画",
                bodyHtml = "使用 GIF 全部帧替换此动画现有帧？",
                subText = "原有帧属性会被替换；GIF 时间延迟会被保留",
                positiveText = "继续"
            ) { startUpload(true, action = "overwrite", mode = key) }
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { leftMargin = px(5) })
        frameSection.addView(frameActions, LinearLayout.LayoutParams(-1, px(44)).apply {
            topMargin = px(8)
            bottomMargin = px(5)
        })
        panel.addView(frameSection, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(9)
            bottomMargin = 0
        })
        val order = (0 until frames.length()).toMutableList()
        val thumbs = mutableMapOf<String, android.graphics.Bitmap?>()
        // The page's ONE RecyclerView scrolls both header controls and all
        // frames. No nested scrolling or wrap-content RecyclerView measurement.
        val pageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // RecyclerView already provides the shared 16dp horizontal gutter.
            // Padding this header again made playback/association cards 16dp
            // narrower on each side than the frame-section surface.
            setPadding(0, px(16), 0, 0)
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        while (panel.childCount > 0) {
            val child = panel.getChildAt(0)
            panel.removeViewAt(0)
            pageHeader.addView(child)
        }
        val recycler = RecyclerView(this).apply {
            id = android.R.id.list
            layoutManager = LinearLayoutManager(this@RolePackEditorActivity)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(px(16), 0, px(16), px(120))
        }
        activeFrameRecycler = recycler
        // Draw ONE section surface behind the frame toolbar AND frame rows.
        // The list stays a single RecyclerView; no nested scrolling or card-width hacks.
        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val surface = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(getColor(R.color.cp_card_bg))
                setStroke(px(1), getColor(R.color.cp_divider))
            }
            override fun onDraw(canvas: android.graphics.Canvas, parent: RecyclerView,
                                state: RecyclerView.State) {
                var top = Int.MAX_VALUE
                var bottom = Int.MIN_VALUE
                var headerVisible = false
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (parent.getChildAdapterPosition(child) == 0) {
                        // Include only the frame toolbar, not the preview and other cards.
                        top = minOf(top, child.top + frameSection.top + px(16))
                        headerVisible = true
                    } else if (parent.getChildAdapterPosition(child) > 0) {
                        top = minOf(top, child.top)
                        bottom = maxOf(bottom, child.bottom)
                    }
                }
                if (top == Int.MAX_VALUE) return
                if (bottom == Int.MIN_VALUE) {
                    val header = parent.getChildAt(0)
                    if (!headerVisible || parent.getChildAdapterPosition(header) != 0) return
                    bottom = header.bottom
                }
                surface.setBounds(px(16), maxOf(0, top - px(2)),
                    parent.width - px(16), minOf(parent.height, bottom + px(8)))
                surface.draw(canvas)
            }
        })
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = order.size + 1
            override fun getItemViewType(position: Int): Int =
                if (position == 0) 1 else 0

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                if (viewType == 1) return object : RecyclerView.ViewHolder(pageHeader) {}
                val card = LinearLayout(this@RolePackEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    rippleCard(this)
                    setPadding(px(8), px(3), px(8), px(3))
                    minimumHeight = px(64)
                    layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                        topMargin = px(3)
                        bottomMargin = px(3)
                    }
                }
                card.addView(ImageView(this@RolePackEditorActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundResource(R.drawable.bg_thumb_border)
                }, LinearLayout.LayoutParams(px(52), px(52)))
                val labels = LinearLayout(this@RolePackEditorActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(8), 0, 0, 0)
                }
                val headline = LinearLayout(this@RolePackEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                headline.addView(text("", 15f).apply {
                    setPadding(px(4), px(2), px(4), px(2))
                })
                headline.addView(text("", 8f).apply {
                    setPadding(px(4), 0, px(4), 0)
                    setTextColor(getColor(R.color.cp_note))
                    setSingleLine(false)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    leftMargin = px(8)
                })
                (headline.getChildAt(0) as TextView).includeFontPadding = false
                (headline.getChildAt(1) as TextView).includeFontPadding = false
                labels.addView(headline)
                labels.addView(text("", 8f).apply {
                    setPadding(px(4), 0, px(4), 0)
                    includeFontPadding = false
                    setTextColor(getColor(R.color.cp_note))
                    setSingleLine(false)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                })
                labels.addView(text("", 8f).apply {
                    setPadding(px(4), 0, px(4), 0)
                    includeFontPadding = false
                    setTextColor(getColor(R.color.cp_note))
                    setSingleLine(false)
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                })
                card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(text("⋮", 24f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                }, LinearLayout.LayoutParams(px(40), -1))
                val rootRow = LinearLayout(this@RolePackEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(72)
                    layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                        topMargin = px(3)
                        bottomMargin = px(3)
                    }
                }
                rootRow.addView(CheckBox(this@RolePackEditorActivity),
                    LinearLayout.LayoutParams(px(43), px(54)))
                rootRow.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
                return object : RecyclerView.ViewHolder(rootRow) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (position == 0) return
                val frameIndex = position - 1
                val frame = frames.getJSONObject(order[frameIndex])
                val source = frame.optString("res")
                val rootRow = holder.itemView as LinearLayout
                val select = rootRow.getChildAt(0) as CheckBox
                val card = rootRow.getChildAt(1) as LinearLayout
                val image = card.getChildAt(0) as ImageView
                val labels = card.getChildAt(1) as LinearLayout
                val headline = labels.getChildAt(0) as LinearLayout
                (headline.getChildAt(0) as TextView).text = "帧 $frameIndex"
                val rawDelay = frame.opt("delay")
                val delayText = when (rawDelay) {
                    is JSONArray -> "${rawDelay.optLong(0)}-${rawDelay.optLong(1)}ms"
                    is Number -> "${rawDelay.toLong()}ms"
                    else -> "${rawDelay}ms"
                }
                val frameInfo = headline.getChildAt(1) as TextView

                // Display only configured coordinates; an explicit zero is not a default.
                fun coord(name: String): String = if (frame.has(name) && !frame.isNull(name)) {
                    val value = frame.opt(name)
                    if (value is Number) {
                        val number = value.toDouble()
                        if (number.isFinite() && number % 1.0 == 0.0) number.toLong().toString()
                        else value.toString()
                    } else value.toString()
                } else "-"
                val x = coord("anchorX")
                val y = coord("anchorY")
                val dx = coord("dx")
                val dy = coord("dy")
                val selectedTransform = frame.optString("transform", "none")
                val transformLabel = frameTransforms.firstOrNull { it.second == selectedTransform }?.first
                    .orEmpty().takeUnless { it == "关闭" }.orEmpty()
                frameInfo.text = delayText + if (transformLabel.isBlank()) "" else "  $transformLabel"
                val coords = mutableListOf<String>()
                if (x != "-" || y != "-") coords.add("XY($x,$y)")
                if (dx != "-" || dy != "-") coords.add("dxy($dx,$dy)")
                (labels.getChildAt(1) as TextView).apply {
                    text = coords.joinToString("  ")
                    visibility = if (coords.isEmpty()) View.GONE else View.VISIBLE
                }
                (labels.getChildAt(2) as TextView).text = source
                val transform = frame.optString("transform", "none")
                image.setImageBitmap(thumbs.getOrPut(source + "|" + transform) {
                    thumb(File(draft, source))?.let { transformedThumb(it, transform) }
                })
                image.scaleX = 1f
                val at = holder.bindingAdapterPosition - 1
                select.setOnCheckedChangeListener(null)
                select.isChecked = at in bulkSelected
                select.setOnCheckedChangeListener { _, checked ->
                    if (syncingBulkSelection) return@setOnCheckedChangeListener
                    val index = holder.bindingAdapterPosition - 1
                    if (index in order.indices) {
                        if (checked) bulkSelected.add(index) else bulkSelected.remove(index)
                        refreshVisibleFrameSelection(recycler)
                        updateBulkBar(key)
                    }
                }
                val more = card.getChildAt(2) as TextView
                more.visibility = if (bulkSelected.isEmpty()) View.VISIBLE else View.INVISIBLE
                more.setOnClickListener {
                    val index = holder.bindingAdapterPosition - 1
                    if (index in order.indices && bulkSelected.isEmpty())
                        showFrameOptions(more, key, index)
                }
                rippleClick(card) {
                    val index = holder.bindingAdapterPosition - 1
                    if (index in order.indices) {
                        if (bulkSelected.isEmpty()) showFrameOptions(more, key, index)
                        else {
                            // Reuse checkbox handling, avoiding a full list rebind.
                            select.isChecked = !select.isChecked
                        }
                    }
                }
            }
        }
        recycler.adapter = adapter
        var changed = false
        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = bulkSelected.isEmpty()
            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                if (holder.bindingAdapterPosition <= 0 || bulkSelected.isNotEmpty()) 0
                else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition - 1
                val b = target.bindingAdapterPosition - 1
                if (a !in order.indices || b !in order.indices) return false
                order.add(b, order.removeAt(a))
                adapter.notifyItemMoved(a + 1, b + 1)
                changed = true
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                if (changed) {
                    changed = false
                    attempt {
                        change {
                            it.getJSONObject("modes").getJSONObject(key)
                                .put("frames", remapFrames(frames, order))
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        })
        touch.attachToRecyclerView(recycler)
        // The header lives inside the same RecyclerView as frames. Hiding the
        // obsolete ScrollView prevents it from intercepting any frame gestures.
        val rootLayout = scroll.parent as android.view.ViewGroup
        val scrollPosition = rootLayout.indexOfChild(scroll)
        rootLayout.addView(recycler, scrollPosition + 1,
            LinearLayout.LayoutParams(-1, 0, 1f))
        scroll.visibility = View.GONE
        modePageRecycler = recycler
    }
    private fun animate(view: RoleAnimationPreview, key: String) {
        stopAnimation()
        val manager = ImageModeManager(this, loadPackMeta(this, id, RoleSource.FILES))
        val clip = manager.createClip(key) ?: return
        val tick = object : Runnable {
            override fun run() {
                if (screen != "mode" || modeId != key) return
                view.update(clip.currentBitmap(), manager.previewContentBounds(clip.currentResName, clip.currentVerticalAlign, clip.currentTransform),
                    clip.currentAnchor(), clip.currentDx, clip.currentDy, manager.currentPack.anchorRes,
                    clip.currentModeId, manager.currentPack.modeLoops[clip.currentModeId] == true,
                    clip.currentFrameIndex, false)
                val step = clip.advance(50, allowModeSwitch = true)
                if (!step.ended && !step.timerRequested) handler.postDelayed(this, 50)
            }
        }
        animation = tick
        tick.run()
    }
    private fun stopAnimation() {
        animation?.let { handler.removeCallbacks(it) }
        animation = null
    }
    /** The active loop policy is independent of stored inactive timer settings. */
    private fun playbackKind(mode: JSONObject): String {
        val explicit = mode.optString("loopMode")
        if (explicit in listOf("off", "timed", "infinite")) return explicit
        return when {
            !mode.optBoolean("loop", false) -> "off"
            mode.optJSONObject("duration") != null -> "timed"
            else -> "infinite"
        }
    }

    private fun attributes(key: String) {
        val mode = root.getJSONObject("modes").getJSONObject(key)
        editor("动画属性") { box ->
            val name = entry(box, "动画名称", key)
            (name.parent as? LinearLayout)?.let { field ->
                (field.getChildAt(0) as? TextView)?.setTextColor(android.graphics.Color.BLACK)
            }
            val playbackModes = listOf("播放一次", "定时循环", "无限循环")
            val previous = mode.optJSONObject("duration")
            val playback = Spinner(this)
            val initialPosition = when (playbackKind(mode)) {
                "timed" -> 1
                "infinite" -> 2
                else -> 0
            }
            SettingsSpinnerBinder.bind(playback, playbackModes, initialPosition) { }
            val playbackRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(12), 0, px(8))
            }
            playbackRow.addView(text("播放循环", 14f).apply {
                setTextColor(android.graphics.Color.BLACK)
                setPadding(0, 0, px(12), 0)
            })
            playbackRow.addView(playback, LinearLayout.LayoutParams(0, px(48), 1f))
            box.addView(playbackRow, LinearLayout.LayoutParams(-1, -2))
            val timedFields = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val rangeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val min = entry(rangeRow, "最短时长（ms）",
                previous?.optLong("min", 3000)?.toString() ?: "3000", compact = true)
            val max = entry(rangeRow, "最长时长（ms）",
                previous?.optLong("max", 4000)?.toString() ?: "4000", compact = true)
            (min.parent as? View)?.layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) }
            (max.parent as? View)?.layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) }
            timedFields.addView(rangeRow)
            val finish = Switch(this).apply {
                text = "结束前播完当前一轮"
                isChecked = mode.optBoolean("finishCycle", true)
            }
            timedFields.addView(finish)
            timedFields.setPadding(px(12), 0, 0, px(5))
            box.addView(timedFields)

            // This is a subordinate setting of loop playback: hidden for infinite
            // playback, but its stored value is never erased by changing the mode.
            var nextModeId = mode.optString("nextMode").takeUnless { it == "null" }.orEmpty()
            val nextFields = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, px(8))
            }
            nextFields.addView(text("后续动画", 14f).apply {
                setPadding(0, 0, px(12), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val nextChoice = TextView(this).apply {
                text = if (nextModeId.isEmpty()) "自动（由系统决定）  ▾" else "$nextModeId  ▾"
                textSize = 13f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setTextColor(getColor(R.color.cp_title))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(px(12), 0, px(12), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    chooseModes(single = true,
                        initial = listOfNotNull(nextModeId.takeIf { it.isNotEmpty() }),
                        headerDescription = "当前动画 · $key") { chosen ->
                        nextModeId = chosen.firstOrNull().orEmpty()
                        text = if (nextModeId.isEmpty()) "自动（由系统决定）  ▾" else "$nextModeId  ▾"
                    }
                }
            }
            nextFields.addView(nextChoice, LinearLayout.LayoutParams(0, px(44), 1f))
            box.addView(nextFields)
            fun refreshFields() {
                val selected = playback.selectedItemPosition
                timedFields.visibility = if (selected == 1) View.VISIBLE else View.GONE
                nextFields.visibility = if (selected == 2) View.GONE else View.VISIBLE
            }
            playback.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, itemId: Long
                ) { refreshFields() }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            refreshFields()
            val save: () -> Unit = {
                val renamed = name.text.toString().trim()
                validId(renamed)
                require(renamed == key || !root.getJSONObject("modes").has(renamed)) { "动画名称已存在" }
                val target = nextModeId.trim()
                require(target.isEmpty() || target == key || root.getJSONObject("modes").has(target)) { "后续动画不存在" }
                val loopMode = when (playback.selectedItemPosition) {
                    1 -> "timed"
                    2 -> "infinite"
                    else -> "off"
                }
                // Keep inactive duration, finishCycle and nextMode values.
                // Only a selected timed mode validates and updates the timer inputs.
                val duration = if (loopMode == "timed") {
                    val lo = min.text.toString().toLong()
                    val hi = max.text.toString().toLong()
                    require(lo > 0 && hi >= lo) { "时长必须为正数，最长不得小于最短" }
                    JSONObject().put("min", lo).put("max", hi)
                } else null
                change { json ->
                    val modes = json.getJSONObject("modes")
                    val m = modes.getJSONObject(key)
                    m.put("loopMode", loopMode)
                    m.put("loop", loopMode != "off")
                    m.put("nextMode", if (target.isEmpty()) JSONObject.NULL else target)
                    if (duration != null) m.put("duration", duration)
                    if (loopMode == "timed") m.put("finishCycle", finish.isChecked)
                    if (renamed != key) {
                        modes.remove(key)
                        modes.put(renamed, m)
                        modes.keys().forEach { n ->
                            val entry = modes.getJSONObject(n)
                            if (entry.optString("nextMode") == key) entry.put("nextMode", renamed)
                        }
                        substitute(json.getJSONObject("functions"), key, renamed)
                    }
                }
                showMode(renamed)
            }
            save
        }
    }
    private fun naturalOrder(path: String): String =
        Regex("\\d+").replace(path.lowercase()) { it.value.padStart(12, '0') }

    private fun frameDelayFor(path: String): Int {
        val file = File(draft, path)
        val metadata = File(file.parentFile, "frames.json")
        if (metadata.isFile) {
            runCatching {
                val frames = JSONObject(metadata.readText()).getJSONArray("frames")
                for (i in 0 until frames.length()) {
                    val item = frames.getJSONObject(i)
                    if (item.optString("file") == file.name)
                        return item.getInt("delay").coerceAtLeast(20)
                }
            }
        }
        return 120
    }

    private fun newFrameFor(path: String): JSONObject =
        JSONObject().put("res", path).put("delay", frameDelayFor(path))

    private fun appendFrames(key: String, paths: List<String>) {
        require(paths.isNotEmpty())
        change { json ->
            val frames = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
            paths.forEach { frames.put(newFrameFor(it)) }
        }
        bulkSelected.clear()
        showMode(key)
    }

    private fun applyImageSelection(request: ImagePickRequest, paths: List<String>) {
        require(paths.isNotEmpty()) { "请至少选择一张图片" }
        if (request.action == "batch") require(paths.size == 1) { "批量替换只能选择一张图片" }
        val key = request.mode
        change { json ->
            val modes = json.getJSONObject("modes")
            val current = modes.getJSONObject(key).getJSONArray("frames")
            val list = (0 until current.length()).map {
                JSONObject(current.getJSONObject(it).toString())
            }.toMutableList()
            when (request.action) {
                "replace" -> {
                    require(request.index in list.indices)
                    val previous = list.removeAt(request.index)
                    list.addAll(request.index, paths.map {
                        JSONObject(previous.toString()).put("res", it)
                    })
                }
                "before", "after" -> {
                    require(request.index in list.indices)
                    list.addAll(request.index + if (request.action == "after") 1 else 0,
                        paths.map(::newFrameFor))
                }
                "batch" -> request.batch.forEach { index ->
                    if (index in list.indices) list[index].put("res", paths.first())
                }
                else -> error("未知图片操作")
            }
            modes.getJSONObject(key).put("frames", JSONArray().also { arr ->
                list.forEach { arr.put(it) }
            })
        }
        bulkSelected.clear()
        showMode(key)
    }

    /** Make a visible, uniquely numbered directory before returning to the image picker. */
    private fun newResourceFolder(afterCreated: () -> Unit) {
        editor("新建文件夹", compact = true) { box ->
            val field = entry(box, "文件夹名称", "", compact = true)
            val save: () -> Unit = {
                val name = field.text.toString().trim()
                require(name.isNotEmpty() && name != "." && name != ".." &&
                    name.length <= 80 && name.none {
                        it == '/' || it == '\\' || it.code < 32 || it == ':'
                    }) { "请输入有效的文件夹名称" }
                resourceChange { working, _ ->
                    val directory = File(working, RolePackEditing.uniqueName(working, name))
                    check(directory.mkdirs()) { "无法创建文件夹" }
                }
                handler.post { afterCreated() }
            }
            save
        }
    }

    private fun showImageChooser(request: ImagePickRequest, initial: List<String> = emptyList()) {
        val single = request.action == "batch"
        val selected = linkedSetOf<String>().apply {
            addAll(initial.filter { File(draft, it).let(::isImage) })
            if (single && size > 1) {
                val first = first()
                clear()
                add(first)
            }
        }
        val expanded = mutableSetOf<String>()
        // Preserve selection and reopen the folder that received a newly imported image.
        val thumbnails = mutableMapOf<String, android.graphics.Bitmap?>()
        initial.forEach { path ->
            var parent = File(path).parent?.replace('\\', '/')
            while (!parent.isNullOrEmpty()) {
                expanded.add(parent)
                parent = File(parent).parent?.replace('\\', '/')
            }
        }
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val (outer, body) = pickerShell(dialog, "选择素材",
            "动画 · ${request.mode}" + if (request.index >= 0) " · 第 ${request.index} 帧" else "")
        val info = text("", 12f).apply { setTextColor(getColor(R.color.cp_note)) }
        body.addView(info)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(dialogButton("新建文件夹", primary = false) {
            dialog.dismiss()
            newResourceFolder {
                showImageChooser(request, selected.toList())
            }
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { leftMargin = px(5) })
        body.addView(tools, LinearLayout.LayoutParams(-1, px(44)).apply { bottomMargin = px(8) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollList = ScrollView(this).apply { addView(list) }
        body.addView(scrollList, LinearLayout.LayoutParams(-1,
            (resources.displayMetrics.heightPixels * 0.54f).toInt()))
        lateinit var redraw: () -> Unit
        redraw = {
            list.removeAllViews()
            info.text = "已选择 ${selected.size} 张图片"
            fun visit(dir: File, depth: Int) {
                dir.listFiles().orEmpty()
                    .filter { isImage(it) || it.isDirectory && !it.name.startsWith(".") }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { naturalOrder(it.name) }))
                    .forEach { file ->
                        val path = file.relativeTo(draft).invariantSeparatorsPath
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            selectionSurface(this, isFolder = file.isDirectory)
                            setPadding(px(6 + depth * 10), px(3), px(6), px(3))
                        }
                        if (file.isDirectory) {
                            val descendants = file.walkTopDown().filter(::isImage)
                                .map { it.relativeTo(draft).invariantSeparatorsPath }.toList()
                            val count = descendants.count { it in selected }
                            if (!single) {
                                row.addView(CheckBox(this).apply {
                                    isChecked = descendants.isNotEmpty() && count == descendants.size
                                    // Counts beside the folder name distinguish none/partial/all.
                                    setOnCheckedChangeListener { _, checked ->
                                        if (checked) selected.addAll(descendants)
                                        else selected.removeAll(descendants.toSet())
                                        redraw()
                                    }
                                }, LinearLayout.LayoutParams(px(40), px(46)))
                            }
                            row.addView(text((if (path in expanded) "▾ 📂  " else "▸ 📁  ") + file.name +
                                " ($count/${descendants.size})", 14f).apply {
                                isClickable = true
                                setOnClickListener {
                                    if (!expanded.add(path)) expanded.remove(path)
                                    redraw()
                                }
                            }, LinearLayout.LayoutParams(0, px(46), 1f))
                            row.addView(text("+", 22f).apply {
                                gravity = Gravity.CENTER
                                isClickable = true
                                setOnClickListener { anchor ->
                                    dialog.dismiss()
                                    startUpload(false, path, "chooser", request.mode, request, selected.toList())
                                }
                            }, LinearLayout.LayoutParams(px(40), px(46)))
                            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                                bottomMargin = px(5)
                            })
                            if (path in expanded) visit(file, depth + 1)
                        } else {
                            val cb = CheckBox(this).apply { isChecked = path in selected }
                            fun toggle(yes: Boolean) {
                                if (single) selected.clear()
                                if (yes) selected.add(path) else selected.remove(path)
                                redraw()
                            }
                            cb.setOnCheckedChangeListener { _, yes -> toggle(yes) }
                            row.addView(cb, LinearLayout.LayoutParams(px(40), px(48)))
                            row.addView(ImageView(this).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setImageBitmap(thumbnails.getOrPut(path) { thumb(file, 72) })
                            }, LinearLayout.LayoutParams(px(44), px(44)))
                            row.addView(text(file.name, 13f).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                isClickable = true
                                setOnClickListener { toggle(path !in selected) }
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                                bottomMargin = px(5)
                            })
                        }
                    }
            }
            visit(draft, 0)
        }
        redraw()
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        buttons.addView(dialogButton("取消", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(4) })
        buttons.addView(dialogButton("确认", primary = true) {
            if (selected.isEmpty()) Toast.makeText(this, "请至少选择一张图片", Toast.LENGTH_SHORT).show()
            else attempt {
                val paths = selected.sortedWith(compareBy(::naturalOrder))
                applyImageSelection(request, paths)
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(4) })
        body.addView(buttons)
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    private fun removeBulkBar() {
        bulkBar?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        bulkBar = null
        bulkCountView = null
    }

    /**
     * Selection updates only visible checkbox and menu state. Row measurements,
     * images, titles and scroll ranges remain untouched.
     */
    private fun refreshVisibleFrameSelection(recycler: RecyclerView) {
        syncingBulkSelection = true
        try {
            for (i in 0 until recycler.childCount) {
                val row = recycler.getChildAt(i) as? LinearLayout ?: continue
                val position = recycler.getChildViewHolder(row).bindingAdapterPosition - 1
                if (position !in 0 until (recycler.adapter?.itemCount ?: 1) - 1) continue
                (row.getChildAt(0) as CheckBox).isChecked = position in bulkSelected
                val card = row.getChildAt(1) as LinearLayout
                (card.getChildAt(2) as TextView).visibility =
                    if (bulkSelected.isEmpty()) View.VISIBLE else View.INVISIBLE
            }
        } finally {
            syncingBulkSelection = false
        }
    }

    private fun updateBulkBar(key: String) {
        if (bulkSelected.isEmpty()) {
            removeBulkBar()
            return
        }
        // Reuse the toolbar instead of repeatedly removing and adding a layout.
        if (bulkBar != null) {
            bulkCountView?.text = "已选择 ${bulkSelected.size} 帧"
            return
        }
        val host = findViewById<FrameLayout>(android.R.id.content)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
            setBackgroundResource(R.drawable.card_bg)
            elevation = px(8).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bulkCountView = text("已选择 ${bulkSelected.size} 帧", 14f)
        header.addView(bulkCountView,
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("全选", 13f).apply {
            isClickable = true
            setOnClickListener {
                val n = root.getJSONObject("modes").getJSONObject(key)
                    .getJSONArray("frames").length()
                bulkSelected.clear()
                bulkSelected.addAll(0 until n)
                activeFrameRecycler?.let { refreshVisibleFrameSelection(it) }
                updateBulkBar(key)
            }
        })
        header.addView(text("清空", 13f).apply {
            isClickable = true
            setOnClickListener {
                bulkSelected.clear()
                activeFrameRecycler?.let { refreshVisibleFrameSelection(it) }
                updateBulkBar(key)
            }
        })
        body.addView(header)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("编辑属性") { editBulkFrameProperties(key) },
            LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(4) })
        buttons.addView(button("删除") { deleteBulkFrames(key) },
            LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(4) })
        buttons.addView(button("替换") {
            showImageChooser(ImagePickRequest(key, "batch", batch = bulkSelected.sorted()))
        }, LinearLayout.LayoutParams(0, px(44), 1f))
        body.addView(buttons)
        bulkBar = body
        host.addView(body, FrameLayout.LayoutParams(-1, px(102), Gravity.BOTTOM).apply {
            leftMargin = px(12)
            rightMargin = px(12)
            bottomMargin = px(8)
        })
    }

    private fun deleteBulkFrames(key: String) {
        val frames = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
        val indices = bulkSelected.toSet()
        if (indices.isEmpty()) return
        if (indices.size >= frames.length()) {
            Toast.makeText(this, "至少需要保留一帧", Toast.LENGTH_SHORT).show()
            return
        }
        showConfirmDialog(
            title = "批量删除",
            bodyHtml = "确认删除选中的 ${indices.size} 帧？",
            positiveText = "删除",
            titleColorRes = R.color.cp_danger
        ) {
            attempt {
                change { json ->
                    val current = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                    json.getJSONObject("modes").getJSONObject(key).put("frames",
                        remapFrames(current, (0 until current.length()).filter { it !in indices }))
                }
                bulkSelected.clear()
                showMode(key)
            }
        }
    }

    private val frameTransforms = listOf(
        "关闭" to "none", "左右镜像" to "flipX", "上下镜像" to "flipY",
        "顺时针旋转 90°" to "rotate90", "旋转 180°" to "rotate180",
        "顺时针旋转 270°" to "rotate270"
    )
    private val frameAlignments = listOf(
        "顶部对齐" to "top", "居中对齐" to "center", "底部对齐" to "bottom"
    )

    private fun frameGroup(box: LinearLayout, title: String, note: String = ""): LinearLayout {
        val group = dialogSection()
        group.addView(text(title, 15f).apply { setPadding(0, 0, 0, px(6)) })
        if (note.isNotBlank()) group.addView(text(note, 11f).apply {
            setTextColor(getColor(R.color.cp_note))
            setPadding(0, 0, 0, px(7))
        })
        addDetailSection(box, group)
        return group
    }
    private fun frameFields(box: LinearLayout, a: String, av: String, b: String, bv: String,
                            ah: String = "", bh: String = ""): Pair<EditText, EditText> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = entry(row, a, av, compact = true)
        val right = entry(row, b, bv, compact = true)
        formFieldOf(left).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) }
        formFieldOf(right).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) }
        if (ah.isNotBlank()) formFieldOf(left).setPlaceholderText(ah)
        if (bh.isNotBlank()) formFieldOf(right).setPlaceholderText(bh)
        box.addView(row)
        return left to right
    }
    private fun frameSelect(box: LinearLayout, title: String,
                            choices: List<Pair<String, String>>, selected: String,
                            onChoose: (String) -> Unit = {}): Spinner {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(title, 13f), LinearLayout.LayoutParams(0, -2, 1f))
        val dropdown = Spinner(this)
        SettingsSpinnerBinder.bind(dropdown, choices.map { it.first },
            choices.indexOfFirst { it.second == selected }.coerceAtLeast(0)) {
            onChoose(choices[it].second)
        }
        row.addView(dropdown, LinearLayout.LayoutParams(0, px(46), 1f))
        box.addView(row)
        return dropdown
    }
    private fun delayRange(f: JSONObject): Pair<String, String> = when (val raw = f.opt("delay")) {
        is JSONArray -> raw.optLong(0, 120).toString() to raw.optLong(1, 120).toString()
        is Number -> raw.toLong().toString() to raw.toLong().toString()
        else -> "120" to "120"
    }
    private fun makeDelay(min: EditText, max: EditText): Any {
        val lo = min.text.toString().trim().toLongOrNull()
        val hi = max.text.toString().trim().toLongOrNull()
        require(lo != null && hi != null && lo > 0 && hi >= lo) {
            "播放时间必须为正整数，最长时间不能小于最短时间"
        }
        return if (lo == hi) lo else JSONArray().put(lo).put(hi)
    }
    private fun putOptionalInt(frame: JSONObject, name: String, input: EditText) {
        val value = input.text.toString().trim()
        if (value.isEmpty()) frame.remove(name)
        else frame.put(name, value.toIntOrNull() ?: throw IllegalArgumentException("$name 请输入整数"))
    }
    private fun transformedThumb(source: android.graphics.Bitmap, transform: String): android.graphics.Bitmap {
        if (transform == "none") return source
        val matrix = android.graphics.Matrix()
        when (transform) {
            "flipX" -> matrix.setScale(-1f, 1f)
            "flipY" -> matrix.setScale(1f, -1f)
            "rotate90" -> matrix.setRotate(90f)
            "rotate180" -> matrix.setRotate(180f)
            "rotate270" -> matrix.setRotate(270f)
            else -> return source
        }
        return android.graphics.Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun editBulkFrameProperties(key: String) {
        val frames = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
        val indices = bulkSelected.sorted()
        if (indices.isEmpty()) return
        editor("批量编辑帧属性", compact = true,
            subtitle = "动画 · $key · 已选 ${indices.size} 帧") { box ->
            val first = frames.getJSONObject(indices.first())
            val touched = mutableSetOf<String>()
            fun watch(input: EditText, name: String) {
                input.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { touched.add(name) }
                })
            }
            val time = frameGroup(box, "播放时间", "单位毫秒，相同为固定时长，不同则随机取值。")
            val range = delayRange(first)
            val (min, max) = frameFields(time, "最短时间", range.first, "最长时间", range.second)
            watch(min, "delay"); watch(max, "delay")
            val move = frameGroup(box, "帧位移", "播放这一帧时产生的位移，可填写负数。")
            val (dx, dy) = frameFields(move, "水平 X", optional(first, "dx"), "垂直 Y", optional(first, "dy"), "0", "0")
            watch(dx, "dx"); watch(dy, "dy")
            val anchor = frameGroup(box, "锚点位置", "用于图片对齐；水平距左侧、垂直距底边。")
            val (ax, ay) = frameFields(anchor, "水平 X", optional(first, "anchorX"),
                "垂直 Y", optional(first, "anchorY"), "自动居中", "图片底边")
            watch(ax, "anchorX"); watch(ay, "anchorY")
            val image = frameGroup(box, "图片处理", "只修改你实际调整过的选项。")
            var transformValue = first.optString("transform", "none")
            var alignValue = first.optString("verticalAlign", "bottom")
            frameSelect(image, "图片变换", frameTransforms, transformValue) { value ->
                if (value != transformValue) touched.add("transform")
                transformValue = value
            }
            frameSelect(image, "垂直对齐", frameAlignments, alignValue) { value ->
                if (value != alignValue) touched.add("verticalAlign")
                alignValue = value
            }
            ({
                if (touched.isNotEmpty()) {
                    val delay = if ("delay" in touched) makeDelay(min, max) else null
                    val numeric = mapOf("dx" to dx, "dy" to dy, "anchorX" to ax, "anchorY" to ay)
                        .filterKeys { it in touched }
                        .mapValues { (_, input) ->
                            input.text.toString().trim().let { str ->
                                if (str.isEmpty()) null else str.toIntOrNull()
                                    ?: throw IllegalArgumentException("位移与锚点必须是整数")
                            }
                        }
                    change { json ->
                        val target = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                        indices.forEach { index ->
                            val frame = target.getJSONObject(index)
                            if (delay != null) frame.put("delay", delay)
                            numeric.forEach { (name, value) ->
                                if (value == null) frame.remove(name) else frame.put(name, value)
                            }
                            if ("transform" in touched) {
                                if (transformValue == "none") frame.remove("transform")
                                else frame.put("transform", transformValue)
                            }
                            if ("verticalAlign" in touched) {
                                if (alignValue == "bottom") frame.remove("verticalAlign")
                                else frame.put("verticalAlign", alignValue)
                            }
                        }
                    }
                }
                bulkSelected.clear()
                showMode(key)
            })
        }
    }
    private fun showFrameOptions(anchor: View, key: String, index: Int) {
        UnifiedOptionMenu.show(anchor,
            listOf("编辑", "替换", "在上方插入", "在下方插入", "删除"), setOf("删除")) { choice ->
            when (choice) {
                "编辑" -> editFrame(key, index)
                "替换" -> showImageChooser(ImagePickRequest(key, "replace", index))
                "在上方插入" -> showImageChooser(ImagePickRequest(key, "before", index))
                "在下方插入" -> showImageChooser(ImagePickRequest(key, "after", index))
                "删除" -> showConfirmDialog(
                    title = "删除帧",
                    bodyHtml = "确认删除帧 $index？",
                    positiveText = "删除",
                    titleColorRes = R.color.cp_danger
                ) {
                    attempt {
                        val old = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                        require(old.length() > 1) { "至少保留一帧" }
                        change {
                            it.getJSONObject("modes").getJSONObject(key).put("frames",
                                remapFrames(old, (0 until old.length()).filter { n -> n != index }))
                        }
                        showMode(key)
                    }
                }
            }
        }
    }

    private fun editFrame(key: String, index: Int) {
        val frame = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").getJSONObject(index)
        editor("编辑第 $index 帧", compact = true,
            subtitle = "动画 · $key") { box ->
            val time = frameGroup(box, "播放时间", "单位毫秒，相同为固定时长，不同则随机取值。")
            val range = delayRange(frame)
            val (min, max) = frameFields(time, "最短时间", range.first, "最长时间", range.second)
            val move = frameGroup(box, "帧位移", "播放这一帧时产生的位移，可填写负数。")
            val (dx, dy) = frameFields(move, "水平 X", optional(frame, "dx"),
                "垂直 Y", optional(frame, "dy"), "0", "0")
            val anchor = frameGroup(box, "锚点位置", "用于图片对齐；水平距左侧、垂直距底边。")
            val (ax, ay) = frameFields(anchor, "水平 X", optional(frame, "anchorX"),
                "垂直 Y", optional(frame, "anchorY"), "自动居中", "图片底边")
            val image = frameGroup(box, "图片处理")
            val transform = frameSelect(image, "图片变换", frameTransforms, frame.optString("transform", "none"))
            val align = frameSelect(image, "垂直对齐", frameAlignments, frame.optString("verticalAlign", "bottom"))
            ({
                val delay = makeDelay(min, max)
                change { json ->
                    val current = json.getJSONObject("modes").getJSONObject(key)
                        .getJSONArray("frames").getJSONObject(index)
                    current.put("delay", delay)
                    putOptionalInt(current, "dx", dx); putOptionalInt(current, "dy", dy)
                    putOptionalInt(current, "anchorX", ax); putOptionalInt(current, "anchorY", ay)
                    val transformation = frameTransforms[transform.selectedItemPosition].second
                    val alignment = frameAlignments[align.selectedItemPosition].second
                    if (transformation == "none") current.remove("transform")
                    else current.put("transform", transformation)
                    if (alignment == "bottom") current.remove("verticalAlign")
                    else current.put("verticalAlign", alignment)
                    current.remove("flipX")
                }
                showMode(key)
            })
        }
    }
    private fun optional(obj: JSONObject, key: String) = if (obj.isNull(key)) "" else obj.optString(key)
    private fun remapFrames(old: JSONArray, orderedIndices: List<Int>): JSONArray {
        require(orderedIndices.isNotEmpty())
        return JSONArray().also { result ->
            orderedIndices.forEach { result.put(JSONObject(old.getJSONObject(it).toString())) }
        }
    }
    private fun copyMode(key: String) {
        editor("复制动画") { box ->
            val name = entry(box, "新动画名称", key + "副本")
            val save: () -> Unit = {
                val target = name.text.toString().trim()
                validId(target)
                require(!root.getJSONObject("modes").has(target)) { "动画名称已存在，请更换名称" }
                change {
                    val m = it.getJSONObject("modes")
                    m.put(target, JSONObject(m.getJSONObject(key).toString()))
                }
                showMode(target)
            }
            save
        }
    }
    private fun validId(id: String) {
        require(id.isNotBlank() && id.length <= 40 &&
            id.none { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
            "动画名称不能为空、不能超过 40 字，且不能包含斜杠或换行"
        }
    }
    private fun substitute(obj: JSONObject, from: String, to: String) {
        obj.keys().forEach { key ->
            when(val value = obj.opt(key)) {
                is JSONObject -> substitute(value, from, to)
                is JSONArray -> for (i in 0 until value.length()) if (value.optString(i) == from) value.put(i, to)
            }
        }
    }
    private fun deleteMode(key: String) {
        showConfirmDialog(
            title = "删除动画",
            bodyHtml = "确定删除「$key」？",
            subText = "仍被功能或其他动画引用时无法删除",
            positiveText = "删除",
            titleColorRes = R.color.cp_danger
        ) {
            attempt {
                require(ids().size > 1) { "至少保留一个动画" }
                change { it.getJSONObject("modes").remove(key) }
                modes()
            }
        }
    }
    /** Only the UI is translated. Engine function path keys remain the canonical English API. */
    private fun functionSegment(path: List<String>): String {
        return when (path.joinToString("/")) {
            "pat_head/pat_head", "snap/pat_head/pat_head" -> "摸头"
            "feed/eat", "snap/feed/eat" -> "吃"
            "feed/food" -> "食物"
            "standby/pool" -> "待机池"
            "standby/static_pool" -> "静止待机池"
            else -> when (path.last()) {
                "standby" -> "待机"
                "touch" -> "抛掷"
                "snap" -> "吸附"
                "climb" -> "攀岩"
                "pat_head" -> if (path.size == 1) "摸摸头" else "摸头"
                "feed" -> "投食"
                "menu" -> "菜单动画"
                "pool" -> "动画池"
                "roll" -> "滚动"
                "lift_up" -> "提起"
                "slow" -> "慢速"
                "left_medium" -> "向左中速"
                "left_fast" -> "向左快速"
                "right_medium" -> "向右中速"
                "right_fast" -> "向右快速"
                "jump_down" -> "落地"
                "jump_up" -> "跳起"
                "leap" -> "飞跃"
                "landing" -> "降落"
                "forage" -> "觅食"
                "move" -> "移动"
                "snap_head" -> "探头"
                "eat" -> "吃"
                "food" -> "食物"
                else -> path.last()
            }
        }
    }

    private fun functionPathName(path: List<String>): String =
        path.indices.joinToString("/") { functionSegment(path.take(it + 1)) }

    private fun functionDescription(path: List<String>): String = when (path.joinToString("/")) {
        "standby/pool" -> "角色正常待机、初始进入或归位时的候选动画"
        "standby/static_pool" -> "移动功能关闭时随机选取的待机动画"
        "touch/roll" -> "角色抛掷或滚动时播放"
        "touch/jump_down" -> "角色抛掷或滚动结束时播放"
        "touch/lift_up/slow" -> "缓慢提起角色时播放"
        "touch/lift_up/left_medium" -> "向左中速拖动提起时播放"
        "touch/lift_up/left_fast" -> "向左快速拖动提起时播放"
        "touch/lift_up/right_medium" -> "向右中速拖动提起时播放"
        "touch/lift_up/right_fast" -> "向右快速拖动提起时播放"
        "snap/snap_head" -> "角色吸附边缘后探头时播放"
        "snap/pat_head/pat_head" -> "角色吸附状态被摸头时播放"
        "snap/feed/eat" -> "角色吸附状态接受投喂时播放"
        "climb/leap/leap" -> "角色飞跃过程中播放的动画"
        "climb/leap/landing" -> "角色飞跃后接触墙面时播放"
        "climb/forage/move" -> "角色奔向食物时播放的动画"
        "pat_head/pat_head" -> "正常状态下摸头互动时播放"
        "feed/eat" -> "角色接受投喂并进食时播放"
        "feed/food" -> "投喂时显示的食物动画"
        "menu/pool" -> "菜单中供手动选择的动画"
        else -> "执行该功能时，从关联动画中选择播放"
    }.replace("角色", "「${root.optString("name").ifBlank { "素材包" }}」")

    private fun functionFolderDescription(path: List<String>): String = when (path.joinToString("/")) {
        "standby" -> "角色正常待机和关闭移动时使用的动画池"
        "touch" -> "角色提起、滚动和落地时的动画"
        "touch/lift_up" -> "角色被提起时的动画"
        "snap" -> "角色贴边吸附时的动画"
        "snap/pat_head" -> "角色吸附时的摸头动画"
        "snap/feed" -> "角色吸附时的投喂动画"
        "climb" -> "角色攀爬、飞跃和觅食时的动画"
        "climb/leap" -> "角色飞跃及降落时的动画"
        "climb/forage" -> "角色自动寻找食物时的动画"
        "pat_head" -> "角色被摸头时的互动动画"
        "feed" -> "角色接受投喂时的动画"
        "menu" -> "菜单中可手动选择的动画"
        else -> "展开查看该分类下的功能"
    }.replace("角色", "「${root.optString("name").ifBlank { "素材包" }}」")

    private fun functionItem(node: LinearLayout, title: String, subtitle: String,
                             level: Int, immediate: Boolean = false, description: String = "",
                             examplePath: List<String>? = null, click: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            rippleCard(this)
            setPadding(if (examplePath != null) px(1) else px(12), px(5),
                if (examplePath != null) px(1) else px(12), px(5))
            isClickable = true
            if (immediate) setOnClickListener { click() }
            else rippleClick(this) { click() }
        }
        // The title chevron belongs to a real centered row, not spaced text.
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (examplePath != null) {
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(text(title, 15f).apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(-2, -2))
            titleRow.addView(text("›", 15f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.cp_title))
                setPadding(px(4), 0, 0, 0)
            }, LinearLayout.LayoutParams(-2, -2))
            content.addView(titleRow)
        } else {
            content.addView(text(title, 15f).apply { setPadding(0, 0, 0, 0) })
        }
        if (description.isNotBlank()) content.addView(text(description, 10f).apply {
            setPadding(0, px(2), 0, 0)
            setTextColor(getColor(R.color.cp_note))
        })
        if (examplePath != null) {
            val refs = functionAnimationsAt(examplePath)
            val bad = refs.filter { id ->
                val mode = root.optJSONObject("modes")?.optJSONObject(id)
                mode?.optJSONArray("frames")?.length()?.let { it > 0 } != true
            }
            val missing = refs.isEmpty()
            if (missing || bad.isNotEmpty()) {
                val critical = examplePath == MaterialFunctionSupport.STATIC
                val required = examplePath in setOf(
                    MaterialFunctionSupport.STATIC, MaterialFunctionSupport.NORMAL,
                    MaterialFunctionSupport.THROW, MaterialFunctionSupport.SNAP,
                    MaterialFunctionSupport.FEED, MaterialFunctionSupport.LEAP,
                    MaterialFunctionSupport.FORAGE, listOf("pat_head", "pat_head")
                )
                val note = when {
                    critical && missing -> "运行必备：静止待机池为空，角色暂不可启动"
                    critical && bad.isNotEmpty() -> "静止待机包含无效动画：${bad.joinToString("、")}"
                    missing && required -> "该功能必要动画未配置，相关行为暂不可用"
                    missing -> "可选动画未配置，不影响其他功能"
                    else -> "部分引用动画无效：${bad.joinToString("、")}"
                }
                content.addView(text(note, 10f).apply {
                    setPadding(0, px(2), 0, 0)
                    setTextColor(getColor(if (required || bad.isNotEmpty()) R.color.cp_danger else R.color.cp_note))
                })
            }
        }
        if (subtitle.isNotBlank()) {
            val subtitleView = if (examplePath != null) {
                val functionPath = examplePath
                val animationNames = functionAnimationsAt(functionPath)
                linkedModeChains(animationNames, root.getJSONObject("modes"), "当前动画：", 10f) {
                    openModeFromDetail(it)
                }
            } else text(subtitle, 10f)
            subtitleView.apply {
                setPadding(0, px(2), 0, 0)
                setTextColor(getColor(if (examplePath == null) R.color.cp_note else R.color.cp_title))
            }
            content.addView(subtitleView)
        }
        val bitmap = examplePath?.let { templateRandomFrame(it) }
        if (bitmap != null) {
            card.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = false
                isFocusable = false
            }, LinearLayout.LayoutParams(px(48), px(48)).apply {
                rightMargin = px(5)
            })
        }
        card.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        if (examplePath != null) {
            card.addView(text("⋮", 23f).apply {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.cp_title))
                isClickable = true
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(px(38), px(48)))
        }
        node.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = px((level * 12).coerceAtMost(48))
            topMargin = px(4)
            bottomMargin = px(4)
        })
        return card
    }

    private fun unsupportedFunction(path: List<String>, value: Any?) {
        editor("未定义 / 不支持的功能", compact = true, readOnly = true,
            subtitle = path.joinToString("/"), readOnlyActionLabel = "编辑 JSONC",
            extraAction = {
                detailDialogs.lastOrNull { it.isShowing }?.dismiss()
                editPackSource()
            }) { form ->
            form.addView(text("当前版本没有该功能的内置定义或其类型不符合要求；" +
                "原始值已保留，可通过 JSONC 编辑器修复。", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
            })
            form.addView(text(value?.toString() ?: "null", 12f).apply {
                setTextIsSelectable(true)
            })
            ({ })
        }
    }

    private fun functionTree(parent: LinearLayout, values: JSONObject, path: List<String>) {
        val keys = values.keys().asSequence()
            .filter { it != "thinking" && it != "modes" && it != "note" && it != "usage" }
            .toList().let { existing ->
                val priority = when (path.joinToString("/")) {
                    "touch" -> listOf("lift_up", "roll", "jump_down")
                    "feed" -> listOf("food", "eat")
                    "standby" -> listOf("pool", "static_pool")
                    else -> emptyList()
                }
                priority.filter { it in existing } + existing.filter { it !in priority }
            }
        for (key in keys) {
            val currentPath = path + key
            val fullKey = currentPath.joinToString("/")
            val displayName = functionPathName(currentPath)
            val undefined = currentPath !in RolePackNormalizer.supportedPaths &&
                RolePackNormalizer.supportedPaths.none { known ->
                    known.size > currentPath.size && known.take(currentPath.size) == currentPath
                }
            if (linkedMode && linkedPaths.none { allowed ->
                    fullKey == allowed || fullKey.startsWith(allowed + "/") ||
                    allowed.startsWith(fullKey + "/")
                }) continue
            when (val child = values.opt(key)) {
                is JSONObject -> if (child.has("modes")) {
                    val selected = jsonStrings(child.optJSONArray("modes"))
                    functionItem(parent, (if (undefined) "未定义 · " else "▣  ") + displayName,
                        if (undefined) "当前软件未定义此功能路径，原值已保留" else
                            "当前动画：" + selected.joinToString("，") { modeChainText(it, root.getJSONObject("modes")) },
                        path.size, description = if (undefined) fullKey else functionDescription(currentPath),
                        examplePath = if (undefined) null else currentPath) {
                        if (undefined) unsupportedFunction(currentPath, child)
                        else functionDetails(currentPath, selected)
                    }
                } else {
                    val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    var populated = false
                    lateinit var folderCard: LinearLayout
                    fun refreshFolder() {
                        val opened = fullKey in openedFunctionFolders
                        ((folderCard.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text =
                            (if (opened) "▾ 📂  " else "▸ 📁  ") + displayName
                        if (opened && !populated) {
                            populated = true
                            functionTree(children, child, currentPath)
                        }
                        children.visibility = if (opened) View.VISIBLE else View.GONE
                    }
                    folderCard = functionItem(parent, "",
                        if (undefined) "未定义文件夹 · 原配置已保留" else functionFolderDescription(currentPath),
                        path.size, immediate = true) {
                        if (!openedFunctionFolders.add(fullKey)) openedFunctionFolders.remove(fullKey)
                        refreshFolder()
                    }
                    parent.addView(children, LinearLayout.LayoutParams(-1, -2))
                    refreshFolder()
                }
                is JSONArray -> {
                    val selected = (0 until child.length()).map { child.optString(it) }
                        .filter { it.isNotBlank() }
                    functionItem(parent, (if (undefined) "未定义 · " else "▣  ") + displayName,
                        if (undefined) "当前软件未定义此功能路径，原值已保留" else
                            "当前动画：" + selected.joinToString("，") { modeChainText(it, root.getJSONObject("modes")) },
                        path.size, description = if (undefined) fullKey else functionDescription(currentPath),
                        examplePath = if (undefined) null else currentPath) {
                        if (undefined) unsupportedFunction(currentPath, child)
                        else functionDetails(currentPath, selected)
                    }
                }
                else -> {
                    functionItem(parent, "格式不支持 · " + displayName,
                        "字段类型无法识别，原始值已保留", path.size, description = fullKey) {
                        unsupportedFunction(currentPath, child)
                    }
                }
            }
        }
    }

    private fun functions() {
        // 每次重新加载功能页，都重新抽取示例；折叠/展开已存在的卡片不换。
        templateExamples.clear()
        section("功能映射", "functions")
        panel.addView(text("决定角色在不同情况下播放的动画。\n点击文件夹展开，点击功能卡片编辑。", 12f).apply {
            setPadding(px(4), 0, px(4), px(6))
            setTextColor(getColor(R.color.cp_note))
        })
        functionTree(panel, root.getJSONObject("functions"), emptyList())
    }

    /**
     * Reverse editor for Functions: a Mode may belong to multiple function pools.
     * The actual source of truth remains functions.jsonc; no duplicated Mode-side binding.
     */
    private fun bindModeToFunctions(modeId: String, onSaved: () -> Unit = {}) {
        val functionsRoot = root.getJSONObject("functions")
        val leaves = linkedMapOf<String, Pair<List<String>, List<String>>>()
        fun collect(node: JSONObject, parent: List<String>) {
            node.keys().asSequence().filter { it != "thinking" && it != "modes" }.toList().forEach { segment ->
                val path = parent + segment
                when (val value = node.opt(segment)) {
                    is JSONObject -> if (value.has("modes")) {
                        leaves[path.joinToString("/")] = path to jsonStrings(value.optJSONArray("modes"))
                    } else collect(value, path)
                    is JSONArray -> leaves[path.joinToString("/")] = path to
                        (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
                }
            }
        }
        collect(functionsRoot, emptyList())
        val selected = leaves.filterValues { modeId in it.second }.keys.toMutableSet()
        val expanded = mutableSetOf<String>()
        selected.forEach { key ->
            val path = leaves.getValue(key).first
            (1 until path.size).forEach { depth ->
                expanded.add(path.take(depth).joinToString("/"))
            }
        }

        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val (frame, body) = pickerShell(dialog, "绑定到功能",
            "动画 · $modeId")
        val selectionInfo = text("", 12f).apply { setTextColor(getColor(R.color.cp_note)) }
        body.addView(selectionInfo)
        val listing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val viewport = ScrollView(this).apply { addView(listing) }
        body.addView(viewport, LinearLayout.LayoutParams(
            -1, (resources.displayMetrics.heightPixels * .55f).toInt()
        ))

        fun warnLastBinding(path: List<String>, proceed: () -> Unit) {
            val critical = path == listOf("standby", "static_pool")
            val normalStandby = path == listOf("standby", "pool")
            showConfirmDialog(
                title = "解除最后一个动画绑定？",
                bodyHtml = if (critical)
                    "静止待机池清空后，素材包将不满足角色最低运行要求。"
                else if (normalStandby)
                    "普通待机池将为空，日常待机无法从此池选择动画。"
                else "功能「${functionPathName(path)}」将没有关联动画。",
                subText = if (critical) "可以继续保存或导出素材包，补齐静止待机动画后才可启动角色。"
                    else "对应动画不会触发，不影响其他独立功能。",
                positiveText = "仍然解绑"
            ) { proceed() }
        }

        lateinit var redraw: () -> Unit
        redraw = {
            val scrollPosition = viewport.scrollY
            listing.removeAllViews()
            selectionInfo.text = "已绑定 ${selected.size} 个功能"

            fun drawNode(node: JSONObject, parent: List<String>, depth: Int) {
                node.keys().asSequence().filter { it != "thinking" && it != "modes" }.toList().forEach { segment ->
                    val path = parent + segment
                    val key = path.joinToString("/")
                    when (val child = node.opt(segment)) {
                        is JSONObject -> if (child.has("modes")) {
                            val checked = key in selected
                            val check = CheckBox(this).apply {
                                text = functionSegment(path)
                                isChecked = checked
                                setOnCheckedChangeListener { _, checkedNow ->
                                    if (checkedNow) selected.add(key) else selected.remove(key)
                                }
                            }
                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                selectionSurface(this)
                                setPadding(px(8), px(3), px(8), px(3))
                                addView(check, LinearLayout.LayoutParams(-1, px(46)))
                            }
                            listing.addView(row, selectionRowParams(path.size))
                            rippleClick(row) { check.isChecked = !check.isChecked }
                        } else {
                            val descendantPaths = leaves.keys.filter { it.startsWith("$key/") }
                            val open = key in expanded
                            val folder = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                selectionSurface(this, isFolder = true)
                                setPadding(px(12), px(5), px(10), px(5))
                            }
                            folder.addView(text(
                                (if (open) "▾ 📂  " else "▸ 📁  ") + functionSegment(path), 15f
                            ).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                            folder.addView(text(
                                "${descendantPaths.count { it in selected }}/${descendantPaths.size}", 11f
                            ).apply { setTextColor(getColor(R.color.cp_note)) })
                            listing.addView(folder, selectionRowParams(depth).apply { height = px(49) })
                            rippleClick(folder) {
                                if (!expanded.add(key)) expanded.remove(key)
                                redraw()
                            }
                            if (open) drawNode(child, path, depth + 1)
                        }
                        is JSONArray -> {
                            val checked = key in selected
                            val row = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                selectionSurface(this)
                                setPadding(px(10), px(4), px(8), px(4))
                            }
                            val check = CheckBox(this).apply {
                                text = functionSegment(path)
                                textSize = 14f
                                isChecked = checked
                                setTextColor(getColor(R.color.cp_title))
                            }
                            row.addView(check, LinearLayout.LayoutParams(-1, px(42)))
                            row.addView(text(functionDescription(path), 11f).apply {
                                setTextColor(getColor(R.color.cp_note))
                                setPadding(px(10), 0, px(6), px(4))
                            })
                            listing.addView(row, selectionRowParams(depth + 1))
                            var restoringCheck = false
                            check.setOnCheckedChangeListener { _, nowChecked ->
                                if (restoringCheck) return@setOnCheckedChangeListener
                                if (!nowChecked && checked && leaves.getValue(key).second
                                        .filter { it != modeId }.isEmpty()) {
                                    restoringCheck = true
                                    try { check.isChecked = true }
                                    finally { restoringCheck = false }
                                    warnLastBinding(path) {
                                        selected.remove(key)
                                        redraw()
                                    }
                                } else {
                                    if (nowChecked) selected.add(key) else selected.remove(key)
                                    redraw()
                                }
                            }
                            rippleClick(row) { check.isChecked = !check.isChecked }
                        }
                    }
                }
            }
            drawNode(functionsRoot, emptyList(), 0)
            viewport.post { viewport.scrollTo(0, scrollPosition) }
        }
        redraw()
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        actions.addView(dialogButton("取消", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(5) })
        actions.addView(dialogButton("确认", primary = true) {
            attempt {
                // Empty pools are valid editor data; only runtime readiness is restricted.
                change { json ->
                    val functions = json.getJSONObject("functions")
                    for ((key, pair) in leaves) {
                        val (path, _) = pair
                        var node = functions
                        path.dropLast(1).forEach { node = node.getJSONObject(it) }
                        val original = node.optJSONArray(path.last()) ?: node.getJSONObject(path.last()).getJSONArray("modes")
                        val oldModes = (0 until original.length()).map { original.optString(it) }
                            .filter { it.isNotBlank() }
                        val updated = oldModes.filter { it != modeId }.toMutableList()
                        if (key in selected && modeId !in updated) updated.add(modeId)
                        if (node.optJSONObject(path.last()) != null) node.getJSONObject(path.last()).put("modes", JSONArray().apply { updated.distinct().forEach { put(it) } })
                        else node.put(path.last(), JSONArray().apply { updated.distinct().forEach { put(it) } })
                    }
                }
                dialog.dismiss()
                onSaved()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(5) })
        body.addView(actions)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    /** Shared grouped Mode selector for nextMode (single) and Functions leaves (multiple). */
    private fun chooseModes(single: Boolean, initial: List<String>,
                            headerTitle: String? = null, headerDescription: String? = null,
                            footerNote: String? = null, onConfirm: (List<String>) -> Unit) {
        val selected = linkedSetOf<String>().apply { addAll(initial.filter { it in ids() }) }
        val groups = knownGroups() + "未分组"
        val expanded = mutableSetOf<String>()
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val (frame, body) = pickerShell(dialog,
            headerTitle ?: if (single) "选择后续动画" else "选择功能动画",
            headerDescription)
        if (!footerNote.isNullOrBlank()) body.addView(text(footerNote, 11f).apply {
            setTextColor(getColor(R.color.cp_sub))
            setPadding(0, px(6), 0, px(8))
        })
        val selectionInfo = text("", 12f).apply { setTextColor(getColor(R.color.cp_note)) }
        body.addView(selectionInfo)
        val listing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ScrollView(this).apply { addView(listing) },
            LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .55f).toInt()))
        lateinit var redraw: () -> Unit
        redraw = {
            listing.removeAllViews()
            selectionInfo.text = "已选 ${selected.size} 个动画"
            if (single) {
                listing.addView(text("○  自动（由系统决定）", 15f).apply {
                    selectionSurface(this)
                    setPadding(px(12), px(6), px(8), px(6))
                    isClickable = true
                    setOnClickListener { selected.clear(); redraw() }
                    setTextColor(getColor(if (selected.isEmpty()) R.color.cp_title else R.color.cp_note))
                }, selectionRowParams().apply { height = px(46) })
            }
            for (group in groups.distinct()) {
                val candidates = ids().filter { modeId ->
                    val memberships = modeGroups(modeId)
                    if (group == "未分组") memberships.isEmpty() else group in memberships
                }
                if (candidates.isEmpty()) continue
                listing.addView(text((if (group in expanded) "▾ 📂 " else "▸ 📁 ") +
                    "$group（${candidates.count { it in selected }}/${candidates.size}）", 15f).apply {
                    selectionSurface(this, isFolder = true)
                    setPadding(px(12), px(6), px(8), px(6))
                    isClickable = true
                    setOnClickListener { if (!expanded.add(group)) expanded.remove(group); redraw() }
                }, selectionRowParams())
                if (group in expanded) {
                    for (name in candidates) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            selectionSurface(this)
                            setPadding(px(10), 0, px(5), 0)
                        }
                        val check = CheckBox(this).apply {
                            text = name
                            isChecked = name in selected
                            setOnCheckedChangeListener { _, checked ->
                                if (single) selected.clear()
                                if (checked) selected.add(name) else selected.remove(name)
                                redraw()
                            }
                        }
                        row.addView(check, LinearLayout.LayoutParams(-1, px(46)))
                        listing.addView(row, selectionRowParams(1))
                        rippleClick(row) { check.isChecked = !check.isChecked }
                    }
                }
            }
        }
        redraw()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        actions.addView(dialogButton("取消", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(5) })
        actions.addView(dialogButton("确认", primary = true) {
            onConfirm(selected.toList())
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(5) })
        body.addView(actions)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    private fun chooseFunctions(path: List<String>, chosen: List<String>) {
        chooseModes(single = false, initial = chosen,
            headerTitle = functionPathName(path),
            headerDescription = functionDescription(path),
            footerNote = "执行到该功能时，将随机选中以上动画播放") { selected ->
            val saveSelection = {
                attempt {
                    change { json ->
                        var node = json.getJSONObject("functions")
                        path.dropLast(1).forEach { node = node.getJSONObject(it) }
                        if (node.optJSONObject(path.last()) != null) node.getJSONObject(path.last()).put("modes", JSONArray(selected))
                        else node.put(path.last(), JSONArray(selected))
                    }
                    if (detailDialogs.none { it.isShowing }) functions()
                }
            }
            if (chosen.isNotEmpty() && selected.isEmpty()) {
                val critical = path == listOf("standby", "static_pool")
                showConfirmDialog(
                    title = "清空功能动画？",
                    bodyHtml = if (critical) "静止待机池清空后，角色将无法满足最低运行要求。"
                        else "功能「${functionPathName(path)}」将没有关联动画。",
                    subText = if (critical) "素材包仍可保存、导出，但需补齐静止待机动作才能启动。"
                        else "不会自动替代为其他功能的动画。",
                    positiveText = "仍然清空"
                ) { saveSelection() }
            } else saveSelection()
        }
    }
    private fun imageUses(): Map<String, List<Pair<String, Int>>> {
        val used = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        val modes = root.getJSONObject("modes")
        modes.keys().forEach { mode ->
            val frames = modes.getJSONObject(mode).getJSONArray("frames")
            for (i in 0 until frames.length()) {
                val res = frames.getJSONObject(i).optString("res")
                used.getOrPut(res) { mutableListOf() }.add(mode to i)
            }
        }
        return used
    }

    private fun isImage(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg")

    /** File operations and JSON path references are committed atomically together. */
    private fun resourceChange(operation: (File, JSONObject) -> Unit) {
        val working = RolePackEditing.draft(this, id)
        try {
            val candidate = JSONObject(root.toString())
            operation(working, candidate)
            RolePackEditing.commit(this, id, working, candidate)
            draft.deleteRecursively()
            root = candidate
            draft = RolePackEditing.draft(this, id)
        } finally {
            if (working.exists()) working.deleteRecursively()
        }
    }

    private fun renameResource(relative: String, newBase: String, directory: Boolean) {
        require(newBase.isNotBlank() && newBase != "." && newBase != ".." &&
            newBase.length <= 100 &&
            newBase.none { it == '/' || it == '\\' || it.code < 32 }) {
            "名称不能为空，不能包含斜杠或控制字符"
        }
        resourceChange { working, candidate ->
            val from = File(working, relative)
            require(from.exists() && from.isDirectory == directory) { "原文件不存在" }
            val newName = if (directory) newBase else newBase + "." + from.extension
            val to = File(from.parentFile, newName)
            require(!to.exists()) { "名称已经存在" }
            check(from.renameTo(to)) { "重命名失败" }
            // GIF frame timing metadata is keyed by filename; keep it in sync on image rename.
            if (!directory) {
                val manifest = File(to.parentFile, "frames.json")
                if (manifest.isFile) {
                    val json = JSONObject(manifest.readText())
                    val items = json.optJSONArray("frames")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val frame = items.getJSONObject(i)
                            if (frame.optString("file") == from.name) frame.put("file", to.name)
                        }
                        manifest.writeText(json.toString(2))
                    }
                }
            }
            val updated = to.relativeTo(working).invariantSeparatorsPath
            val prefix = "$relative/"
            val modes = candidate.getJSONObject("modes")
            modes.keys().forEach { mode ->
                val frames = modes.getJSONObject(mode).getJSONArray("frames")
                for (i in 0 until frames.length()) {
                    val frame = frames.getJSONObject(i)
                    val before = frame.optString("res")
                    val after = when {
                        directory && before.startsWith(prefix) -> updated + "/" + before.removePrefix(prefix)
                        !directory && before == relative -> updated
                        else -> before
                    }
                    if (after != before) frame.put("res", after)
                }
            }
        }
        images()
    }

    private fun renameDialog(relative: String, directory: Boolean) {
        val file = File(draft, relative)
        editor(if (directory) "重命名文件夹" else "重命名图片",
            compact = true, renameLayout = true,
            subtitle = if (directory) "当前文件夹 · $relative" else "当前图片 · $relative") { box ->
            val input = entry(box, "名称", if (directory) file.name else file.nameWithoutExtension, compact = true)
            val save: () -> Unit = {
                renameResource(relative, input.text.toString().trim(), directory)
            }
            save
        }
    }

    private fun deleteResourceFolder(relative: String) {
        val folder = File(draft, relative).canonicalFile
        require(folder.isDirectory && folder.toPath().startsWith(draft.canonicalFile.toPath()) &&
            folder != draft.canonicalFile) { "无效文件夹" }
        val used = imageUses().keys
        require(folder.walkTopDown().filter(::isImage).none {
            it.relativeTo(draft).invariantSeparatorsPath in used
        }) { "文件夹中有正在使用的图片，无法删除" }
        resourceChange { working, _ ->
            val target = File(working, relative).canonicalFile
            require(target.isDirectory && target.toPath().startsWith(working.canonicalFile.toPath())) {
                "无效文件夹"
            }
            check(target.deleteRecursively()) { "删除文件夹失败" }
        }
        expandedResourceFolders.remove(relative)
        images()
    }

    private fun showResourceFolderOptions(anchor: View, relative: String) {
        UnifiedOptionMenu.show(anchor, listOf("重命名", "删除"), setOf("删除")) { choice ->
            when (choice) {
                "重命名" -> renameDialog(relative, true)
                "删除" -> showConfirmDialog(
                    title = "删除文件夹",
                    bodyHtml = "确认删除「${File(relative).name}」及其中未使用的资源？",
                    positiveText = "删除"
                ) { attempt { deleteResourceFolder(relative) } }
            }
        }
    }

    private fun deleteImage(relative: String) {
        require(imageUses()[relative].orEmpty().isEmpty()) { "仍有动画引用，不能删除" }
        resourceChange { working, _ ->
            val file = File(working, relative)
            require(isImage(file))
            check(file.delete()) { "删除失败" }
        }
        images()
    }

    private fun imageDetails(relative: String) {
        val uses = imageUses()[relative].orEmpty()
        var popup: android.app.Dialog? = null
        popup = editor("图片详情", compact = true, readOnly = true,
            subtitle = File(relative).name, extraAction = {
            popup?.dismiss()
            renameDialog(relative, false)
        }) { box ->
            val previewCard = dialogSection()
            previewCard.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundResource(R.drawable.bg_thumb_border)
                setImageBitmap(thumb(File(draft, relative), 400))
            }, LinearLayout.LayoutParams(-1, px(200)))
            previewCard.addView(text(relative, 12f).apply {
                setTextColor(getColor(R.color.cp_note))
                setPadding(px(2), px(6), px(2), px(2))
            })
            addDetailSection(box, previewCard)

            val references = dialogSection()
            val summary = if (uses.isEmpty()) "未使用"
                else "${uses.map { it.first }.distinct().size} 个动画 · ${uses.size} 帧"
            references.addView(detailSectionHeader("被引用位置", summary))
            if (uses.isEmpty()) references.addView(text("未使用", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
            })
            uses.forEachIndexed { number, (mode, index) ->
                val row = detailListRow()
                row.addView(detailNumber(number))
                row.addView(text("动画 · $mode / 第 ${index + 1} 帧", 12f).apply {
                    setPadding(0, px(3), 0, px(3))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 18f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.cp_note))
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(px(22), -2))
                rippleClick(row) { openModeFromDetail(mode) }
                references.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(8)
                    topMargin = px(4)
                })
            }
            addDetailSection(box, references)
            ({ })
        }
    }

    private fun actionPairForDialog(parent: LinearLayout, left: String,
                                    onLeft: () -> Unit, right: String, onRight: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, px(10), 0, 0)
        }
        row.addView(dialogButton(left, primary = false, click = onLeft), LinearLayout.LayoutParams(-2, -2).apply {
            rightMargin = px(5)
        })
        row.addView(dialogButton(right, primary = true, click = onRight), LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = px(5)
        })
        parent.addView(row)
    }

    private fun imageOptions(anchor: View, relative: String) {
        val users = imageUses()[relative].orEmpty()
        UnifiedOptionMenu.show(anchor, listOf("大图及引用详情", "重命名", "替换图片", "删除"), setOf("删除")) { selected ->
            when (selected) {
                "大图及引用详情" -> imageDetails(relative)
                "重命名" -> renameDialog(relative, false)
                "替换图片" -> {
                    resourceReplacePath = relative
                    resourcePicker.launch(arrayOf("image/*"))
                }
                "删除" -> if (users.isNotEmpty()) {
                    showConfirmDialog(
                        title = "图片正在使用",
                        bodyHtml = "该图片被 ${users.size} 帧引用，不能直接删除。",
                        positiveText = "知道了"
                    ) { }
                } else showConfirmDialog(
                    title = "删除图片",
                    bodyHtml = "确定删除「${File(relative).name}」？",
                    positiveText = "删除",
                    titleColorRes = R.color.cp_danger
                ) { attempt { deleteImage(relative) } }
            }
        }
    }

    private fun resourceBranch(directory: File, level: Int,
                               uses: Map<String, List<Pair<String, Int>>>,
                               target: LinearLayout = panel) {
        directory.listFiles().orEmpty()
            .filter { isImage(it) || it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { file ->
                val path = file.relativeTo(draft).invariantSeparatorsPath
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    selectionSurface(this, isFolder = file.isDirectory)
                    setPadding(px(8), px(4), px(4), px(4))
                }
                val main = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = false
                }
                if (file.isDirectory) {
                    main.addView(text(if (path in expandedResourceFolders) "▾ 📂" else "▸ 📁", 15f),
                        LinearLayout.LayoutParams(px(54), -2))
                } else {
                    main.addView(ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(thumb(file, 96))
                    }, LinearLayout.LayoutParams(px(52), px(52)))
                }
                val words = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(8), 0, 0, 0)
                }
                words.addView(text(file.name, 14f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, 0)
                })
                val subtitle = if (file.isDirectory) {
                    "${file.walkTopDown().count { isImage(it) }} 张图片"
                } else {
                    val refs = uses[path].orEmpty()
                    if (refs.isEmpty()) "未使用"
                    else "${refs.map { it.first }.distinct().take(2).joinToString("、")} · ${refs.size} 帧引用"
                }
                words.addView(text(subtitle, 11f).apply {
                    setTextColor(getColor(R.color.cp_note))
                    setPadding(0, 0, 0, 0)
                    maxLines = 2
                })
                main.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
                val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                var populated = false
                fun refreshResourceFolder() {
                    if (file.isDirectory) {
                        val opened = path in expandedResourceFolders
                        (main.getChildAt(0) as TextView).text =
                            if (opened) "▾ 📂" else "▸ 📁"
                        if (opened && !populated) {
                            populated = true
                            resourceBranch(file, level + 1, uses, children)
                        }
                        children.visibility = if (opened) View.VISIBLE else View.GONE
                    }
                }
                row.setOnClickListener {
                    if (file.isDirectory) {
                        if (!expandedResourceFolders.add(path)) expandedResourceFolders.remove(path)
                        refreshResourceFolder()
                    } else imageDetails(path)
                }
                if (file.isDirectory) {
                    row.addView(text("+", 22f).apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        setOnClickListener { anchor ->
                            startUpload(false, path, "library")
                        }
                    }, LinearLayout.LayoutParams(px(36), px(48)))
                }
                row.addView(text("⋮", 23f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { anchor ->
                        if (file.isDirectory) showResourceFolderOptions(anchor, path)
                        else imageOptions(anchor, path)
                    }
                }, LinearLayout.LayoutParams(px(40), px(48)))
                target.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px((level * 12).coerceAtMost(48))
                    topMargin = px(4)
                })
                if (file.isDirectory) {
                    target.addView(children, LinearLayout.LayoutParams(-1, -2))
                    refreshResourceFolder()
                }
            }
    }


    // ===== 碎碎念资源与引用编辑 =====
    private fun jsonStrings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.distinct()

    private fun thinkingObject(): JSONObject =
        root.optJSONObject("thinkings") ?: JSONObject()

    private fun thinkingBody(id: String): String {
        val raw = thinkingObject().opt(id)
        return if (raw is JSONObject) raw.optString("text") else (raw as? String).orEmpty()
    }

    private fun thinkingGroups(id: String): List<String> =
        jsonStrings(thinkingObject().optJSONObject(id)?.optJSONArray("groups"))

    private fun allThinkingGroups(): List<String> =
        thinkingObject().keys().asSequence().toList().flatMap(::thinkingGroups).distinct().sorted()

    private fun allThinkingIds(): List<String> =
        thinkingObject().keys().asSequence().toList().filter { thinkingBody(it).isNotBlank() }.sorted()

    private data class ThinkingReference(val functionPath: List<String>? = null, val modeId: String? = null) {
        fun label(functionName: (List<String>) -> String): String =
            if (functionPath != null) "功能 · ${functionName(functionPath)}" else "动画 · ${modeId.orEmpty()}"
    }
    private fun thinkingUses(id: String): List<ThinkingReference> {
        val uses = mutableListOf<ThinkingReference>()
        fun check(node: JSONObject, path: List<String>) {
            val pool = node.optJSONObject("thinking")?.opt("pool")
            val referenced = when (pool) {
                is JSONObject -> pool.has(id)
                is JSONArray -> jsonStrings(pool).contains(id)
                else -> false
            }
            if (referenced && node.optJSONArray("modes") != null) uses += ThinkingReference(functionPath = path)
            val keys = node.keys().asSequence().toList()
            keys.filter { it != "thinking" && it != "modes" }.forEach { key ->
                node.optJSONObject(key)?.let { check(it, path + key) }
            }
        }
        root.optJSONObject("functions")?.let { check(it, emptyList()) }
        root.optJSONObject("modes")?.let { modes ->
            modes.keys().asSequence().toList().forEach { key ->
                val pool = modes.optJSONObject(key)?.optJSONObject("thinking")?.opt("pool")
                if ((pool is JSONObject && pool.has(id)) ||
                    (pool is JSONArray && id in jsonStrings(pool))) uses += ThinkingReference(modeId = key)
            }
        }
        return uses
    }

    private fun thinkingResources() {
        section("碎碎念文本", "thinkings")
        panel.addView(text("管理角色的底部消息文本。\n点击“+”创建文本；点击“文本卡片”进行编辑。", 12f).apply {
            setTextColor(getColor(R.color.cp_note))
        })
        action("＋ 新建碎碎念") { thinkingDetails(null) }
        val grouped = linkedMapOf<String, MutableList<String>>()
        grouped["全部"] = allThinkingIds().toMutableList()
        allThinkingGroups().filter { it != "全部" }.forEach { grouped[it] = mutableListOf() }
        allThinkingIds().forEach { id ->
            val groups = thinkingGroups(id)
            if (groups.isEmpty()) grouped.getOrPut("未分组") { mutableListOf() }.add(id)
            else groups.forEach { grouped.getOrPut(it) { mutableListOf() }.add(id) }
        }
        for ((group, ids) in grouped) {
            val opened = expandedThinkingGroups.contains(group)
            // Match the action layout already used by animation group headers.
            val folder = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                rippleCard(this)
                setPadding(px(12), px(6), px(12), px(6))
            }
            folder.addView(text((if (opened) "▾ 📂  " else "▸ 📁  ") +
                "$group（${ids.size}）", 15f), LinearLayout.LayoutParams(0, -2, 1f))
            if (group != "全部") folder.addView(text("+", 23f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = if (group == "未分组") "新建未分组碎碎念" else "在${group}中新建碎碎念"
                setOnClickListener {
                    thinkingDetails(null, group.takeUnless { it == "未分组" })
                }
            }, LinearLayout.LayoutParams(px(38), px(44)))
            if (group != "全部" && group != "未分组")
                folder.addView(text("⋮", 23f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    contentDescription = "$group 分组操作"
                    setOnClickListener { anchor -> showThinkingGroupOptions(anchor, group) }
                }, LinearLayout.LayoutParams(px(38), px(44)))
            panel.addView(folder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            folder.setOnClickListener {
                if (!expandedThinkingGroups.add(group)) expandedThinkingGroups.remove(group)
                thinkingResources()
            }
            if (opened) for (id in ids) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    rippleCard(this)
                    setPadding(px(12), px(4), px(6), px(4))
                }
                val textContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textContainer.addView(text(thinkingBody(id), 14f).apply {
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                })
                val references = thinkingUses(id).size
                textContainer.addView(text(if (references == 0) "未被引用" else "${references}次引用", 11f).apply {
                    setTextColor(getColor(R.color.cp_note))
                    setPadding(0, px(2), 0, 0)
                })
                row.addView(textContainer, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("⋮", 23f).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { view ->
                        UnifiedOptionMenu.show(view, listOf("详情 / 编辑", "选择碎碎念组", "删除"),
                            setOf("删除")) { action ->
                            when (action) {
                                "详情 / 编辑" -> thinkingDetails(id)
                                "选择碎碎念组" -> chooseThinkingGroups(thinkingGroups(id)) { selected ->
                                    if (selected != thinkingGroups(id)) {
                                        change { json ->
                                            val texts = json.getJSONObject("thinkings")
                                            val item = texts.optJSONObject(id) ?: JSONObject().apply {
                                                put("text", thinkingBody(id))
                                            }.also { texts.put(id, it) }
                                            item.put("groups", JSONArray().apply {
                                                selected.distinct().forEach { put(it) }
                                            })
                                        }
                                        thinkingResources()
                                    }
                                }
                                "删除" -> confirmThinkingDelete(id)
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(px(42), px(42)))
                rippleClick(row) { thinkingDetails(id) }
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(12)
                    topMargin = px(4)
                })
            }
        }
    }
    private val expandedThinkingGroups = mutableSetOf<String>()

    /** Reuse the animation-group menu semantics; membership is stored on each text. */
    private fun showThinkingGroupOptions(anchor: View, group: String) {
        if (group == "全部" || group == "未分组") return
        UnifiedOptionMenu.show(anchor, listOf("重命名", "解散"), setOf("解散")) { choice ->
            when (choice) {
                "重命名" -> editor("重命名碎碎念组", compact = true,
                    subtitle = "当前分组 · $group", renameLayout = true) { box ->
                    val field = entry(box, "碎碎念组名称", group, compact = true)
                    ({
                        val target = field.text.toString().trim()
                        require(target.isNotEmpty() && target.length <= 40 &&
                            target != "全部" && target != "未分组" &&
                            target.none { ch -> ch == '/' || ch == '\\' || ch == '\n' || ch == '\r' }) {
                            "碎碎念组名称不合法"
                        }
                        require(target == group || target !in allThinkingGroups()) {
                            "碎碎念组已存在"
                        }
                        if (target != group) {
                            change { json ->
                                val texts = json.getJSONObject("thinkings")
                                texts.keys().asSequence().toList().forEach { id ->
                                    val item = texts.optJSONObject(id) ?: return@forEach
                                    val groups = item.optJSONArray("groups") ?: return@forEach
                                    for (index in 0 until groups.length()) {
                                        if (groups.optString(index) == group) groups.put(index, target)
                                    }
                                }
                            }
                            if (expandedThinkingGroups.remove(group))
                                expandedThinkingGroups.add(target)
                            thinkingResources()
                        }
                    })
                }
                "解散" -> showConfirmDialog(
                    title = "解散碎碎念组",
                    bodyHtml = "确认解散「${android.text.TextUtils.htmlEncode(group)}」？组内碎碎念不会删除，已存在的功能和动画引用不会改变。",
                    positiveText = "解散"
                ) {
                    attempt {
                        change { json ->
                            val texts = json.getJSONObject("thinkings")
                            texts.keys().asSequence().toList().forEach { id ->
                                val item = texts.optJSONObject(id) ?: return@forEach
                                val old = item.optJSONArray("groups") ?: return@forEach
                                val kept = JSONArray()
                                for (index in 0 until old.length()) {
                                    val name = old.optString(index)
                                    if (name != group) kept.put(name)
                                }
                                item.put("groups", kept)
                            }
                        }
                        expandedThinkingGroups.remove(group)
                        thinkingResources()
                    }
                }
            }
        }
    }

    private fun functionAnimationsAt(path: List<String>): List<String> {
        var node: Any? = root.optJSONObject("functions")
        for (part in path) node = (node as? JSONObject)?.opt(part)
        return when (node) {
            is JSONArray -> jsonStrings(node)
            is JSONObject -> jsonStrings(node.optJSONArray("modes"))
            else -> emptyList()
        }
    }

    /** A Function folder can own thinking without owning an animation pool. */
    private fun functionHasAnimationPool(path: List<String>): Boolean {
        var node: Any? = root.optJSONObject("functions")
        for (part in path) node = (node as? JSONObject)?.opt(part)
        return node is JSONArray || (node is JSONObject && node.optJSONArray("modes") != null)
    }

    /** Autosave valid text with debounce, and flush before closing or navigating. */
    private fun thinkingDetails(id: String?, initialGroup: String? = null) {
        // Group-local creation prefills membership; nothing is committed until Save.
        val originalText = id?.let(::thinkingBody).orEmpty()
        val draftGroups = id?.let(::thinkingGroups)?.toMutableList()
            ?: mutableListOf<String>().apply {
                if (initialGroup != null && initialGroup != "全部" && initialGroup != "未分组")
                    add(initialGroup)
            }
        editor(if (id == null) "新建碎碎念" else "碎碎念详情",
            compact = true, subtitle = "角色底部消息文本") { box ->
            val textCard = dialogSection()
            val textField = FormInputField(this)
            val field = textField.configure("文本内容", originalText,
                placeholder = "输入碎碎念文本", multiline = true)
            textCard.addView(textField, LinearLayout.LayoutParams(-1, -2))
            addDetailSection(box, textCard)

            val groupCard = dialogSection()
            val groupInfo = text("", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
                gravity = Gravity.END
            }
            fun refreshGroups() {
                groupInfo.text = draftGroups.ifEmpty { listOf("未分组") }.joinToString("、")
            }
            refreshGroups()
            val groupHeader = detailSectionHeader("所属分组", "") {
                chooseThinkingGroups(draftGroups) { selected ->
                    draftGroups.clear()
                    draftGroups.addAll(selected)
                    refreshGroups()
                }
            }
            groupHeader.removeViewAt(1)
            val groupValue = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            groupValue.addView(groupInfo, LinearLayout.LayoutParams(0, -2, 1f))
            groupValue.addView(text("›", 17f).apply {
                setTextColor(getColor(R.color.cp_note))
                setPadding(px(4), 0, 0, 0)
            })
            groupHeader.addView(groupValue)
            groupCard.addView(groupHeader)
            addDetailSection(box, groupCard)

            val referencesCard = dialogSection()
            val refs = id?.let(::thinkingUses).orEmpty()
            referencesCard.addView(detailSectionHeader("被引用位置",
                if (refs.isEmpty()) "未被引用" else "${refs.size} 次引用"))
            refs.forEachIndexed { index, reference ->
                val row = detailListRow()
                row.addView(detailNumber(index))
                row.addView(text(reference.label(::functionPathName), 12f).apply {
                    setPadding(0, px(3), 0, px(3))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text("›", 18f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.cp_note))
                    setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(px(22), -2))
                rippleClick(row) {
                    val functionPath = reference.functionPath
                    if (functionPath != null) functionDetails(functionPath, functionAnimationsAt(functionPath))
                    else reference.modeId?.let { openModeFromDetail(it) }
                }
                referencesCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(8)
                    topMargin = px(4)
                })
            }
            addDetailSection(box, referencesCard)
            ({
                val content = field.text.toString().trim()
                require(content.isNotBlank()) { "文本内容不能为空" }
                val currentGroups = draftGroups.distinct()
                // Merge this entry only; preserve independent changes made to
                // Function/Mode data while this detail dialog was open.
                change { json ->
                    val texts = json.optJSONObject("thinkings") ?: JSONObject().also {
                        json.put("thinkings", it)
                    }
                    val key = id ?: "t_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    val item = texts.optJSONObject(key) ?: JSONObject()
                    item.put("text", content)
                    item.put("groups", JSONArray().apply { currentGroups.forEach { put(it) } })
                    texts.put(key, item)
                }
                if (screen == "thinkings") thinkingResources()
            })
        }
    }

    /** Mirror animation group selection: add candidates locally, then confirm once. */
    private fun chooseThinkingGroups(initialGroups: List<String>, onConfirm: (List<String>) -> Unit) {
        editor("选择碎碎念组", compact = true, confirmLabel = "确认") { box ->
            val groups = (allThinkingGroups() + initialGroups)
                .filter { it != "全部" && it != "未分组" }.distinct().toMutableList()
            val checked = initialGroups.toMutableSet()
            val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun redraw() {
                choices.removeAllViews()
                groups.forEach { group ->
                    choices.addView(CheckBox(this).apply {
                        text = group
                        isChecked = group in checked
                        setOnCheckedChangeListener { _, on ->
                            if (on) checked.add(group) else checked.remove(group)
                        }
                    })
                }
            }
            box.addView(choices)
            redraw()
            // A floating outline label replaces the separate heading.
            val addRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val inputField = FormInputField(this)
            val input = inputField.configure("新组名称", placeholder = "输入新组名称")
            addRow.addView(inputField, LinearLayout.LayoutParams(0, -2, 1f))
            addRow.addView(button("√") {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 40 || name == "全部" || name == "未分组" ||
                    name.any { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
                    input.error = "碎碎念组名称不合法"
                } else {
                    if (name !in groups) groups.add(name)
                    checked.add(name)
                    input.setText("")
                    redraw()
                }
            }, LinearLayout.LayoutParams(px(48), px(42)).apply { leftMargin = px(8) })
            box.addView(addRow)
            ({
                require(input.text.toString().isBlank()) { "请先点击添加新组" }
                onConfirm(groups.filter { it in checked })
            })
        }
    }

    private fun confirmThinkingDelete(id: String) {
        val uses = thinkingUses(id)
        if (uses.isNotEmpty()) {
            val references = android.text.TextUtils.htmlEncode(
                uses.joinToString("\n") { it.label(::functionPathName) }
            ).replace("\n", "<br>")
            showConfirmDialog(
                title = "无法删除：仍被引用",
                bodyHtml = references,
                positiveText = "我知道了",
                showNegative = false
            ) { }
            return
        }
        showConfirmDialog(
            title = "删除碎碎念",
            bodyHtml = "确认删除「" + android.text.TextUtils.htmlEncode(thinkingBody(id)) + "」？",
            subText = "此操作不可撤销。",
            positiveText = "删除"
        ) {
            attempt {
                change { it.getJSONObject("thinkings").remove(id) }
                thinkingResources()
            }
        }
    }

    /**
     * Only direct Function data is shown. Display paths use the existing Chinese mapping,
     * while selection and saving continue to use the canonical internal keys.
     */
    private fun functionDetails(path: List<String>, selected: List<String>) {
        if (linkedMode && linkedReadOnly) { linkedReadonlyFunction(path, selected); return }
        val all = PackThinking.parse(root).functions
        val effective = all[path] ?: ThinkingConfig()
        val thinkingSummary = when (effective.policy) {
            "off" -> "不播放"
            "hold" -> "持续 · ${effective.pool.size} 条"
            else -> "轮播 · ${effective.pool.size} 条"
        }
        var popup: android.app.Dialog? = null
        popup = editor(functionPathName(path), compact = true, readOnly = true,
            subtitle = functionDescription(path)) { box ->

            val thinkingCard = dialogSection()
            thinkingCard.addView(detailSectionHeader("碎碎念配置", thinkingSummary) {
                linkedConsent { thinkingConfigDialog(functionPath = path) }
            })
            addDetailSection(box, thinkingCard)

            val animationsCard = dialogSection()
            animationsCard.addView(detailSectionHeader(
                "当前动画", "动画链 ${selected.size} 个",
                if (functionHasAnimationPool(path)) ({
                    linkedConsent { chooseFunctions(path, selected) }
                }) else null
            ))
            val animations = root.getJSONObject("modes")
            selected.forEachIndexed { index, start ->
                val row = detailListRow()
                row.addView(detailNumber(index))
                row.addView(linkedModeChains(listOf(start), animations, "", 13f) { target ->
                    openModeFromDetail(target)
                }.apply { setPadding(0, px(2), 0, px(2)) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                animationsCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(8)
                    topMargin = px(4)
                })
            }
            if (selected.isEmpty()) animationsCard.addView(text("暂无关联动画", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
            })
            addDetailSection(box, animationsCard)
            ({ })
        }
    }

    private fun functionNode(json: JSONObject, path: List<String>): JSONObject {
        var node = json.getJSONObject("functions")
        for (key in path.dropLast(1)) node = node.getJSONObject(key)
        val last = path.last()
        val raw = node.opt(last)
        if (raw is JSONArray) {
            val converted = JSONObject().put("modes", raw)
            node.put(last, converted)
            return converted
        }
        return if (raw is JSONObject) raw else node
    }

    private fun thinkingPoolJson(config: JSONObject?): LinkedHashMap<String, Pair<Long, Long>> {
        val result = linkedMapOf<String, Pair<Long, Long>>()
        val baseMin = config?.optLong("min", DEFAULT_THINKING_MIN_MS) ?: DEFAULT_THINKING_MIN_MS
        val baseMax = config?.optLong("max", DEFAULT_THINKING_MAX_MS) ?: DEFAULT_THINKING_MAX_MS
        when (val pool = config?.opt("pool")) {
            is JSONObject -> pool.keys().asSequence().toList().forEach { key ->
                val time = pool.optJSONObject(key)
                result[key] = (time?.optLong("min", baseMin) ?: baseMin) to
                    (time?.optLong("max", baseMax) ?: baseMax)
            }
            is JSONArray -> jsonStrings(pool).forEach { result[it] = baseMin to baseMax }
        }
        return result
    }

    private fun thinkingConfigDialog(modeId: String? = null, functionPath: List<String>? = null,
                                     onSaved: () -> Unit = {}) {
        val target = if (modeId != null) root.getJSONObject("modes").getJSONObject(modeId)
            else {
                val path = requireNotNull(functionPath)
                var at = root.getJSONObject("functions")
                path.dropLast(1).forEach { key -> at = at.getJSONObject(key) }
                at.optJSONObject(path.last()) ?: JSONObject()
            }
        val old = target.optJSONObject("thinking")
        val selected = thinkingPoolJson(old)
        val variants = if (modeId != null) listOf("跟随功能", "轮播", "持续", "不播放")
            else listOf("轮播", "持续", "不播放")
        val codes = if (modeId != null) listOf("inherit", "cycle", "hold", "off")
            else listOf("cycle", "hold", "off")
        editor("碎碎念配置", compact = true,
            subtitle = if (modeId != null) "动画 · $modeId"
                else "功能 · ${functionPathName(requireNotNull(functionPath))}") { box ->
            val strategyCard = dialogSection()
            val strategyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            strategyRow.addView(text("播放方式", 14f), LinearLayout.LayoutParams(0, -2, 1f))
            val spinner = Spinner(this)
            SettingsSpinnerBinder.bind(spinner, variants,
                codes.indexOf(old?.optString("policy", if (modeId != null) "inherit" else "cycle")).coerceAtLeast(0)) { }
            strategyRow.addView(spinner, LinearLayout.LayoutParams(0, px(48), 1f))
            strategyCard.addView(strategyRow)
            addDetailSection(box, strategyCard)
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(content)
            fun redraw() {
                content.removeAllViews()
                val policy = codes[spinner.selectedItemPosition.coerceIn(codes.indices)]
                if (policy == "inherit" || policy == "off") {
                    content.addView(text(if (policy == "inherit") "使用当前功能的碎碎念设置"
                        else "保持休息状态，不选择新文本", 12f).apply {
                        gravity = Gravity.START
                        setTextColor(getColor(R.color.cp_sub))
                        setPadding(px(3), px(7), px(3), px(4))
                    })
                } else {
                    val listCard = dialogSection()
                    listCard.addView(detailSectionHeader("碎碎念文本", "${selected.size} 条") {
                        chooseThinkingPool(selected,
                            if (modeId != null) "动画 · $modeId"
                            else "功能 · ${functionPathName(requireNotNull(functionPath))}") { changed ->
                            selected.clear()
                            selected.putAll(changed)
                            redraw()
                        }
                    })
                    if (selected.isEmpty()) listCard.addView(text("尚未选择文本", 12f).apply {
                        setTextColor(getColor(R.color.cp_note))
                    })
                    selected.entries.forEachIndexed { index, (id, time) ->
                        val line = detailListRow()
                        line.addView(detailNumber(index))
                        line.addView(text(thinkingBody(id), 12f).apply {
                            setPadding(0, px(3), 0, px(3))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        line.addView(text("${time.first}–${time.second}ms", 10f).apply {
                            setTextColor(getColor(R.color.cp_note))
                            setPadding(px(4), px(3), 0, px(3))
                        })
                        listCard.addView(line, LinearLayout.LayoutParams(-1, -2).apply {
                            leftMargin = px(8)
                            topMargin = px(4)
                        })
                    }
                    addDetailSection(content, listCard)
                }
            }
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { redraw() }
            }
            redraw()
            ({
                val policy = codes[spinner.selectedItemPosition.coerceIn(codes.indices)]
                selected.forEach { (id, time) ->
                    require(time.first >= 0 && time.second >= time.first) { "时长范围不合法：${thinkingBody(id)}" }
                }
                change { json ->
                    val obj = if (modeId != null) json.getJSONObject("modes").getJSONObject(modeId)
                    else functionNode(json, requireNotNull(functionPath))
                    val pool = JSONObject()
                    selected.forEach { (key, pair) ->
                        pool.put(key, JSONObject().put("min", pair.first).put("max", pair.second))
                    }
                    obj.put("thinking", JSONObject().put("policy", policy).put("pool", pool))
                }
                onSaved()
                if (detailDialogs.none { it.isShowing }) {
                    if (modeId != null && screen == "mode") showMode(modeId)
                    else if (modeId == null && screen == "functions") functions()
                }
            })
        }
    }

    private fun chooseThinkingPool(initial: Map<String, Pair<Long, Long>>,
                                   subtitle: String,
                                   confirm: (Map<String, Pair<Long, Long>>) -> Unit) {
        val chosen = LinkedHashMap(initial)
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val (frame, body) = pickerShell(dialog, "选择碎碎念", subtitle)
        val info = text("", 12f).apply { setTextColor(getColor(R.color.cp_sub)) }
        body.addView(info)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val viewport = ScrollView(this).apply { addView(list) }
        body.addView(viewport, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .55f).toInt()))
        val opened = mutableSetOf<String>()
        fun redraw() {
            list.removeAllViews()
            info.text = "已选择 ${chosen.size} 条"
            val grouped = linkedMapOf<String, MutableList<String>>()
            allThinkingGroups().forEach { grouped[it] = mutableListOf() }
            allThinkingIds().forEach { id ->
                val groups = thinkingGroups(id)
                if (groups.isEmpty()) grouped.getOrPut("未分组") { mutableListOf() }.add(id)
                else groups.forEach { grouped.getOrPut(it) { mutableListOf() }.add(id) }
            }
            for ((group, ids) in grouped) {
                list.addView(text((if (group in opened) "▾ 📂 " else "▸ 📁 ") + "$group（${ids.size}）", 15f).apply {
                    selectionSurface(this, isFolder = true)
                    setPadding(px(12), px(6), px(8), px(6))
                    setOnClickListener {
                        if (!opened.add(group)) opened.remove(group)
                        redraw()
                    }
                }, selectionRowParams())
                if (group in opened) for (id in ids) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        selectionSurface(this)
                        setPadding(px(7), px(4), px(6), px(4))
                    }
                    val itemText = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    itemText.addView(CheckBox(this).apply {
                        text = thinkingBody(id)
                        textSize = 12f
                        isChecked = id in chosen
                        setOnCheckedChangeListener { _, enabled ->
                            if (enabled) chosen.putIfAbsent(id, DEFAULT_THINKING_MIN_MS to DEFAULT_THINKING_MAX_MS)
                            else chosen.remove(id)
                            redraw()
                        }
                    }, LinearLayout.LayoutParams(-1, -2))
                    val range = chosen[id] ?: (DEFAULT_THINKING_MIN_MS to DEFAULT_THINKING_MAX_MS)
                    itemText.addView(text("持续时间：${range.first}–${range.second}ms", 10f).apply {
                        setTextColor(getColor(R.color.cp_note))
                        setPadding(px(36), 0, 0, px(4))
                    })
                    row.addView(itemText, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(text("⋮", 23f).apply {
                        gravity = Gravity.CENTER
                        setOnClickListener {
                            if (id !in chosen) chosen[id] = DEFAULT_THINKING_MIN_MS to DEFAULT_THINKING_MAX_MS
                            editor("碎碎念设置", compact = true,
                                subtitle = "碎碎念文本 · ${thinkingBody(id).take(28)}") { box ->
                                val textCard = dialogSection()
                                val textField = FormInputField(this@RolePackEditorActivity)
                                val field = textField.configure("文本内容", thinkingBody(id),
                                    placeholder = "输入碎碎念文本",
                                    helper = "将影响全部引用位置（${thinkingUses(id).size}次引用）",
                                    multiline = true)
                                textCard.addView(textField, LinearLayout.LayoutParams(-1, -2))
                                addDetailSection(box, textCard)
                                val time = chosen[id]!!
                                val timeCard = dialogSection()
                                timeCard.addView(text("持续时间", 15f))
                                val rangeRow = LinearLayout(this@RolePackEditorActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                }
                                val minField = entry(rangeRow, "Min（毫秒）", time.first.toString(), compact = true)
                                val maxField = entry(rangeRow, "Max（毫秒）", time.second.toString(), compact = true)
                                formFieldOf(minField).layoutParams =
                                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) }
                                formFieldOf(maxField).layoutParams =
                                    LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) }
                                timeCard.addView(rangeRow)
                                addDetailSection(box, timeCard)
                                ({
                                    val updatedText = field.text.toString().trim()
                                    require(updatedText.isNotBlank()) { "文本内容不能为空" }
                                    val lo = minField.text.toString().toLong()
                                    val hi = maxField.text.toString().toLong()
                                    require(lo >= 0 && hi >= lo) { "要求 0 ≤ Min ≤ Max" }
                                    if (updatedText != thinkingBody(id)) change { json ->
                                        json.getJSONObject("thinkings").getJSONObject(id).put("text", updatedText)
                                    }
                                    chosen[id] = lo to hi
                                    redraw()
                                })
                            }
                        }
                    }, LinearLayout.LayoutParams(px(44), px(46)))
                    list.addView(row, selectionRowParams(1))
                }
            }
        }
        redraw()
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(button("取消") { dialog.dismiss() })
        actions.addView(button("确认") { confirm(chosen); dialog.dismiss() })
        body.addView(actions)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    private fun images() {
        section("图片资源", "images")
        panel.addView(text("管理角色使用的图片资源。\n点击文件夹展开，点击图片查看详情。", 12f).apply {
            setPadding(px(4), 0, px(4), px(6))
            setTextColor(getColor(R.color.cp_note))
        })
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(button("导入GIF") {
            startUpload(true, folder = "", action = "library")
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(5) })
        toolbar.addView(button("新建文件夹") {
            newResourceFolder { images() }
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { leftMargin = px(5) })
        panel.addView(toolbar, LinearLayout.LayoutParams(-1, px(44)).apply {
            topMargin = px(8)
            bottomMargin = px(8)
        })
        resourceBranch(draft, 0, imageUses())
    }
}
