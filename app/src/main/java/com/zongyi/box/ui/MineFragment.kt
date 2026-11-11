package com.zongyi.box.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        reload()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) reload()
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
                    })
                },
                onRemove = { entry ->
                    Store.removeHistory(ctx, entry.vodId)
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
        val pct = if (item.durationMs > 0) " · 已看 ${item.positionMs * 100 / item.durationMs}%" else ""
        h.b.tvTime.text = fmt.format(Date(item.updatedAt)) + pct
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
