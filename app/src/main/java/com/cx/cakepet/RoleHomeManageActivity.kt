package com.cx.cakepet

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.datastore.preferences.preferencesDataStoreFile
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 角色管理页：管理首页的“实例”（非素材模板）。
 *  - 列表顺序仅用于组织与置顶展示，不再决定屏幕叠放层级；宠物窗堆叠固定为「召唤顺序」。
 *    （PetService.reapplyZOrder 现仅负责把辅助浮层重新置顶，不再按本列表重排宠物窗。）
 *  - 卡片可长按拖动排序、三点浮窗重命名（显示名 + 简称，独立、不写回素材包）/ 删除（二次确认）。
 */
class RoleHomeManageActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: HomeRoleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_home_manage)
        findViewById<View>(R.id.status_bar_spacer).apply {
            layoutParams.height = getStatusBarHeight()
            requestLayout()
        }
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }

        recycler = findViewById(R.id.role_home_list)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = HomeRoleAdapter(
            items = mutableListOf(),
            onMore = { inst, anchor -> showItemMenu(inst, anchor) }
        )
        recycler.adapter = adapter

        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                // 末尾「+」卡片（position == items.size）不参与拖拽，禁止拖入或拖出。
                if (from < 0 || to < 0 || from !in adapter.items.indices || to !in adapter.items.indices) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // 拖拽落定：持久化新顺序并重排实时显示/点击层级。
                persistOrder()
            }
        })
        touch.attachToRecyclerView(recycler)
    }

    override fun onResume() {
        super.onResume()
        loadList()
    }

    override fun onPause() {
        adapter.stopAll()
        super.onPause()
    }

    private fun loadList() {
        adapter.stopAll()
        val home = RoleStore.getHomeRoles(this)
        adapter.submit(home)
    }

    private fun showItemMenu(inst: HomeRoleInstance, anchor: View) {
        PopupMenuHelper(
            anchor = anchor,
            popupLayoutRes = R.layout.popup_role_home_item,
            items = mapOf(
                R.id.item_rename to { showRename(inst) },
                R.id.item_delete to { confirmDelete(inst) }
            )
        ).show()
    }

    private fun showRename(inst: HomeRoleInstance) {
        val name = RoleStore.resolveDisplayName(this, inst.instanceId)
        val short = RoleStore.resolveShortName(this, inst.instanceId)
        showRenameDialog(name = name, short = short) { newName, newShort ->
            RoleStore.updateHomeRoleRename(this, inst.instanceId, newName, newShort)
            PetService.instance?.refreshShortName()
            loadList()
            Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(inst: HomeRoleInstance) {
        val name = RoleStore.resolveDisplayName(this, inst.instanceId)
        showConfirmDialog(
            title = "删除角色",
            bodyHtml = "确定删除「$name」？",
            subText = "该角色实例及设置都将被移除！",
            positiveText = "删除",
            titleColorRes = R.color.cp_danger
        ) { doDelete(inst) }
    }

    private fun doDelete(inst: HomeRoleInstance) {
        deleteRoleInstance(this, inst.instanceId)
        loadList()
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
    }

    private fun persistOrder() {
        RoleStore.setHomeRoles(this, adapter.items)
        // 实时重排显示/点击层级（仅重排堆叠次序，不影响各窗显示位置）。
        PetService.instance?.reapplyZOrder()
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    /** 首页实例列表适配器：含动态预览（PreviewPlayer）与长按拖动重排。 */
    companion object {
        private const val TYPE_ITEM = 0   // 角色实例卡片
        private const val TYPE_ADD = 1    // 末尾「+」加角色卡片

        /**
         * 删除指定角色实例：召回实时窗 + 清该角色专属 config/bounds 文件 + 从首页实例清单移除。
         * 供设置页「删除角色」三点菜单复用（按 roleId 精确删除，与主设置页当前角色一致）。
         */
        fun deleteRoleInstance(context: Context, instanceId: String) {
            // 先召回其实时窗，再清理其专属配置，最后从首页实例清单移除。
            PetService.instance?.recall(instanceId)
            context.applicationContext.preferencesDataStoreFile("pet_config_${instanceId}").delete()
            context.applicationContext.preferencesDataStoreFile("pet_bounds_${instanceId}").delete()
            RoleStore.removeHomeRole(context, instanceId)
        }
    }

    inner class HomeRoleAdapter(
        val items: MutableList<HomeRoleInstance>,
        private val onMore: (HomeRoleInstance, View) -> Unit
    ) : RecyclerView.Adapter<HomeRoleAdapter.VH>() {

        private val players = mutableMapOf<String, PreviewPlayer>()
        private val mgrCache = mutableMapOf<String, ImageModeManager>()

        fun submit(list: List<HomeRoleInstance>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
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

        override fun getItemCount(): Int = items.size + 1  // +1 = 末尾「+」卡片

        override fun getItemViewType(position: Int): Int =
            if (position == items.size) TYPE_ADD else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == TYPE_ADD) R.layout.item_add_role else R.layout.item_role_home
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            // 末尾「+」卡片：进入角色素材管理页（加入更多素材）。不参与数据绑定/拖拽。
            if (position == items.size) {
                holder.itemView.setOnClickListener {
                    startActivity(Intent(this@RoleHomeManageActivity, RoleManageActivity::class.java))
                }
                return
            }
            val inst = items[position]
            holder.instId = inst.instanceId
            val ctx = holder.itemView.context
            // 角色卡片布局才有这些视图；footer「+」卡片不会走到这里（已上方 early-return）。
            val name = holder.name ?: return
            val sub = holder.sub ?: return
            val thumb = holder.thumb ?: return
            val more = holder.more ?: return
            name.text = RoleStore.resolveDisplayName(ctx, inst.instanceId)
            sub.text = "简称：${RoleStore.resolveShortName(ctx, inst.instanceId)}"

            // 复用/重建该实例的预览播放器
            players[inst.instanceId]?.stop()
            val desc = RoleStore.descriptorForInstance(ctx, inst.instanceId)
            if (desc != null) {
                val mgr = mgrCache.getOrPut(inst.instanceId) {
                    ImageModeManager(ctx, buildSpritePackForRole(ctx, desc))
                }
                val player = PreviewPlayer(mgr, thumb, ctx.resources.displayMetrics.density)
                players[inst.instanceId] = player
                thumb.setOnClickListener { player.triggerPat() }
                player.start()
            } else {
                thumb.setImageDrawable(null)
            }
            more.setOnClickListener { onMore(inst, more) }
        }

        override fun onViewRecycled(holder: VH) {
            val id = holder.instId
            if (id.isNotEmpty()) {
                players[id]?.stop()
                players.remove(id)
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var instId: String = ""
            // 可空：末尾「+」卡片（item_add_role）不含这些 id，findViewById 返回 null。
            val thumb: ImageView? = itemView.findViewById(R.id.role_thumb)
            val name: TextView? = itemView.findViewById(R.id.role_name)
            val sub: TextView? = itemView.findViewById(R.id.role_sub)
            val more: ImageView? = itemView.findViewById(R.id.iv_more)
        }
    }
}
