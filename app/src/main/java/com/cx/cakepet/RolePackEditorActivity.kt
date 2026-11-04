package com.cx.cakepet

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
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
    private var screen = "home"
    private data class EditorPage(val name: String, val mode: String)
    private val pageHistory = java.util.ArrayDeque<EditorPage>()
    private var restoringPage = false
    private val openedFunctionFolders = mutableSetOf<String>()
    private var modeId = ""
    private var fileIndex = -1
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
            else {
                val previousLast = frames.getJSONObject(frames.length() - 1)
                val previousIndex = frames.length()
                frames.put(JSONObject().put("res", "images/${file.name}").put("delay", 120).put("next", 0))
                if (previousLast.optInt("next", -1) == 0) previousLast.put("next", previousIndex)
            }
            change { it.getJSONObject("modes").getJSONObject(modeId).put("frames", frames) }
            showMode(modeId)
        }
    }
    private val resourcePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attempt {
            val folder = File(draft, "images").apply { mkdirs() }
            val file = File(folder, UUID.randomUUID().toString() + ".png")
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("无法解码图片")
            require(bitmap.width.toLong() * bitmap.height <= 8_000_000)
            try { file.outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "PNG 写入失败" }
            }} finally { bitmap.recycle() }
            change { }
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.cp_bg))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        val scroll = ScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }
        scroll.addView(panel)
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
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
    }
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }
    private fun text(value: String, size: Float = 14f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.cp_title))
        setPadding(px(4), px(8), px(4), px(8))
    }
    private fun section(title: String, dest: String) {
        stopAnimation()
        if (!restoringPage && screen != dest) pageHistory.addLast(EditorPage(screen, modeId))
        screen = dest
        panel.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹") { navigateBack() },
            LinearLayout.LayoutParams(px(56), px(48)))
        header.addView(text(title, 20f), LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(header)
    }
    private fun action(title: String, click: () -> Unit) {
        panel.addView(button(title, click), LinearLayout.LayoutParams(-1, px(52)))
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
    private fun editor(title: String, create: (LinearLayout) -> (() -> Unit)) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
        }
        val save = create(form)
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("完成", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching(save).onSuccess { dialog.dismiss() }
                    .onFailure { Toast.makeText(this, it.message ?: "输入错误", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private fun entry(box: LinearLayout, title: String, initial: String): EditText {
        box.addView(text(title))
        val v = EditText(this).apply {
            setText(initial)
            setSingleLine(true)
            setTextColor(getColor(R.color.cp_title))
        }
        box.addView(v)
        return v
    }
    private fun ids(): List<String> = root.getJSONObject("modes").keys().asSequence().toList().sorted()
    private fun home() {
        section(root.optString("name") + " · 素材包编辑", "home")
        panel.addView(text("完成一次有效操作后自动保存；预览和文件修改只作用于这个素材包。"))
        action("基本信息") { info() }
        action("全部动作 · Modes") { modes() }
        action("功能映射 · Functions") { functions() }
        action("图片资源") { images() }
    }
    private fun info() {
        editor("角色信息") { box ->
            val name = entry(box, "名称", root.optString("name"))
            val short = entry(box, "简称", root.optString("shortName"))
            val decode = entry(box, "图片解码尺寸 decodeTarget", root.optInt("decodeTarget").toString())
            val anchor = entry(box, "逻辑尺寸 anchorRes", root.optInt("anchorRes", 128).toString())
            val list = root.getJSONArray("authors")
            val author = entry(box, "作者（用逗号分隔）",
                (0 until list.length()).joinToString(",") { list.getString(it) })
            box.addView(text("内部 ID：$id（不可修改）"))
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
    private fun modes() {
        section("全部动作 · Modes", "modes")
        action("＋ 新建动作") { newMode() }
        panel.addView(text("未被功能引用的动作也可以自由保留和编辑。"))
        ids().forEach { key ->
            val m = root.getJSONObject("modes").getJSONObject(key)
            action("$key · ${m.getJSONArray("frames").length()} 帧") { showMode(key) }
        }
    }
    private fun newMode() {
        editor("新建动作") { box ->
            val key = entry(box, "动作名称", "新动作")
            val save: () -> Unit = {
                val name = key.text.toString().trim()
                validId(name)
                require(!root.getJSONObject("modes").has(name)) { "动作名称已存在" }
                val source = root.getJSONObject("modes").getJSONObject(ids().first()).getJSONArray("frames").getJSONObject(0).getString("res")
                change { json ->
                    json.getJSONObject("modes").put(name, JSONObject()
                        .put("loop", true)
                        .put("walkSpeed", 0).put("nextMode", JSONObject.NULL)
                        .put("timeNext", JSONObject().put("enabled", false))
                        .put("frames", JSONArray().put(JSONObject().put("res", source).put("delay", 120).put("next", 0))))
                }
                showMode(name)
            }
            save
        }
    }
    private fun showMode(key: String) {
        modeId = key
        section("动作 · $key", "mode")
        val m = root.getJSONObject("modes").getJSONObject(key)

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.bg_thumb_border)
        }
        panel.addView(preview, LinearLayout.LayoutParams(-1, px(180)))
        animate(preview, key)
        action("编辑动作属性") { attributes(key) }
        action("复制动作") { copyMode(key) }
        action("重命名动作") { renameMode(key) }
        action("删除动作") { deleteMode(key) }
        action("＋ 添加 PNG 帧") { fileIndex = -1; pngPicker.launch(arrayOf("image/*")) }
        action("导入 GIF，替换当前帧序列") {
            AlertDialog.Builder(this).setTitle("GIF 转帧").setMessage("GIF 将转换为 PNG 帧，替换此动作的帧序列。")
                .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ ->
                    gifPicker.launch(arrayOf("image/gif"))
                }.show()
        }
        val frames = m.getJSONArray("frames")
        panel.addView(text("逐帧编辑 · ${frames.length()} 帧", 18f))
        panel.addView(text("点击帧卡片编辑；箭头调整排序，帧编号从 0 开始。"))
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_btn_outline)
                setPadding(px(12), px(8), px(12), px(8))
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { frameMenu(key, index) }
            }
            val thumbnail = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = false
            }
            val file = File(draft, frame.optString("res"))
            if (file.isFile) thumbnail.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            content.addView(thumbnail, LinearLayout.LayoutParams(px(68), px(68)))
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), 0, px(4), 0)
            }
            details.addView(text("帧 $index", 16f))
            details.addView(text("时长：${frame.opt("delay")} ms", 13f))
            details.addView(text(frame.optString("res"), 12f).apply {
                setTextColor(getColor(R.color.cp_note))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            content.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(text("›", 22f))
            card.addView(content, LinearLayout.LayoutParams(-1, -2))
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            controls.addView(button("上移 ↑") { move(key, index, -1) })
            controls.addView(button("下移 ↓") { move(key, index, 1) })
            card.addView(controls)
            panel.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = px(5)
                bottomMargin = px(5)
            })
        }
    }
    private fun animate(view: ImageView, key: String) {
        stopAnimation()
        // Reuse the same ModeClip as the floating pet and food, not a second frame player.
        val manager = ImageModeManager(this, loadPackMeta(this, id, RoleSource.FILES))
        val clip = manager.createClip(key) ?: return
        val tick = object : Runnable {
            override fun run() {
                if (screen != "mode" || modeId != key) return
                view.setImageBitmap(clip.currentBitmap())
                val step = clip.advance(50, allowModeSwitch = true)
                if (!step.ended) handler.postDelayed(this, 50)
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
            val speed = entry(box, "walkSpeed", mode.opt("walkSpeed").toString())
            val align = entry(box, "verticalAlign (top/center)", mode.optString("verticalAlign", "top"))
            val loop = Switch(this).apply { text = "循环"; isChecked = mode.optBoolean("loop", true) }
            box.addView(loop)
            val next = entry(box, "nextMode（无则留空）", mode.optString("nextMode").takeUnless { it == "null" }.orEmpty())
            val timer = mode.optJSONObject("timeNext") ?: JSONObject()
            val enabled = Switch(this).apply { text = "启用 timeNext"; isChecked = timer.optBoolean("enabled") }
            box.addView(enabled)
            val min = entry(box, "timeNext min", timer.optLong("min", 1000).toString())
            val max = entry(box, "timeNext max", timer.optLong("max", 3000).toString())
            val prob = entry(box, "timeNext prob", timer.optDouble("prob", 1.0).toString())
            val save: () -> Unit = {
                val target = next.text.toString().trim()
                require(target.isEmpty() || root.getJSONObject("modes").has(target))
                val minMs = min.text.toString().toLong()
                val maxMs = max.text.toString().toLong()
                val probability = prob.text.toString().toDouble()
                require(minMs > 0 && maxMs >= minMs && probability in 0.0..1.0)
                change {
                    val m = it.getJSONObject("modes").getJSONObject(key)
                    m.put("walkSpeed", speed.text.toString().toDouble())
                    m.put("verticalAlign", align.text.toString()).put("loop", loop.isChecked)
                    m.put("nextMode", if (target.isEmpty()) JSONObject.NULL else target)
                    m.put("timeNext", JSONObject().put("enabled", enabled.isChecked)
                        .put("min", minMs).put("max", maxMs).put("prob", probability))
                }
                showMode(key)
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
            val next = entry(box, "next（帧索引从 0 开始）", f.optInt("next", 0).toString())
            val anchorX = entry(box, "anchorX（空=默认）", optional(f, "anchorX"))
            val anchorY = entry(box, "anchorY（空=默认）", optional(f, "anchorY"))
            val dx = entry(box, "dx（空=默认）", optional(f, "dx"))
            val dy = entry(box, "dy（空=默认）", optional(f, "dy"))
            val save: () -> Unit = {
                val delays = delay.text.toString().split(",").map { it.trim().toLong() }
                require(delays.size in 1..2 && delays[0] > 0 && (delays.size == 1 || delays[1] >= delays[0]))
                val nextIndex = next.text.toString().toInt()
                require(nextIndex in 0 until root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").length())
                change { json ->
                    val frame = json.getJSONObject("modes").getJSONObject(key).getJSONArray("frames").getJSONObject(index)
                    frame.put("delay", if (delays.size == 1) delays[0] else JSONArray().put(delays[0]).put(delays[1]))
                    frame.put("next", nextIndex)
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
    /** Preserve custom next links when rearranging or removing frames. */
    private fun remapFrames(old: JSONArray, orderedIndices: List<Int>): JSONArray {
        require(orderedIndices.isNotEmpty())
        val result = JSONArray()
        orderedIndices.forEach { index ->
            val frame = JSONObject(old.getJSONObject(index).toString())
            val originalNext = frame.opt("next")
            if (originalNext is Number) {
                val target = originalNext.toInt()
                val surviving = if (target in orderedIndices) target
                    else orderedIndices.firstOrNull { it > target } ?: orderedIndices.first()
                frame.put("next", orderedIndices.indexOf(surviving))
            }
            result.put(frame)
        }
        return result
    }
    private fun move(key: String, index: Int, delta: Int) = attempt {
        val frames = root.getJSONObject("modes").getJSONObject(key).getJSONArray("frames")
        val to = index + delta
        if (to !in 0 until frames.length()) return@attempt
        val order = (0 until frames.length()).toMutableList()
        order.add(to, order.removeAt(index))
        val changed = remapFrames(frames, order)
        change { it.getJSONObject("modes").getJSONObject(key).put("frames", changed) }
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
    private fun renameMode(key: String) {
        editor("重命名动作") { box ->
            val name = entry(box, "新动作名称", key)
            val save: () -> Unit = {
                val target = name.text.toString().trim()
                validId(target)
                require(target == key || !root.getJSONObject("modes").has(target)) { "动作名称已存在，请更换名称" }
                if (target != key) change { json ->
                    val modes = json.getJSONObject("modes")
                    val old = modes.getJSONObject(key)
                    modes.remove(key)
                    modes.put(target, old)
                    modes.keys().forEach {
                        val mode = modes.getJSONObject(it)
                        if (mode.optString("nextMode") == key) mode.put("nextMode", target)
                    }
                    substitute(json.getJSONObject("functions"), key, target)
                }
                showMode(target)
            }
            save
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
        AlertDialog.Builder(this).setTitle("删除动作 $key")
            .setMessage("仍被 Functions 或其他 Mode 引用的动作无法删除。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> attempt {
                require(ids().size > 1)
                change { it.getJSONObject("modes").remove(key) }
                modes()
            }}.show()
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
            setBackgroundResource(R.drawable.bg_btn_outline)
            setPadding(px(12), px(9), px(12), px(9))
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
                    functionItem(parent, (if (opened) "▾  " else "▸  ") + displayName,
                        if (opened) "" else "点击展开", path.size) {
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
        panel.addView(text("按目录展开功能，选择末级条目的起始动作。功能路径示例：静止/静坐。"))
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
    private fun images() {
        section("图片资源", "images")
        action("＋ 导入图片到素材库") { resourcePicker.launch(arrayOf("image/*")) }
        val used = mutableSetOf<String>()
        val modes = root.getJSONObject("modes")
        modes.keys().forEach { key ->
            val frames = modes.getJSONObject(key).getJSONArray("frames")
            for (i in 0 until frames.length()) used.add(frames.getJSONObject(i).getString("res"))
        }
        val files = draft.walkTopDown().filter {
            it.isFile && it.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg")
        }.toList()
        panel.addView(text("共 ${files.size} 张图片。使用中的图片不能直接删除。"))
        files.forEach { file ->
            val relative = file.relativeTo(draft).invariantSeparatorsPath
            action("${if (relative in used) "●" else "○"} $relative") {
                if (relative in used) AlertDialog.Builder(this).setMessage("图片正在被 Mode 引用，请先在帧编辑中替换。")
                    .setPositiveButton("知道了", null).show()
                else AlertDialog.Builder(this).setTitle("删除未使用图片？").setMessage(relative)
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        attempt {
                            check(file.delete()) { "无法删除图片" }
                            change { }
                            images()
                        }
                    }.show()
            }
        }
    }
}
