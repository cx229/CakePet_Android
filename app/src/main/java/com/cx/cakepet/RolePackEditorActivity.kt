package com.cx.cakepet

import android.app.AlertDialog
import android.graphics.BitmapFactory
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
import androidx.activity.OnBackPressedCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One-package visual editor; every edit validates the whole v1.02 package before replacing it. */
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
    private var fileIndex = -1
    private var resourceReplacePath: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var animation: Runnable? = null

    private val pngPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt {
            val target = File(draft, "images").apply { mkdirs() }
            val file = File(target, UUID.randomUUID().toString() + ".png")
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 8_000_000)
            try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            val frames = JSONArray(root.getJSONObject("modes").getJSONObject(modeId).getJSONArray("frames").toString())
            if (fileIndex in 0 until frames.length()) frames.getJSONObject(fileIndex).put("res", "images/${file.name}")
            else frames.put(JSONObject().put("res", "images/${file.name}").put("delay", 120))
            change { it.getJSONObject("modes").getJSONObject(modeId).put("frames", frames) }
            showMode(modeId)
        }
    }
    private val resourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replacing = resourceReplacePath
        resourceReplacePath = null
        if (uri != null) attempt {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            try {
                require(bitmap.width > 0 && bitmap.height > 0 &&
                    bitmap.width.toLong() * bitmap.height <= 8_000_000) { "图片尺寸过大" }
                resourceChange { work, _ ->
                    val dest = if (replacing == null) {
                        File(work, "images/${UUID.randomUUID()}.png")
                    } else File(work, replacing)
                    if (replacing != null) require(isImage(dest)) { "原图片不存在" }
                    dest.parentFile?.mkdirs()
                    val format = when (dest.extension.lowercase()) {
                        "jpg", "jpeg" -> android.graphics.Bitmap.CompressFormat.JPEG
                        "webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                        else -> android.graphics.Bitmap.CompressFormat.PNG
                    }
                    dest.outputStream().use {
                        check(bitmap.compress(format, 100, it)) { "图片写入失败" }
                    }
                }
            } finally { bitmap.recycle() }
            images()
        }
    }
    private val gifPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt {
            val frames = RolePackEditing.gif(this, uri, draft, modeId)
            change { it.getJSONObject("modes").getJSONObject(modeId).put("frames", frames) }
            showMode(modeId)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id = intent.getStringExtra("role_id") ?: run { finish(); return }
        try {
            draft = RolePackEditing.draft(this, id)
            root = parseJsonc(File(draft, "modes.jsonc").readText())
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
        stopAnimation()
        if (::draft.isInitialized) draft.deleteRecursively()
        super.onDestroy()
    }
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
        scroll.post { scroll.scrollTo(0, previous.scrollY) }
    }
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
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

    private fun menuCard(title: String, english: String, click: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(px(16), px(10), px(12), px(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 16f).apply { setPadding(0, 0, 0, 0) })
        labels.addView(text(english, 12f).apply {
            setPadding(0, px(2), 0, 0)
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
        stopAnimation()
        val pageChanged = screen != dest
        if (!restoringPage && pageChanged)
            pageHistory.addLast(EditorPage(screen, modeId, scroll.scrollY))
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
    /** Match the role-settings rename dialog: a single rounded Material card, not a nested AlertDialog. */
    private fun editor(title: String, create: (LinearLayout) -> (() -> Unit)) {
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
            setPadding(px(20), px(20), px(20), px(20))
        }
        card.addView(content)
        content.addView(text(title, 18f).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, px(12))
        })
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = create(form)
        val viewport = ScrollView(this).apply {
            isFillViewport = false
            addView(form)
        }
        content.addView(viewport, LinearLayout.LayoutParams(-1, -2).apply {
            // Tall forms remain scrollable instead of exceeding the visible display.
            height = minOf(px(70 * form.childCount + 20), resources.displayMetrics.heightPixels * 2 / 3)
        })
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(12), 0, 0)
        }
        val dialog = android.app.Dialog(this, R.style.Theme_CakePet_ConfirmDialog)
        actions.addView(button("取消") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, px(44), 1f).apply { rightMargin = px(5) })
        actions.addView(button("保存") {
            runCatching(save).onSuccess { dialog.dismiss() }
                .onFailure { Toast.makeText(this, it.message ?: "输入错误", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(0, px(44), 1f).apply { leftMargin = px(5) })
        content.addView(actions)
        dialog.setContentView(frame)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -2)
    }
    private fun entry(box: LinearLayout, title: String, initial: String): EditText {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(px(12), px(7), px(12), px(7))
        }
        item.addView(text(title, 13f).apply {
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.cp_note))
        })
        val field = EditText(this).apply {
            setText(initial)
            setSingleLine(true)
            textSize = 15f
            setTextColor(getColor(R.color.cp_title))
        }
        item.addView(field, LinearLayout.LayoutParams(-1, px(44)))
        box.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(8)
        })
        return field
    }
    private fun ids(): List<String> = root.getJSONObject("modes").keys().asSequence().toList().sorted()
    private fun home() {
        section("素材包编辑", "home")
        menuCard("基本信息", "Info") { info() }
        menuCard("功能映射", "Functions") { functions() }
        menuCard("全部动作", "Modes") { modes() }
        menuCard("图片资源", "Images") { images() }
    }
    private fun info() {
        editor("基本信息") { box ->
            val name = entry(box, "名称", root.optString("name"))
            val short = entry(box, "简称", root.optString("shortName"))
            val decode = entry(box, "图片解码尺寸 decodeTarget", root.optInt("decodeTarget").toString())
            val anchor = entry(box, "逻辑尺寸 anchorRes", root.optInt("anchorRes", 128).toString())
            val list = root.getJSONArray("authors")
            val author = entry(box, "作者（用逗号分隔）",
                (0 until list.length()).joinToString(",") { list.getString(it) })
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
        section("全部动作", "modes")
        action("＋ 新建动作") { newMode() }
        val grouped = linkedMapOf<String, MutableList<String>>()
        knownGroups().forEach { grouped[it] = mutableListOf() }
        ids().forEach { key ->
            val groups = modeGroups(key)
            if (groups.isEmpty()) grouped.getOrPut("未分组") { mutableListOf() }.add(key)
            else groups.forEach { grouped.getOrPut(it) { mutableListOf() }.add(key) }
        }
        val thumbnails = mutableMapOf<String, android.graphics.Bitmap?>()
        for ((group, names) in grouped) {
            val opened = group in expandedModeGroups
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(px(12), px(6), px(12), px(6))
                isClickable = true
                setOnClickListener {
                    if (!expandedModeGroups.add(group)) expandedModeGroups.remove(group)
                    modes()
                }
            }
            header.addView(text((if (opened) "▾ 📂  " else "▸ 📁  ") + group, 16f),
                LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("${names.size} 个", 12f).apply {
                setTextColor(getColor(R.color.cp_note))
            })
            panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            if (!opened) continue
            for (key in names) {
                val mode = root.getJSONObject("modes").getJSONObject(key)
                val frames = mode.getJSONArray("frames")
                val source = frames.optJSONObject(0)?.optString("res").orEmpty()
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(px(8), px(5), px(4), px(5))
                }
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener { showMode(key) }
                }
                content.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundResource(R.drawable.bg_thumb_border)
                    setImageBitmap(thumbnails.getOrPut(source) { thumb(File(draft, source)) })
                }, LinearLayout.LayoutParams(px(54), px(54)))
                val label = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(8), 0, 0, 0)
                }
                label.addView(text(key, 16f).apply { setPadding(0, 0, 0, 0) })
                label.addView(text("${frames.length()} 帧 · ${if (mode.optBoolean("loop")) "循环" else "播放一次"}", 12f).apply {
                    setPadding(0, 0, 0, 0)
                    setTextColor(getColor(R.color.cp_note))
                })
                content.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(text("⋮", 24f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { anchor -> showModeOptions(anchor, key) }
                }, LinearLayout.LayoutParams(px(42), px(54)))
                panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px(14)
                    topMargin = px(4)
                })
            }
        }
    }

    private fun showModeOptions(anchor: View, key: String) {
        val menu = PopupMenu(this, anchor)
        listOf("复制动作", "动作组", "删除动作").forEach { menu.menu.add(it) }
        menu.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "复制动作" -> copyMode(key)
                "动作组" -> editModeGroups(key)
                "删除动作" -> deleteMode(key)
            }
            true
        }
        menu.show()
    }

    private fun editModeGroups(key: String) {
        editor("$key · 动作组") { box ->
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
            val input = entry(box, "创建新组", "")
            box.addView(button("＋ 添加并选中") {
                val name = input.text.toString().trim()
                if (name.isBlank() || name.length > 40 || name == "未分组" ||
                    name.any { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
                    input.error = "动作组名称不合法"
                } else {
                    if (name !in groups) groups.add(name)
                    selected.add(name)
                    input.setText("")
                    redraw()
                }
            }, LinearLayout.LayoutParams(-1, px(44)))
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

    private fun newMode() {
        editor("新建动作") { box ->
            val key = entry(box, "动作名称", "")
            val save: () -> Unit = {
                val name = key.text.toString().trim()
                validId(name)
                require(!root.getJSONObject("modes").has(name)) { "动作名称已存在" }
                val source = root.getJSONObject("modes").getJSONObject(ids().first()).getJSONArray("frames").getJSONObject(0).getString("res")
                change { json ->
                    json.getJSONObject("modes").put(name, JSONObject()
                        .put("loop", true)
                        .put("walkSpeed", 0).put("nextMode", JSONObject.NULL)
                        .put("duration", JSONObject.NULL).put("finishCycle", true)
                        .put("frames", JSONArray().put(JSONObject().put("res", source).put("delay", 120))))
                }
                showMode(name)
            }
            save
        }
    }
    private fun showMode(key: String) {
        modeId = key
        section("动作：$key", "mode")
        val m = root.getJSONObject("modes").getJSONObject(key)

        val preview = RoleAnimationPreview(this)
        panel.addView(preview, LinearLayout.LayoutParams(-1, -2))
        panel.addView(button("↺ 从头播放") { animate(preview, key) }, LinearLayout.LayoutParams(-1, px(44)))
        animate(preview, key)
        action("编辑动作属性") { attributes(key) }
        actionPair("＋ 添加 PNG 帧", { fileIndex = -1; pngPicker.launch(arrayOf("image/*")) },
            "导入 GIF", {
            showConfirmDialog(
                title = "GIF 转帧",
                bodyHtml = "导入 GIF 并替换当前动作的全部帧？",
                subText = "原有帧配置将被替换",
                positiveText = "继续"
            ) { gifPicker.launch(arrayOf("image/gif")) }
        })
        val frames = m.getJSONArray("frames")
        panel.addView(text("逐帧编辑 · ${frames.length()} 帧", 18f))
        val thumbs = mutableMapOf<String, android.graphics.Bitmap?>()
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val source = frame.optString("res")
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(px(8), px(4), px(4), px(4))
            }
            val contents = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { frameMenu(key, index) }
            }
            contents.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(thumbs.getOrPut(source) { thumb(File(draft, source)) })
            }, LinearLayout.LayoutParams(px(52), px(52)))
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), 0, 0, 0)
            }
            labels.addView(text("帧 $index", 15f).apply { setPadding(0, 0, 0, 0) })
            labels.addView(text("${frame.opt("delay")} ms", 12f).apply { setPadding(0, 0, 0, 0) })
            labels.addView(text(source, 10f).apply {
                setPadding(0, 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.cp_note))
            })
            contents.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(contents, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(text("⠿", 22f).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                setOnLongClickListener {
                    val data = android.content.ClipData.newPlainText("frame-index", index.toString())
                    card.startDragAndDrop(data, android.view.View.DragShadowBuilder(card), null, 0)
                    true
                }
            }, LinearLayout.LayoutParams(px(40), px(54)))
            card.setOnLongClickListener {
                val data = android.content.ClipData.newPlainText("frame-index", index.toString())
                card.startDragAndDrop(data, android.view.View.DragShadowBuilder(card), null, 0)
                true
            }
            card.setOnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED ->
                        event.clipDescription?.hasMimeType("text/plain") == true
                    android.view.DragEvent.ACTION_DROP -> {
                        val from = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
                        if (from != null && from != index) moveFrameTo(key, from, index)
                        true
                    }
                    else -> true
                }
            }
            panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = px(4)
                bottomMargin = px(2)
            })
        }
    }
    private fun animate(view: RoleAnimationPreview, key: String) {
        stopAnimation()
        val manager = ImageModeManager(this, loadPackMeta(this, id, RoleSource.FILES))
        val clip = manager.createClip(key) ?: return
        val tick = object : Runnable {
            override fun run() {
                if (screen != "mode" || modeId != key) return
                view.update(clip.currentBitmap(), manager.previewContentBounds(clip.currentResName),
                    clip.currentAnchor(), clip.currentDx, clip.currentDy, manager.currentPack.anchorRes,
                    clip.currentModeId, manager.currentPack.modeLoops[clip.currentModeId] == true)
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
    private fun attributes(key: String) {
        val mode = root.getJSONObject("modes").getJSONObject(key)
        editor("动作属性") { box ->
            val name = entry(box, "动作名称", key)
            val speed = entry(box, "移动速度 walkSpeed", mode.opt("walkSpeed").toString())
            val align = entry(box, "垂直填充方式 verticalAlign（top 或 center）",
                mode.optString("verticalAlign", "top"))
            val loop = Switch(this).apply { text = "循环播放"; isChecked = mode.optBoolean("loop", true) }
            box.addView(loop)
            val next = entry(box, "播放结束后的动作（留空=由控制器决定）",
                mode.optString("nextMode").takeUnless { it == "null" }.orEmpty())
            val previous = mode.optJSONObject("duration")
            val limited = Switch(this).apply {
                text = "设置循环播放时长（关闭=无限循环）"
                isChecked = mode.optBoolean("loop") && previous != null
            }
            box.addView(limited)
            val min = entry(box, "随机目标时长：最短（毫秒）",
                previous?.optLong("min", 3000)?.toString() ?: "3000")
            val max = entry(box, "随机目标时长：最长（毫秒）",
                previous?.optLong("max", 4000)?.toString() ?: "4000")
            val finish = Switch(this).apply {
                text = "到达目标时长后，完整播放当前轮"
                isChecked = mode.optBoolean("finishCycle", true)
            }
            box.addView(finish)
            fun refreshFields() {
                limited.isEnabled = loop.isChecked
                min.isEnabled = loop.isChecked && limited.isChecked
                max.isEnabled = loop.isChecked && limited.isChecked
                finish.isEnabled = loop.isChecked && limited.isChecked
            }
            loop.setOnCheckedChangeListener { _, _ -> refreshFields() }
            limited.setOnCheckedChangeListener { _, _ -> refreshFields() }
            refreshFields()
            val save: () -> Unit = {
                val renamed = name.text.toString().trim()
                validId(renamed)
                require(renamed == key || !root.getJSONObject("modes").has(renamed)) { "动作名称已存在" }
                val target = next.text.toString().trim()
                require(target.isEmpty() || target == key || root.getJSONObject("modes").has(target)) { "后续动作不存在" }
                val alignment = align.text.toString().trim()
                require(alignment in listOf("top", "center")) { "填充方式必须是 top 或 center" }
                val duration = if (loop.isChecked && limited.isChecked) {
                    val lo = min.text.toString().toLong()
                    val hi = max.text.toString().toLong()
                    require(lo > 0 && hi >= lo) { "时长必须为正数，最长不得小于最短" }
                    JSONObject().put("min", lo).put("max", hi)
                } else null
                change { json ->
                    val modes = json.getJSONObject("modes")
                    val m = modes.getJSONObject(key)
                    m.put("walkSpeed", speed.text.toString().toDouble())
                    m.put("verticalAlign", alignment)
                    m.put("loop", loop.isChecked)
                    m.put("nextMode", if (target.isEmpty()) JSONObject.NULL else target)
                    if (loop.isChecked) {
                        m.put("duration", duration ?: JSONObject.NULL)
                        m.put("finishCycle", finish.isChecked)
                    } else {
                        m.remove("duration")
                        m.remove("finishCycle")
                    }
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
    private fun frameMenu(key: String, index: Int) {
        AlertDialog.Builder(this).setTitle("第 $index 帧")
            .setItems(arrayOf("编辑参数", "导入图片替换", "选择素材库图片", "删除帧")) { _, selection ->
                when (selection) {
                    0 -> editFrame(key, index)
                    1 -> { fileIndex = index; pngPicker.launch(arrayOf("image/*")) }
                    2 -> selectExistingImage(key, index)
                    3 -> attempt {
                        val old = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                        require(old.length() > 1) { "至少保留一帧" }
                        val newer = remapFrames(old, (0 until old.length()).filter { it != index })
                        change { it.getJSONObject("modes").getJSONObject(key).put("frames", newer) }
                        showMode(key)
                    }
                }
            }.show()
    }
    private fun selectExistingImage(key: String, index: Int) {
        val files = draft.walkTopDown().filter {
            it.isFile && it.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg")
        }.map { it.relativeTo(draft).invariantSeparatorsPath }.toList().sorted()
        if (files.isEmpty()) {
            Toast.makeText(this, "素材库没有图片", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("选择已有图片")
            .setItems(files.toTypedArray()) { _, selected -> attempt {
                change {
                    it.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
                        .getJSONObject(index).put("res", files[selected])
                }
                showMode(key)
            }}.show()
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
            val save: () -> Unit = {
                val delays = delay.text.toString().split(",").map { it.trim().toLong() }
                require(delays.size in 1..2 && delays[0] > 0 && (delays.size == 1 || delays[1] >= delays[0]))
                change { json ->
                    val frame = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").getJSONObject(index)
                    frame.put("delay", if (delays.size == 1) delays[0] else JSONArray().put(delays[0]).put(delays[1]))
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
    private fun moveFrameTo(key: String, from: Int, to: Int) = attempt {
        val frames = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
        if (from !in 0 until frames.length() || to !in 0 until frames.length()) return@attempt
        val order = (0 until frames.length()).toMutableList()
        order.add(to, order.removeAt(from))
        val reordered = remapFrames(frames, order)
        change { it.getJSONObject("modes").getJSONObject(key).put("frames", reordered) }
        showMode(key)
    }
    private fun copyMode(key: String) {
        editor("复制动作") { box ->
            val name = entry(box, "新动作名称", key + "副本")
            val save: () -> Unit = {
                val target = name.text.toString().trim()
                validId(target)
                require(!root.getJSONObject("modes").has(target)) { "动作名称已存在，请更换名称" }
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
            "动作名称不能为空、不能超过 40 字，且不能包含斜杠或换行"
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
            title = "删除动作",
            bodyHtml = "确定删除「$key」？",
            subText = "仍被功能或其他动作引用时无法删除",
            positiveText = "删除",
            titleColorRes = R.color.cp_danger
        ) {
            attempt {
                require(ids().size > 1) { "至少保留一个动作" }
                change { it.getJSONObject("modes").remove(key) }
                modes()
            }
        }
    }
    /** Only the UI is translated. Engine function path keys remain the canonical English API. */
    private fun functionSegment(path: List<String>): String {
        return when (path.joinToString("/")) {
            "snap/pat_head/pat_head", "pat_head/pat_head" -> "摸头动作"
            "snap/feed/eat" -> "吸附进食"
            "feed/eat" -> "吃鱼动作"
            "feed/food" -> "食物动画"
            else -> when (path.last()) {
                "static" -> "静止"
                "standby" -> "待机"
                "touch" -> "触摸"
                "snap" -> "吸附"
                "climb" -> "攀爬"
                "pat_head" -> "摸头"
                "feed" -> "投喂"
                "menu" -> "菜单动作"
                "pool" -> "动作池"
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

    private fun functionItem(node: LinearLayout, title: String, subtitle: String,
                             level: Int, click: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(px(12), px(7), px(12), px(7))
            isClickable = true
            setOnClickListener { click() }
        }
        val titleText = text(title, 16f).apply {
            setPadding(px(2), px(2), px(2), px(2))
        }
        card.addView(titleText, LinearLayout.LayoutParams(-1, -2))
        if (subtitle.isNotBlank()) {
            val description = text(subtitle, 13f).apply {
                setTextColor(getColor(R.color.cp_note))
            }
            card.addView(description, LinearLayout.LayoutParams(-1, -2))
        }
        val indent = px((level * 12).coerceAtMost(48))
        val params = LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = indent
            topMargin = px(4)
            bottomMargin = px(4)
        }
        node.addView(card, params)
    }

    private fun functionTree(parent: LinearLayout, values: JSONObject, path: List<String>) {
        val keys = values.keys().asSequence().toList()
        for (key in keys) {
            val currentPath = path + key
            val fullKey = currentPath.joinToString("/")
            val displayName = functionSegment(currentPath)
            when (val child = values.opt(key)) {
                is JSONObject -> {
                    val opened = fullKey in openedFunctionFolders
                    functionItem(parent, (if (opened) "▾ 📂  " else "▸ 📁  ") + displayName,
                        "", path.size) {
                        if (opened) openedFunctionFolders.remove(fullKey)
                        else openedFunctionFolders.add(fullKey)
                        functions()
                    }
                    if (opened) functionTree(parent, child, currentPath)
                }
                is JSONArray -> {
                    val selected = (0 until child.length()).map { child.optString(it) }
                        .filter { it.isNotBlank() }
                    functionItem(parent, "▣  " + displayName + "   ›",
                        "动作：" + selected.joinToString("、"), path.size) {
                        chooseFunctions(currentPath, selected)
                    }
                }
            }
        }
    }

    private fun functions() {
        section("功能映射", "functions")
        functionTree(panel, root.getJSONObject("functions"), emptyList())
    }
    private fun chooseFunctions(path: List<String>, chosen: List<String>) {
        val keys = ids()
        val checked = BooleanArray(keys.size) { keys[it] in chosen }
        AlertDialog.Builder(this).setTitle(functionPathName(path))
            .setMultiChoiceItems(keys.toTypedArray(), checked) { _, index, selected -> checked[index] = selected }
            .setNegativeButton("取消", null).setPositiveButton("完成") { _, _ -> attempt {
                val selected = keys.filterIndexed { index, _ -> checked[index] }
                require(selected.isNotEmpty()) { "至少选择一个动作" }
                change { json ->
                    var at = json.getJSONObject("functions")
                    path.dropLast(1).forEach { at = at.getJSONObject(it) }
                    at.put(path.last(), JSONArray(selected))
                }
                functions()
            }}.show()
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

    private fun deleteImage(relative: String) {
        require(imageUses()[relative].orEmpty().isEmpty()) { "仍有动作引用，不能删除" }
        resourceChange { working, _ ->
            val file = File(working, relative)
            require(isImage(file))
            check(file.delete()) { "删除失败" }
        }
        images()
    }

    private fun imageDetails(relative: String) {
        val uses = imageUses()[relative].orEmpty()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(10), px(16), px(10))
        }
        content.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_thumb_border)
            setImageBitmap(thumb(File(draft, relative), 400))
        }, LinearLayout.LayoutParams(-1, px(210)))
        content.addView(text(relative, 12f))
        content.addView(text(if (uses.isEmpty()) "未使用"
            else "被 ${uses.map { it.first }.distinct().size} 个动作 / ${uses.size} 帧引用", 13f))
        val dialog = AlertDialog.Builder(this).setTitle(File(relative).name)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null)
            .setPositiveButton("重命名") { _, _ -> renameDialog(relative, false) }
            .create()
        uses.forEach { (mode, index) ->
            content.addView(text("$mode / 帧 $index  ›", 14f).apply {
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    showMode(mode)
                }
            })
        }
        dialog.show()
    }

    private fun imageOptions(relative: String) {
        val users = imageUses()[relative].orEmpty()
        AlertDialog.Builder(this).setTitle(File(relative).name)
            .setItems(arrayOf("大图及引用详情", "重命名", "替换图片", "删除")) { _, option ->
                when (option) {
                    0 -> imageDetails(relative)
                    1 -> renameDialog(relative, false)
                    2 -> {
                        resourceReplacePath = relative
                        resourcePicker.launch(arrayOf("image/*"))
                    }
                    3 -> if (users.isNotEmpty()) {
                        AlertDialog.Builder(this).setMessage("被 ${users.size} 帧引用，请先更换引用。")
                            .setPositiveButton("知道了", null).show()
                    } else AlertDialog.Builder(this).setTitle("确认删除？")
                        .setMessage(relative)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> attempt { deleteImage(relative) } }
                        .show()
                }
            }.show()
    }

    private fun resourceBranch(directory: File, level: Int,
                               uses: Map<String, List<Pair<String, Int>>>) {
        directory.listFiles().orEmpty()
            .filter { isImage(it) || it.isDirectory && it.walkTopDown().any { item -> isImage(item) } }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { file ->
                val path = file.relativeTo(draft).invariantSeparatorsPath
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(px(8), px(4), px(4), px(4))
                }
                val main = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener {
                        if (file.isDirectory) {
                            if (!expandedResourceFolders.add(path)) expandedResourceFolders.remove(path)
                            images()
                        } else imageDetails(path)
                    }
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
                row.addView(text("⋮", 23f).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        if (file.isDirectory) {
                            renameDialog(path, true)
                        } else imageOptions(path)
                    }
                }, LinearLayout.LayoutParams(px(40), px(48)))
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    leftMargin = px((level * 12).coerceAtMost(48))
                    topMargin = px(4)
                })
                if (file.isDirectory && path in expandedResourceFolders)
                    resourceBranch(file, level + 1, uses)
            }
    }

    private fun images() {
        section("图片资源", "images")
        action("＋ 导入图片到素材库") { resourcePicker.launch(arrayOf("image/*")) }
        panel.addView(text("点击文件夹展开；点击图片查看大图与引用；右侧 ⋮ 可重命名、替换或安全删除。", 12f))
        resourceBranch(draft, 0, imageUses())
    }
}
