package com.cx.cakepet

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import com.cx.cakepet.databinding.ActivityMainBinding
import com.cx.cakepet.AppVersion

/**
 * 入口 Activity：浮窗权限引导 + 多角色卡片（召唤 / 归位 / 召回 / 设置）。
 * 对应 PC 端无（PC 直接运行），安卓必须做 SYSTEM_ALERT_WINDOW 引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshCards() }

    /** 读取 PetService 持久化的退出意图，避免进程重启后误把已退出的宠物复活。 */
    private fun wasExited(): Boolean {
        val prefs = getSharedPreferences("cakepet_exit", MODE_PRIVATE)
        return prefs.getBoolean("exiting", false)
    }

    /** 用户明确启动宠物时，清除先前的退出意图。 */
    private fun clearExited() {
        getSharedPreferences("cakepet_exit", MODE_PRIVATE)
            .edit().putBoolean("exiting", false).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 与设置页一致：按真实状态栏高度设置占位条，使顶栏与状态栏连成整体（三点按钮不再压在状态栏上）。
        binding.statusBarSpacer.layoutParams.height = getStatusBarHeight()
        binding.statusBarSpacer.requestLayout()
        binding.tvAuthor.text = "初心丶CX  ·  v${AppVersion.name}"
        setupHomeRecycler()
        buildRoleCards()
        setupMainMenu()
    }

    /** 首页右上角“更多”入口：复用设置页右上角浮窗逻辑（PopupMenuHelper）。 */
    private fun setupMainMenu() {
        val mainMenu = PopupMenuHelper(
            anchor = binding.btnMenu,
            popupLayoutRes = R.layout.popup_main_menu,
            items = mapOf(
                R.id.item_role_import to {
                    // 进入角色素材管理页（模板清单 + 从存储导入 + 删除等）。
                    startActivity(Intent(this, RoleManageActivity::class.java))
                },
                R.id.item_reset_software to { confirmResetSoftware() },
                R.id.item_about to {
                    // 关于页：版本/作者/角色/感谢 + 隐私协议入口（全局页，不绑定角色）
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            )
        )
        binding.btnMenu.setOnClickListener { mainMenu.show() }
    }

    override fun onResume() {
        super.onResume()
        // 注册角色在屏状态变化回调：服务异步召唤/召回后即时刷新卡片（无需重新进页面）。
        PetService.onActiveRolesChanged = {
            runOnUiThread { if (::binding.isInitialized && !isFinishing && !isDestroyed) refreshCards() }
        }
        buildRoleCards()   // 重建首页实例列表（加入新角色/重复加入后需刷新），内部末尾调用 refreshCards()
        // 恢复时启动所有预览播放器（onPause 已停止），继续“静坐一遍→随机动作”。
        previewPlayers.values.forEach { it.start() }
    }

    override fun onPause() {
        // 取消注册，避免泄漏/对已销毁页面回调。
        PetService.onActiveRolesChanged = null
        // 停止预览播放器，避免后台持续解码/刷新。
        previewPlayers.values.forEach { it.stop() }
        super.onPause()
    }

    /** 初始化首页角色列表（RecyclerView + ItemTouchHelper，与角色管理页同构）。 */
    private fun setupHomeRecycler() {
        // 页面级「+」按钮（列表之外的独立按钮，与角色素材管理页的「＋导入素材包」同级）：
        // 进入角色素材管理页挑选并添加角色。不占 adapter position，不参与拖拽与预览。
        // 由 <include> 引入，ViewBinding 生成的是 ItemAddRoleBinding，需取 .root 才是按钮本身
        binding.btnAddRole.root.setOnClickListener {
            startActivity(Intent(this, RoleManageActivity::class.java))
        }
        binding.roleList.layoutManager = LinearLayoutManager(this)
        homeAdapter = HomeAdapter()
        binding.roleList.adapter = homeAdapter
        // 与管理页一致的「长按拖动」重排：ItemTouchHelper 自动在长按卡片时发起拖拽。
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // 列表内只有角色卡片，position 即数据索引（「+」是布局中列表外的独立按钮）。
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from !in homeRoles.indices || to !in homeRoles.indices) return false
                homeAdapter.move(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            // 拖拽结束（手指抬起）时落定顺序：与角色管理页一致，仅在松手时持久化一次。
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                homeAdapter.persist()
            }
        })
        touchHelper.attachToRecyclerView(binding.roleList)
    }

    /** 为每个首页实例构建一张卡片（实例绑定素材模板，但配置按 instanceId 完全独立）。 */
    private fun buildRoleCards() {
        homeAdapter.submit(RoleStore.getHomeRoles(this))
        refreshCards()
    }

    /** 状态文字：实时圈圈（●已召唤/○未召唤）+ 真实作者署名（与素材管理页一致）。作者为空兜底「未知作者」。 */
    private fun buildStatusText(active: Boolean, authors: List<String>): String {
        val circle = if (active) "● " else "○ "
        val by = if (authors.isEmpty()) "未知作者" else "by " + authors.joinToString(", ")
        return circle + by
    }

    /** 依据当前已召唤角色刷新各卡片状态（仅更新可见项的文字/按钮，不重建列表）。 */
    private fun refreshCards() {
        val svc = PetService.instance
        for (i in homeRoles.indices) {
            // 列表内只有角色卡片，「+」不在列表里，故 position 即数据索引，无需偏移。
            val holder = binding.roleList.findViewHolderForAdapterPosition(i) as? HomeAdapter.VH ?: continue
            val inst = homeRoles[i]
            val activeHere = svc?.isRoleActive(inst.instanceId) == true
            val authors = RoleRegistry.byId(inst.templateId)?.authors ?: emptyList()
            holder.status?.text = buildStatusText(activeHere, authors)
            holder.summon?.text = if (activeHere) "归位" else "召唤"
            // 未召唤：召回 GONE → 召唤独占全宽；已召唤：归位与召回各占一半（XML 中两者 weight=1）。
            holder.recall?.visibility = if (activeHere) View.VISIBLE else View.GONE
        }
    }

    /** 首页角色列表适配器：与管理页一致，含动态预览（PreviewPlayer）与长按拖动重排。
     *  列表内【只有角色卡片】：「+」按钮是布局中位于列表之外的独立按钮（与角色素材管理页
     *  的「＋导入素材包」同级做法），不占 adapter position，故 position 即数据索引。 */
    inner class HomeAdapter : RecyclerView.Adapter<HomeAdapter.VH>() {

        override fun getItemCount(): Int = homeRoles.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_role_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val inst = homeRoles[position]
            holder.instId = inst.instanceId
            val ctx = holder.itemView.context
            // 显示实例的「已改名」名称（resolveDisplayName 先取实例改名，回落素材包名），而非模板名。
            // 仅角色卡片布局含这些视图；若取不到说明布局异常，放弃绑定该卡片。
            val name = holder.name ?: return
            val thumb = holder.thumb ?: return
            val status = holder.status ?: return
            val summon = holder.summon ?: return
            val settings = holder.settings ?: return
            val recall = holder.recall ?: return
            // 取素材模板（用于作者署名与动态预览）；可能为空，下面分别判空。
            val role = RoleRegistry.byId(inst.templateId)
            name.text = RoleStore.resolveDisplayName(ctx, inst.instanceId)
            // 绑定「是否召唤」状态文字（在视图创建时即写入，避免 RecyclerView 异步建 Holder
            // 导致 refreshCards 取不到 Holder 而漏写——这是之前状态副标题不显示的根因）。
            // 圈圈（●/○ 实时召唤指示）+ 真实作者署名「by xxx, xxx」，与素材管理页一致。
            val svc = PetService.instance
            val activeHere = svc?.isRoleActive(inst.instanceId) == true
            status.text = buildStatusText(activeHere, role?.authors ?: emptyList())
            summon.text = if (activeHere) "归位" else "召唤"
            recall.visibility = if (activeHere) View.VISIBLE else View.GONE
            // 动态预览：每实例一个播放器（静坐一遍 → 之后随机动作循环）。管理器按模板 id 共享。
            if (role != null) {
                val mgr = previewMgrCache.getOrPut(role.id) {
                    ImageModeManager(ctx, buildSpritePackForRole(ctx, role))
                }
                val player = PreviewPlayer(mgr, thumb, ctx.resources.displayMetrics.density)
                previewPlayers[inst.instanceId] = player
                thumb.setOnClickListener { player.triggerPat() }
                player.start()
            }
            val iid = inst.instanceId
            summon.setOnClickListener { onSummon(iid) }
            settings.setOnClickListener { onSettings(iid) }
            recall.setOnClickListener { onRecall(iid) }
        }

        override fun onViewRecycled(holder: VH) {
            val id = holder.instId
            if (id.isNotEmpty()) {
                previewPlayers[id]?.stop()
                previewPlayers.remove(id)
            }
        }

        /** 提交新列表（加入/重命名后刷新）。 */
        fun submit(list: List<HomeRoleInstance>) {
            homeRoles.clear()
            homeRoles.addAll(list)
            notifyDataSetChanged()
        }

        /** 拖拽重排：仅角色卡片区间（列表内无「+」卡片，position 即数据索引）。 */
        fun move(from: Int, to: Int) {
            if (from == to) return
            val it = homeRoles.removeAt(from)
            homeRoles.add(to, it)
            notifyItemMoved(from, to)
        }

        /** 顺序变化后持久化（与管理页 RoleStore.setHomeRoles 一致）。 */
        fun persist() {
            RoleStore.setHomeRoles(this@MainActivity, homeRoles.toList())
            // 顺序变化后同步（reapplyZOrder 现仅重置辅助浮层置顶，不影响宠物窗堆叠）。
            PetService.instance?.reapplyZOrder()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var instId: String = ""
            // 角色卡片布局才有这些视图；末尾「+」footer 卡片布局无这些 id，故可空。
            val thumb: ImageView? = itemView.findViewById(R.id.role_thumb)
            val name: TextView? = itemView.findViewById(R.id.role_name)
            val status: TextView? = itemView.findViewById(R.id.role_status)
            val summon: Button? = itemView.findViewById(R.id.btn_summon)
            val settings: Button? = itemView.findViewById(R.id.btn_settings)
            val recall: Button? = itemView.findViewById(R.id.btn_recall)
        }
    }

    /** 召唤/归位同一按钮：未召唤→召唤；已召唤→归位。单活跃角色（切换会先召回旧的）。 */
    private fun onSummon(instanceId: String) {
        if (!hasOverlayPermission()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayLauncher.launch(intent)
            return
        }
        clearExited()
        if (PetService.instance != null) {
            PetService.instance?.summonRole(instanceId)
        } else {
            startForegroundService(Intent(this, PetService::class.java).apply {
                putExtra(PetService.EXTRA_ROLE_ID, instanceId)
            })
        }
        refreshCards()
    }

    /** 召回（退场）：撤窗、停循环，回到未召唤；不卸载、不丢配置。 */
    private fun onRecall(instanceId: String) {
        PetService.instance?.recall(instanceId)
        refreshCards()
    }

    /** 设置：打开对应实例的专属设置页（role_id 透传 instanceId，写入 pet_config_<instanceId> / pet_bounds_<instanceId>）。 */
    private fun onSettings(instanceId: String) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra("role_id", instanceId)
        })
    }

    /** 预览图管理器缓存（按模板 id 共享，避免每次重建解析 modes.json / 重算统一画布）。 */
    private val previewMgrCache = mutableMapOf<String, ImageModeManager>()
    /** 每实例一个动态预览播放器（静坐一遍 → 之后随机模式循环）。 */
    private val previewPlayers = mutableMapOf<String, PreviewPlayer>()

    /** 首页角色实例列表（Adapter 直接复用，便于拖拽重排）。 */
    private val homeRoles = mutableListOf<HomeRoleInstance>()
    private lateinit var homeAdapter: HomeAdapter

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    /** 重置软件：二次确认弹窗（样式与“重置角色所有设置”一致）。 */
    private fun confirmResetSoftware() {
        showConfirmDialog(
            title = "重置软件",
            bodyHtml = "将<b>本软件</b>的一切重置！！！！！",
            subText = "素材删除，角色删除，设置删除。一切重置！",
            positiveText = "重置",
            titleColorRes = R.color.cp_danger
        ) { doResetSoftware() }
    }

    /** 执行重置：清素材库与顺序、删所有角色与设置、首页清单归位预装角色，并重启服务/刷新首页。 */
    private fun doResetSoftware() {
        val oldIds = RoleStore.getHomeRoles(this).map { it.instanceId }
        // 1. 撤回在屏宠物，避免重置后残留窗口。
        PetService.instance?.let { svc -> oldIds.forEach { svc.recall(it) } }
        // 2. 所有角色设置【就地】回到系统默认（经活跃 DataStore 实例写回，内存与磁盘同时生效，
        //    不删活动角色的文件——删文件会让进程内单例缓存残留旧值，导致重置不生效/被旧值写回污染）；
        //    清素材库、顺序、首页清单（下次读取自动播种预装角色）。
        RoleStore.resetAllSoftwareData(this)
        // 3. 删除用户上传的素材文件目录（服务重启会连带清掉派生的位图/资源包内存缓存）。
        filesDir.resolve("roles").deleteRecursively()
        // 4. 重启前台服务以重新初始化（清内存态、重读已清空的角色数据）；
        //    带 EXTRA_NO_AUTO_SUMMON：重置后不自动召唤任何角色，屏幕上不留宠物，
        //    由用户在首页卡片自行召唤。再重建首页以刷新卡片。
        stopService(Intent(this, PetService::class.java))
        startForegroundService(
            Intent(this, PetService::class.java)
                .putExtra(PetService.EXTRA_NO_AUTO_SUMMON, true)
        )
        recreate()
    }

    /** 判断 PetService 是否正在运行 */
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == PetService::class.java.name }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp（与设置页各 Activity 实现一致）。 */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
