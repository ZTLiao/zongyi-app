package com.zongyi.box.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.zongyi.box.R
import com.zongyi.box.data.Store
import com.zongyi.box.databinding.FragmentMineBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MineFragment : Fragment() {

    private var _b: FragmentMineBinding? = null
    private val b get() = _b!!
    private var showHistory = true

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentMineBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.rvMine.layoutManager = LinearLayoutManager(requireContext())
        b.chipHistory.setOnClickListener {
            if (!b.chipHistory.isChecked) b.chipHistory.isChecked = true
            showHistory = true; reload()
        }
        b.chipFav.setOnClickListener {
            if (!b.chipFav.isChecked) b.chipFav.isChecked = true
            showHistory = false; reload()
        }
        b.btnClear.setOnClickListener {
            if (showHistory) Store.clearHistory(requireContext())
            reload()
        }
        // AI 接入（appfunction）：开关本地桥接服务，展示端点与令牌
        b.rowAi.setOnClickListener { showAiBridgeDialog() }
        reload()
    }

    private fun aiStatusText(): String {
        val ctx = requireContext()
        return if (com.zongyi.box.data.AiBridge.isRunning()) {
            "运行中 :${com.zongyi.box.data.AiBridge.port(ctx)}"
        } else "未开启"
    }

    private fun refreshAiStatus() {
        b.tvAiStatus.text = aiStatusText()
    }

    /** AI 接入（appfunction）：开关 + 端点/令牌展示，AI 侧凭令牌经 HTTP 调用 app 功能 */
    private fun showAiBridgeDialog() {
        val ctx = requireContext()
        val switch = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
            isChecked = com.zongyi.box.data.AiBridge.isRunning()
            text = if (isChecked) "已开启，等待 AI 接入" else "已关闭"
        }
        val tvEndpoint = android.widget.TextView(ctx).apply {
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(0, 24, 0, 4)
            text = "端点：${endpointText()}"
        }
        val tvToken = android.widget.TextView(ctx).apply {
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, 4, 0, 4)
            text = "令牌：${com.zongyi.box.data.AiBridge.token(ctx)}"
        }
        val tvTip = android.widget.TextView(ctx).apply {
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, 16, 0, 0)
            text = "开启后 AI（本机或局域网）可通过 HTTP 调用 app 的搜索、详情、播放、历史、收藏等功能：\nGET /appfunction 功能清单 · POST /appfunction/call 调用\n鉴权：X-Api-Token 或 ?token="
        }
        val btnCopy = android.widget.TextView(ctx).apply {
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.accent))
            setPadding(0, 20, 0, 0)
            text = "复制端点与令牌"
            setOnClickListener {
                val text = "端点：${endpointText()}\n令牌：${com.zongyi.box.data.AiBridge.token(ctx)}"
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("appfunction", text))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 40, 56, 24)
            addView(switch)
            addView(tvEndpoint)
            addView(tvToken)
            addView(btnCopy)
            addView(tvTip)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_ZongYiBox)
            .setTitle("AI 接入")
            .setView(box)
            .setPositiveButton("完成", null)
            .setOnDismissListener { refreshAiStatus() }
            .show()
        switch.setOnCheckedChangeListener { _, checked ->
            com.zongyi.box.data.AiBridge.setEnabled(ctx, checked)
            switch.text = if (checked) "已开启，等待 AI 接入" else "已关闭"
            if (checked) {
                tvEndpoint.text = "端点：${endpointText()}"
                Toast.makeText(ctx, "AI 接入已开启：${endpointText()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun endpointText(): String {
        val ip = com.zongyi.box.data.AiBridge.lanIp() ?: "127.0.0.1"
        return "http://$ip:${com.zongyi.box.data.AiBridge.port(requireContext())}/appfunction"
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) {
            reload()
            refreshAiStatus()
        }
    }

    private fun reload() {
        val ctx = requireContext()
        b.tvTitle.setText(if (showHistory) R.string.history else R.string.favorites)
        b.btnClear.visibility = if (showHistory) View.VISIBLE else View.GONE

        if (showHistory) {
            val list = Store.history(ctx)
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            b.rvMine.adapter = HistoryAdapter(list,
                onOpen = { entry ->
                    ctx.startActivity(android.content.Intent(ctx, DetailActivity::class.java).apply {
                        putExtra("id", entry.vodId)
                        putExtra("title", entry.title)
                        putExtra("cover", entry.cover)
                        putExtra("src", entry.srcKey)
                    })
                },
                onRemove = { entry ->
                    Store.removeHistory(ctx, entry.vodId, entry.srcKey)
                    reload()
                })
        } else {
            val list = Store.favorites(ctx).map { it.item }
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            b.rvMine.adapter = FavAdapter(list) { Nav.detail(ctx, it) }
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}

private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

class HistoryAdapter(
    private val items: List<com.zongyi.box.data.HistoryEntry>,
    private val onOpen: (com.zongyi.box.data.HistoryEntry) -> Unit,
    private val onRemove: (com.zongyi.box.data.HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    inner class VH(val b: com.zongyi.box.databinding.ItemMineRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(com.zongyi.box.databinding.ItemMineRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.b.imgCover.load(item.cover) { crossfade(true) }
        h.b.tvTitle.text = item.title
        h.b.tvSub.text = item.episodeName.ifBlank { "第${item.nid}集" }
        val tag = if (item.srcKey == com.zongyi.box.data.Src.JUZI.key) ""
        else "[${com.zongyi.box.data.Src.of(item.srcKey).label}] "
        val pct = if (item.durationMs > 0) " · 已看 ${item.positionMs * 100 / item.durationMs}%" else ""
        h.b.tvTime.text = tag + fmt.format(Date(item.updatedAt)) + pct
        h.b.btnRemove.setOnClickListener { onRemove(item) }
        h.b.root.setOnClickListener { onOpen(item) }
    }
}

class FavAdapter(
    private val items: List<com.zongyi.box.data.VodItem>,
    private val onOpen: (com.zongyi.box.data.VodItem) -> Unit
) : RecyclerView.Adapter<FavAdapter.VH>() {

    inner class VH(val b: com.zongyi.box.databinding.ItemMineRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(com.zongyi.box.databinding.ItemMineRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.b.imgCover.load(item.cover) { crossfade(true) }
        h.b.tvTitle.text = item.title
        h.b.tvSub.text = item.actors.ifBlank { item.remark }
        h.b.tvTime.visibility = android.view.View.GONE
        h.b.btnRemove.visibility = android.view.View.GONE
        h.b.root.setOnClickListener { onOpen(item) }
    }
}
