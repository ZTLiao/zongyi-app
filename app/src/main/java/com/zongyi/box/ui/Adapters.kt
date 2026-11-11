package com.zongyi.box.ui

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.zongyi.box.data.VodItem
import com.zongyi.box.databinding.ItemVodGridBinding
import com.zongyi.box.databinding.ItemVodRowBinding

/** 三列网格海报卡 */
class VodGridAdapter(
    val items: MutableList<VodItem> = mutableListOf(),
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodGridAdapter.VH>() {

    inner class VH(val b: ItemVodGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVodGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.b.imgCover.load(item.cover) { crossfade(true) }
        h.b.tvTitle.text = item.title
        h.b.tvRemark.text = item.remark
        h.b.tvRemark.visibility = if (item.remark.isBlank()) View.GONE else View.VISIBLE
        h.b.tvActors.text = item.actors
        h.b.tvActors.visibility = if (item.actors.isBlank()) View.GONE else View.VISIBLE
        h.b.root.setOnClickListener { onClick(item) }
    }

    fun submit(list: List<VodItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun append(list: List<VodItem>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }
}

/** 横滑小卡（首页版块内） */
class VodRowAdapter(
    private val items: List<VodItem>,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<VodRowAdapter.VH>() {

    inner class VH(val b: ItemVodRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVodRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.b.imgCover.load(item.cover) { crossfade(true) }
        h.b.tvTitle.text = item.title
        h.b.tvRemark.text = item.remark
        h.b.tvRemark.visibility = if (item.remark.isBlank()) View.GONE else View.VISIBLE
        h.b.root.setOnClickListener { onClick(item) }
    }
}

/** 横滑列表辅助：把 RecyclerView 配置为横向 */
fun RecyclerView.setupHorizontal() {
    layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
}

object Nav {
    fun detail(ctx: Context, item: VodItem) {
        ctx.startActivity(Intent(ctx, DetailActivity::class.java).apply {
            putExtra("id", item.id)
            putExtra("title", item.title)
            putExtra("cover", item.cover)
            putExtra("remark", item.remark)
        })
    }
}
