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
class RolePackEditorActivity : AppCompatActivity() {
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
    private val openedFunctionFolders = mutableSetOf<String>()
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
        scroll = findViewById(R.id.editor_scroll)
        panel = findViewById(R.id.editor_content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        home()
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
    private fun change(edit: (JSONObject) -> Unit) {
        val candidate = JSONObject(root.toString())
        edit(candidate)
        RolePackEditing.commit(this, id, draft, candidate)
        root = candidate
        draft = RolePackEditing.draft(this, id)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }
    /**
     * Use the EXACT role-rename XML shell (title, card, margins, and actual
     * Material buttons), not a programmatic approximation of its appearance.
     * Only the variable body area is replaced by this editor's form.
     */
    private fun editor(title: String, compact: Boolean = false, create: (LinearLayout) -> (() -> Unit)) {
        val frame = layoutInflater.inflate(R.layout.dialog_rename_role, null)
        frame.findViewById<TextView>(R.id.tv_title).text = title
        val card = (frame as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        val content = card.getChildAt(0) as LinearLayout
        // Remove the rename-specific name/short label+field pairs.
        repeat(4) { content.removeViewAt(1) }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = create(form)
        val viewport = ScrollView(this).apply {
            isFillViewport = false
            addView(form)
        }
        content.addView(viewport, 1, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = px(if (compact) 12 else 20)
        })
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        frame.findViewById<View>(R.id.btn_negative).setOnClickListener { dialog.dismiss() }
        frame.findViewById<TextView>(R.id.btn_positive).apply {
            text = "保存"
            setOnClickListener {
                runCatching(save).onSuccess { dialog.dismiss() }
                    .onFailure { Toast.makeText(this@RolePackEditorActivity,
                        it.message ?: "输入错误", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
        // Keep save/cancel fixed while the form scrolls with keyboard and
        // conditional fields (such as timed playback) independently.
        fun resizeForm() {
            if (!dialog.isShowing || viewport.width <= 0) return
            val maxHeight = (resources.displayMetrics.heightPixels * 0.65f).toInt()
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
    }
    private fun optionsDialog(title: String, items: List<String>, choose: (Int) -> Unit) {
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(px(24), 0, px(24), 0)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = px(28).toFloat()
            cardElevation = px(6).toFloat()
            setCardBackgroundColor(getColor(R.color.cp_card_bg))
        }
        frame.addView(card, android.widget.FrameLayout.LayoutParams(-1, -2))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(18))
        }
        card.addView(content)
        content.addView(text(title, 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        items.forEachIndexed { index, option ->
            list.addView(text(option, 15f).apply {
                setBackgroundResource(R.drawable.card_bg)
                setPadding(px(14), px(12), px(14), px(12))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    choose(index)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(5) })
        }
        content.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1,
                minOf(px(56 * items.size + 10), resources.displayMetrics.heightPixels / 2)))
        content.addView(dialogButton("关闭", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, px(42)).apply { topMargin = px(10) })
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }

    private fun entry(box: LinearLayout, title: String, initial: String, compact: Boolean = false): EditText {
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        item.addView(text(title, 12f).apply {
            setPadding(0, 0, 0, px(4))
            setTextColor(getColor(R.color.cp_sub))
        })
        val field = EditText(this).apply {
            setText(initial)
            setSingleLine(true)
            textSize = 15f
            setTextColor(getColor(R.color.cp_text))
            setHintTextColor(getColor(R.color.cp_sub))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(px(12), px(8), px(12), px(8))
        }
        item.addView(field, LinearLayout.LayoutParams(-1, px(if (compact) 42 else 46)))
        box.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(if (compact) 8 else 12)
        })
        return field
    }
    private fun ids(): List<String> = root.getJSONObject("modes").keys().asSequence().toList().sorted()
    private fun home() {
        section("素材包编辑", "home")
        val roleName = root.optString("name").ifBlank { "角色" }
        menuCard("基本信息", "定义了「$roleName」的身份与信息", android.R.drawable.ic_menu_info_details) { info() }
        menuCard("功能映射", "决定了「$roleName」在何时会触发什么动作动画", android.R.drawable.ic_menu_manage) { functions() }
        menuCard("动作动画", "制定了「$roleName」有什么动作动画", android.R.drawable.ic_media_play) { modes() }
        menuCard("图片资源", "列举了「$roleName」所用的图片", android.R.drawable.ic_menu_gallery) { images() }
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
        knownGroups().forEach { grouped[it] = mutableListOf() }
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
            header.addView(text("+", 23f).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { newMode(group.takeUnless { it == "未分组" }) }
            }, LinearLayout.LayoutParams(px(38), px(44)))
            if (group != "未分组") header.addView(text("⋮", 23f).apply {
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
                    setImageBitmap(thumbnails.getOrPut(source) { thumb(File(draft, source)) })
                    scaleX = if (frames.optJSONObject(0)?.optBoolean("flipX", false) == true) -1f else 1f
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
                rippleClick(card) { showMode(key) }
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
        UnifiedOptionMenu.show(anchor, listOf("复制动画", "动画组", "删除动画"),
            setOf("删除动画")) { choice ->
            when (choice) {
                "复制动画" -> copyMode(key)
                "动画组" -> editModeGroups(key)
                "删除动画" -> deleteMode(key)
            }
        }
    }

    private fun editModeGroups(key: String) {
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
            box.addView(text("创建新组", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
            })
            val addRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val input = EditText(this).apply {
                hint = "新组名称"
                setSingleLine(true)
                textSize = 14f
                setTextColor(getColor(R.color.cp_title))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(px(10), 0, px(10), 0)
            }
            addRow.addView(input, LinearLayout.LayoutParams(0, px(42), 1f))
            addRow.addView(button("√") {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 40 || name == "未分组" ||
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
                modes()
            }
            save
        }
    }

    private fun showModeGroupOptions(anchor: View, group: String) {
        UnifiedOptionMenu.show(anchor, listOf("重命名", "解散"), setOf("解散")) { choice ->
            when (choice) {
                "重命名" -> editor("重命名动画组", compact = true) { box ->
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
    private fun showMode(key: String) {
        modeId = key
        bulkSelected.clear()
        section("动画：$key", "mode")
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
                setOnClickListener { animate(preview, key) }
            }, FrameLayout.LayoutParams(px(36), px(36), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = px(8)
                bottomMargin = px(8)
            })
        }
        panel.addView(previewContainer, LinearLayout.LayoutParams(-1, -2))
        animate(preview, key)
        action("编辑动画属性") { attributes(key) }
        action("绑定到功能") { bindModeToFunctions(key) }
        panel.addView(button("导入 GIF 并覆盖") {
            showConfirmDialog(
                title = "GIF 覆盖动画",
                bodyHtml = "使用 GIF 全部帧替换此动画现有帧？",
                subText = "原有帧属性会被替换；GIF 时间延迟会被保留",
                positiveText = "继续"
            ) { startUpload(true, action = "overwrite", mode = key) }
        }, LinearLayout.LayoutParams(-1, px(44)).apply {
            topMargin = px(8)
            bottomMargin = px(8)
        })
        val frames = m.getJSONArray("frames")
        val frameHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        frameHeader.addView(text("逐帧编辑 · ${frames.length()} 帧", 18f),
            LinearLayout.LayoutParams(0, -2, 1f))
        frameHeader.addView(text("支持长按拖动排序", 11f).apply {
            setTextColor(getColor(R.color.cp_note))
        })
        panel.addView(frameHeader, LinearLayout.LayoutParams(-1, -2))
        val order = (0 until frames.length()).toMutableList()
        val thumbs = mutableMapOf<String, android.graphics.Bitmap?>()
        // The page's ONE RecyclerView scrolls both header controls and all
        // frames. No nested scrolling or wrap-content RecyclerView measurement.
        val pageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(8))
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
                frameInfo.text = delayText + (if (frame.optBoolean("flipX", false)) "  左右镜像" else "")
                val coords = mutableListOf<String>()
                if (x != "-" || y != "-") coords.add("XY($x,$y)")
                if (dx != "-" || dy != "-") coords.add("dxy($dx,$dy)")
                (labels.getChildAt(1) as TextView).apply {
                    text = coords.joinToString("  ")
                    visibility = if (coords.isEmpty()) View.GONE else View.VISIBLE
                }
                (labels.getChildAt(2) as TextView).text = source
                image.setImageBitmap(thumbs.getOrPut(source) { thumb(File(draft, source)) })
                image.scaleX = if (frame.optBoolean("flipX", false)) -1f else 1f
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
                view.update(clip.currentBitmap(), manager.previewContentBounds(clip.currentResName, clip.currentVerticalAlign),
                    clip.currentAnchor(), clip.currentDx, clip.currentDy, manager.currentPack.anchorRes,
                    clip.currentModeId, manager.currentPack.modeLoops[clip.currentModeId] == true,
                    clip.currentFrameIndex, clip.currentFlipX)
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
            val playbackModes = listOf("关闭", "定时循环", "无限循环")
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
            playbackRow.addView(text("循环播放", 14f).apply {
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
            val min = entry(rangeRow, "最短（ms）",
                previous?.optLong("min", 3000)?.toString() ?: "3000", compact = true)
            val max = entry(rangeRow, "最长（ms）",
                previous?.optLong("max", 4000)?.toString() ?: "4000", compact = true)
            (min.parent as? View)?.layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(5) }
            (max.parent as? View)?.layoutParams =
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(5) }
            timedFields.addView(rangeRow)
            val finish = Switch(this).apply {
                text = "循环结束时完整播放一轮"
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
            nextFields.addView(text("播放结束后的动画", 14f).apply {
                setPadding(0, 0, px(12), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val nextChoice = TextView(this).apply {
                text = if (nextModeId.isEmpty()) "无（由控制器决定）  ▾" else "$nextModeId  ▾"
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
                        initial = listOfNotNull(nextModeId.takeIf { it.isNotEmpty() })) { chosen ->
                        nextModeId = chosen.firstOrNull().orEmpty()
                        text = if (nextModeId.isEmpty()) "无（由控制器决定）  ▾" else "$nextModeId  ▾"
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
        val outer = FrameLayout(this).apply { setPadding(px(20), 0, px(20), 0) }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = px(26).toFloat()
            setCardBackgroundColor(getColor(R.color.cp_card_bg))
        }
        outer.addView(card, FrameLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
        }
        card.addView(body)
        body.addView(text("选择素材", 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
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
                            setBackgroundResource(R.drawable.card_bg)
                            setPadding(px(4 + depth * 10), px(3), px(3), px(3))
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
                                bottomMargin = px(4)
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
                                bottomMargin = px(4)
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

    private fun editBulkFrameProperties(key: String) {
        val frames = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
        val indices = bulkSelected.sorted()
        if (indices.isEmpty()) return
        editor("批量编辑帧属性") { box ->
            val changed = mutableSetOf<String>()
            fun initial(name: String): String {
                for (index in indices) {
                    val field = frames.getJSONObject(index)
                    val raw = if (name == "delay") {
                        when (val delay = field.opt("delay")) {
                            is JSONArray -> "${delay.optLong(0)},${delay.optLong(1)}"
                            else -> delay?.toString().orEmpty()
                        }
                    } else if (name == "verticalAlign") field.optString(name, "top")
                    else optional(field, name)
                    if (raw.isNotBlank()) return raw
                }
                return ""
            }
            val fields = linkedMapOf<String, EditText>()
            for (name in listOf("delay", "anchorX", "anchorY", "dx", "dy", "verticalAlign")) {
                val field = entry(box, name, initial(name))
                fields[name] = field
                field.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) { changed.add(name) }
                })
            }
            // Explicit opt-in to bulk change; opening the dialog never resets mixed values.
            val flipSelection = Switch(this).apply {
                text = "左右镜像（仅勾选修改此项后批量覆盖）"
                isChecked = frames.getJSONObject(indices.first()).optBoolean("flipX", false)
            }
            var flipTouched = false
            flipSelection.setOnCheckedChangeListener { _, _ -> flipTouched = true }
            box.addView(flipSelection)
            val save: () -> Unit = {
                val edits = changed.associateWith { name ->
                    val raw = fields.getValue(name).text.toString().trim()
                    if (name == "delay") {
                        val nums = raw.split(",").map { it.trim().toLong() }
                        require(nums.size in 1..2 && nums[0] > 0 &&
                            (nums.size == 1 || nums[1] >= nums[0])) { "delay 不合法" }
                        if (nums.size == 1) nums[0] else JSONArray().put(nums[0]).put(nums[1])
                    } else if (name == "verticalAlign") {
                        require(raw in listOf("top", "center")) { "填充方式必须为 top 或 center" }
                        raw
                    } else if (raw.isEmpty()) JSONObject.NULL else raw.toDouble()
                }
                if (edits.isNotEmpty() || flipTouched) {
                    change { json ->
                        val current = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                        indices.forEach { index ->
                            val frame = current.getJSONObject(index)
                            edits.forEach { (name, value) ->
                                if (value === JSONObject.NULL) frame.remove(name) else frame.put(name, value)
                            }
                            if (flipTouched) {
                                if (flipSelection.isChecked) frame.put("flipX", true)
                                else frame.remove("flipX")
                            }
                        }
                    }
                }
                bulkSelected.clear()
                showMode(key)
            }
            save
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
        val f = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").getJSONObject(index)
        editor("帧参数") { box ->
            val delay = entry(box, "delay (毫秒或 min,max)", when(val d = f.opt("delay")) {
                is JSONArray -> "${d.optLong(0)},${d.optLong(1)}"
                else -> d.toString()
            })
            val anchorX = entry(box, "anchorX（空=默认）", optional(f, "anchorX"))
            val anchorY = entry(box, "anchorY（空=默认）", optional(f, "anchorY"))
            val dx = entry(box, "dx（空=默认）", optional(f, "dx"))
            val dy = entry(box, "dy（空=默认）", optional(f, "dy"))
            val align = entry(box, "垂直填充 verticalAlign（top/center）",
                f.optString("verticalAlign", "top"))
            val flip = Switch(this).apply {
                text = "左右镜像（默认关闭）"
                isChecked = f.optBoolean("flipX", false)
            }
            box.addView(flip)
            val save: () -> Unit = {
                val delays = delay.text.toString().split(",").map { it.trim().toLong() }
                require(delays.size in 1..2 && delays[0] > 0 && (delays.size == 1 || delays[1] >= delays[0]))
                change { json ->
                    val frame = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").getJSONObject(index)
                    frame.put("delay", if (delays.size == 1) delays[0] else JSONArray().put(delays[0]).put(delays[1]))
                    val alignment = align.text.toString().trim()
                    require(alignment in listOf("top", "center")) { "填充方式必须为 top 或 center" }
                    frame.put("verticalAlign", alignment)
                    if (flip.isChecked) frame.put("flipX", true) else frame.remove("flipX")
                    listOf("anchorX" to anchorX, "anchorY" to anchorY, "dx" to dx, "dy" to dy).forEach { (name, input) ->
                        if (input.text.isBlank()) frame.remove(name)
                        else frame.put(name, input.text.toString().toDouble())
                    }
                }
                showMode(key)
            }
            save
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
            "snap/pat_head/pat_head", "pat_head/pat_head" -> "摸头动画"
            "snap/feed/eat" -> "吸附进食"
            "feed/eat" -> "吃鱼动画"
            "feed/food" -> "食物动画"
            else -> when (path.last()) {
                "static" -> "静止"
                "standby" -> "待机"
                "touch" -> "触摸"
                "snap" -> "吸附"
                "climb" -> "攀爬"
                "pat_head" -> "摸头"
                "feed" -> "投喂"
                "menu" -> "菜单动画"
                "pool" -> "动画池"
                "sit" -> "静坐"
                "roll" -> "滚动"
                "lift_up" -> "提起"
                "slow" -> "慢速"
                "left_medium" -> "向左中速"
                "left_fast" -> "向左快速"
                "right_medium" -> "向右中速"
                "right_fast" -> "向右快速"
                "jump_down" -> "落地"
                "jump_up" -> "跳起"
                "walk" -> "行走"
                "snap_head" -> "探头"
                "eat" -> "进食"
                "food" -> "食物动画"
                else -> path.last()
            }
        }
    }

    private fun functionPathName(path: List<String>): String =
        path.indices.joinToString("/") { functionSegment(path.take(it + 1)) }

    private fun functionDescription(path: List<String>): String = when (path.joinToString("/")) {
        "static/sit" -> "进入静止状态时播放的基础动画"
        "static/pool" -> "静止状态下随机选取的候选动画"
        "standby/pool" -> "角色待机时随机选取的候选动画"
        "touch/roll" -> "触摸角色触发滚动行为时播放"
        "touch/walk" -> "触摸交互触发行走行为时播放"
        "touch/jump_down" -> "触摸交互后角色落地时播放"
        "touch/lift_up/slow" -> "缓慢提起角色时播放"
        "touch/lift_up/left_medium" -> "向左中速拖动提起时播放"
        "touch/lift_up/left_fast" -> "向左快速拖动提起时播放"
        "touch/lift_up/right_medium" -> "向右中速拖动提起时播放"
        "touch/lift_up/right_fast" -> "向右快速拖动提起时播放"
        "snap/snap_head" -> "角色吸附边缘后探头时播放"
        "snap/pat_head/pat_head" -> "角色吸附状态被摸头时播放"
        "snap/feed/eat" -> "角色吸附状态接受投喂时播放"
        "climb/walk" -> "角色沿边缘攀爬移动时播放"
        "climb/jump_up" -> "角色从攀爬状态跳起时播放"
        "pat_head/pat_head" -> "正常状态下摸头互动时播放"
        "feed/eat" -> "角色接受投喂并进食时播放"
        "feed/food" -> "投喂时显示的食物动画"
        "menu/pool" -> "菜单中供手动选择的动画"
        else -> "执行该功能时，从关联动画中选择播放"
    }

    private fun functionFolderDescription(path: List<String>): String = when (path.joinToString("/")) {
        "static" -> "角色静止时的基础动画"
        "standby" -> "角色待机时的候选动画"
        "touch" -> "角色触摸与拖动时的动画"
        "touch/lift_up" -> "角色被提起时的动画"
        "snap" -> "角色贴边吸附时的动画"
        "snap/pat_head" -> "角色吸附时的摸头动画"
        "snap/feed" -> "角色吸附时的投喂动画"
        "climb" -> "角色沿边缘移动时的动画"
        "pat_head" -> "角色被摸头时的互动动画"
        "feed" -> "角色接受投喂时的动画"
        "menu" -> "菜单中可手动选择的动画"
        else -> "展开查看该分类下的功能"
    }

    private fun functionItem(node: LinearLayout, title: String, subtitle: String,
                             level: Int, immediate: Boolean = false, description: String = "",
                             click: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            rippleCard(this)
            setPadding(px(12), px(7), px(12), px(7))
            isClickable = true
            if (immediate) setOnClickListener { click() }
            else rippleClick(this) { click() }
        }
        card.addView(text(title, 16f).apply { setPadding(0, 0, 0, 0) })
        if (description.isNotBlank()) card.addView(text(description, 12f).apply {
            setPadding(0, px(2), 0, 0)
            setTextColor(getColor(R.color.cp_note))
        })
        if (subtitle.isNotBlank()) card.addView(text(subtitle, 11f).apply {
            setPadding(0, px(2), 0, 0)
            setTextColor(getColor(R.color.cp_note))
        })
        val indent = px((level * 12).coerceAtMost(48))
        val params = LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = indent
            topMargin = px(4)
            bottomMargin = px(4)
        }
        node.addView(card, params)
        return card
    }

    private fun functionTree(parent: LinearLayout, values: JSONObject, path: List<String>) {
        val keys = values.keys().asSequence().toList()
        for (key in keys) {
            val currentPath = path + key
            val fullKey = currentPath.joinToString("/")
            val displayName = functionSegment(currentPath)
            when (val child = values.opt(key)) {
                is JSONObject -> {
                    val children = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    var populated = false
                    lateinit var folderCard: LinearLayout
                    fun refreshFolder() {
                        val opened = fullKey in openedFunctionFolders
                        (folderCard.getChildAt(0) as TextView).text =
                            (if (opened) "▾ 📂  " else "▸ 📁  ") + displayName
                        if (opened && !populated) {
                            populated = true
                            functionTree(children, child, currentPath)
                        }
                        children.visibility = if (opened) View.VISIBLE else View.GONE
                    }
                    folderCard = functionItem(parent, "", functionFolderDescription(currentPath), path.size, immediate = true) {
                        if (!openedFunctionFolders.add(fullKey)) openedFunctionFolders.remove(fullKey)
                        refreshFolder()
                    }
                    parent.addView(children, LinearLayout.LayoutParams(-1, -2))
                    refreshFolder()
                }
                is JSONArray -> {
                    val selected = (0 until child.length()).map { child.optString(it) }
                        .filter { it.isNotBlank() }
                    functionItem(parent, "▣  " + functionPathName(currentPath) + "   ›",
                        "动画：" + selected.joinToString("、"), path.size,
                        description = functionDescription(currentPath)) {
                        chooseFunctions(currentPath, selected)
                    }
                }
            }
        }
    }

    private fun functions() {
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
    private fun bindModeToFunctions(modeId: String) {
        val functionsRoot = root.getJSONObject("functions")
        val leaves = linkedMapOf<String, Pair<List<String>, List<String>>>()
        fun collect(node: JSONObject, parent: List<String>) {
            node.keys().asSequence().toList().forEach { segment ->
                val path = parent + segment
                when (val value = node.opt(segment)) {
                    is JSONObject -> collect(value, path)
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
        val frame = FrameLayout(this).apply { setPadding(px(22), 0, px(22), 0) }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = px(26).toFloat()
            setCardBackgroundColor(getColor(R.color.cp_card_bg))
        }
        frame.addView(card, FrameLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
        }
        card.addView(body)
        body.addView(text("绑定到功能", 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        body.addView(text("动画：$modeId\n勾选要绑定的功能；取消勾选将解除对应绑定。", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(px(4), 0, px(4), px(10))
            setTextColor(getColor(R.color.cp_note))
        })
        val selectionInfo = text("", 12f).apply { setTextColor(getColor(R.color.cp_note)) }
        body.addView(selectionInfo)
        val listing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val viewport = ScrollView(this).apply { addView(listing) }
        body.addView(viewport, LinearLayout.LayoutParams(
            -1, (resources.displayMetrics.heightPixels * .55f).toInt()
        ))

        fun warnLastBinding(path: List<String>) {
            showConfirmDialog(
                title = "无法解除绑定",
                bodyHtml = "功能「${functionPathName(path)}」至少需要保留一个动画。",
                subText = "当前只有「$modeId」绑定到这个功能。请先为该功能添加其他动画。",
                positiveText = "我知道了"
            ) { }
        }

        lateinit var redraw: () -> Unit
        redraw = {
            val scrollPosition = viewport.scrollY
            listing.removeAllViews()
            selectionInfo.text = "已绑定 ${selected.size} 个功能"

            fun drawNode(node: JSONObject, parent: List<String>, depth: Int) {
                node.keys().asSequence().toList().forEach { segment ->
                    val path = parent + segment
                    val key = path.joinToString("/")
                    when (val child = node.opt(segment)) {
                        is JSONObject -> {
                            val descendantPaths = leaves.keys.filter { it.startsWith("$key/") }
                            val open = key in expanded
                            val folder = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setBackgroundResource(R.drawable.card_bg)
                                rippleCard(this)
                                setPadding(px(12), px(5), px(10), px(5))
                            }
                            folder.addView(text(
                                (if (open) "▾ 📂  " else "▸ 📁  ") + functionSegment(path), 15f
                            ).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                            folder.addView(text(
                                "${descendantPaths.count { it in selected }}/${descendantPaths.size}", 11f
                            ).apply { setTextColor(getColor(R.color.cp_note)) })
                            listing.addView(folder, LinearLayout.LayoutParams(-1, px(49)).apply {
                                leftMargin = px((depth * 12).coerceAtMost(48))
                                topMargin = px(4)
                            })
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
                                setBackgroundResource(R.drawable.card_bg)
                                rippleCard(this)
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
                            listing.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                                leftMargin = px(((depth + 1) * 12).coerceAtMost(60))
                                topMargin = px(3)
                            })
                            var restoringCheck = false
                            check.setOnCheckedChangeListener { _, nowChecked ->
                                if (restoringCheck) return@setOnCheckedChangeListener
                                if (!nowChecked && checked && leaves.getValue(key).second
                                        .filter { it != modeId }.isEmpty()) {
                                    // Restore without firing another selection callback.
                                    // Restore without dropping this checkbox's listener.
                                    // Suppress only the programmatic callback.
                                    restoringCheck = true
                                    try { check.isChecked = true }
                                    finally { restoringCheck = false }
                                    warnLastBinding(path)
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
                // Verify again at commit time; never clear an existing function pool.
                change { json ->
                    val functions = json.getJSONObject("functions")
                    for ((key, pair) in leaves) {
                        val (path, _) = pair
                        var node = functions
                        path.dropLast(1).forEach { node = node.getJSONObject(it) }
                        val original = node.getJSONArray(path.last())
                        val oldModes = (0 until original.length()).map { original.optString(it) }
                            .filter { it.isNotBlank() }
                        val updated = oldModes.filter { it != modeId }.toMutableList()
                        if (key in selected && modeId !in updated) updated.add(modeId)
                        require(oldModes.isEmpty() || updated.isNotEmpty()) {
                            "功能「${functionPathName(path)}」至少需要保留一个动画"
                        }
                        node.put(path.last(), JSONArray().apply { updated.distinct().forEach { put(it) } })
                    }
                }
                dialog.dismiss()
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
        val frame = FrameLayout(this).apply { setPadding(px(22), 0, px(22), 0) }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = px(26).toFloat()
            setCardBackgroundColor(getColor(R.color.cp_card_bg))
        }
        frame.addView(card, FrameLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
        }
        card.addView(body)
        body.addView(text(headerTitle ?: if (single) "选择后续动画" else "选择功能动画", 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!headerDescription.isNullOrBlank()) body.addView(text(headerDescription, 12f).apply {
            gravity = Gravity.CENTER
            setPadding(px(4), 0, px(4), px(10))
            setTextColor(getColor(R.color.cp_note))
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
                listing.addView(text("○  无（由控制器决定）", 15f).apply {
                    setBackgroundResource(R.drawable.card_bg)
                    isClickable = true
                    setOnClickListener { selected.clear(); redraw() }
                    setTextColor(getColor(if (selected.isEmpty()) R.color.cp_title else R.color.cp_note))
                }, LinearLayout.LayoutParams(-1, px(46)))
            }
            for (group in groups.distinct()) {
                val candidates = ids().filter { modeId ->
                    val memberships = modeGroups(modeId)
                    if (group == "未分组") memberships.isEmpty() else group in memberships
                }
                if (candidates.isEmpty()) continue
                listing.addView(text((if (group in expanded) "▾ 📂 " else "▸ 📁 ") +
                    "$group（${candidates.count { it in selected }}/${candidates.size}）", 15f).apply {
                    setBackgroundResource(R.drawable.card_bg)
                    isClickable = true
                    setOnClickListener { if (!expanded.add(group)) expanded.remove(group); redraw() }
                })
                if (group in expanded) {
                    for (name in candidates) {
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(px(14), 0, px(5), 0)
                        }
                        row.addView(CheckBox(this).apply {
                            text = name
                            isChecked = name in selected
                            setOnCheckedChangeListener { _, checked ->
                                if (single) selected.clear()
                                if (checked) selected.add(name) else selected.remove(name)
                                redraw()
                            }
                        }, LinearLayout.LayoutParams(-1, px(46)))
                        listing.addView(row)
                    }
                }
            }
        }
        redraw()
        if (!footerNote.isNullOrBlank()) body.addView(text(footerNote, 11f).apply {
            setPadding(px(2), px(10), px(2), px(8))
            setTextColor(getColor(R.color.cp_note))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) }
        actions.addView(dialogButton("取消", primary = false) { dialog.dismiss() },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = px(5) })
        actions.addView(dialogButton("确认", primary = true) {
            if (!single && selected.isEmpty()) {
                Toast.makeText(this, "至少选择一个动画", Toast.LENGTH_SHORT).show()
            } else {
                onConfirm(selected.toList())
                dialog.dismiss()
            }
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
            attempt {
                change { json ->
                    var node = json.getJSONObject("functions")
                    path.dropLast(1).forEach { node = node.getJSONObject(it) }
                    node.put(path.last(), JSONArray(selected))
                }
                functions()
            }
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
        editor(if (directory) "重命名文件夹" else "重命名图片") { box ->
            val input = entry(box, "名称", if (directory) file.name else file.nameWithoutExtension)
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
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        val frame = FrameLayout(this).apply { setPadding(px(24), 0, px(24), 0) }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = px(28).toFloat()
            cardElevation = px(6).toFloat()
            setCardBackgroundColor(getColor(R.color.cp_card_bg))
        }
        frame.addView(card, FrameLayout.LayoutParams(-1, -2))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        card.addView(body)
        body.addView(text(File(relative).name, 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_thumb_border)
            setImageBitmap(thumb(File(draft, relative), 400))
        }, LinearLayout.LayoutParams(-1, px(200)))
        content.addView(text(relative, 12f))
        content.addView(text(if (uses.isEmpty()) "未使用"
            else "被 ${uses.map { it.first }.distinct().size} 个动画 / ${uses.size} 帧引用", 13f))
        uses.forEach { (mode, index) ->
            content.addView(text("$mode / 帧 $index  ›", 14f).apply {
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    showMode(mode)
                }
            })
        }
        // Let short image details take their actual height, rather than guessing
        // from the number of references. Long details scroll independently while
        // the title and action buttons remain visible.
        val detailsScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        body.addView(detailsScroll, LinearLayout.LayoutParams(-1, -2))
        actionPairForDialog(body, "关闭", { dialog.dismiss() },
            "重命名", { dialog.dismiss(); renameDialog(relative, false) })
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
        detailsScroll.post {
            if (!dialog.isShowing) return@post
            val visibleFrame = android.graphics.Rect()
            dialog.window?.decorView?.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = visibleFrame.height().takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            // Keep headroom for system bars, navigation, card shadow and IME.
            val maxDialogHeight = (screenHeight * 0.85f).toInt()
            val fixedHeight = body.paddingTop + body.paddingBottom +
                body.getChildAt(0).measuredHeight + body.getChildAt(2).measuredHeight
            val contentWidth = detailsScroll.width.coerceAtLeast(1)
            content.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val desired = minOf(content.measuredHeight,
                (maxDialogHeight - fixedHeight).coerceAtLeast(px(120)))
            if (detailsScroll.layoutParams.height != desired) {
                detailsScroll.layoutParams = detailsScroll.layoutParams.apply { height = desired }
                dialog.window?.setLayout(-1, -2)
            }
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
                    setBackgroundResource(R.drawable.card_bg)
                    rippleCard(this)
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
