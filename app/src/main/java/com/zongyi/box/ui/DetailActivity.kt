package com.zongyi.box.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.zongyi.box.R
import com.zongyi.box.data.Episode
import com.zongyi.box.data.Parser
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Source
import com.zongyi.box.data.Store
import com.zongyi.box.data.VodDetail
import com.zongyi.box.data.VodItem
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.ActivityDetailBinding
import com.zongyi.box.databinding.ItemEpisodeBinding
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private var detail: VodDetail? = null
    private var currentSid = -1
    private var vodId = -1
    private var prefillTitle = ""
    private var prefillCover = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        vodId = intent.getIntExtra("id", -1)
        prefillTitle = intent.getStringExtra("title").orEmpty()
        prefillCover = intent.getStringExtra("cover").orEmpty()

        b.btnBack.setOnClickListener { finish() }
        b.tvTitle.text = prefillTitle
        if (prefillCover.isNotBlank()) b.imgCover.load(prefillCover)
        b.tvIntro.setOnClickListener { b.tvIntro.maxLines = if (b.tvIntro.maxLines == 3) Int.MAX_VALUE else 3 }

        b.btnPlay.setOnClickListener {
            val d = detail ?: return@setOnClickListener
            val src = d.source(currentSid) ?: d.firstAvailable() ?: return@setOnClickListener
            // 优先跳最近看的那一集
            val resume = Store.history(this).firstOrNull { it.vodId == vodId }
            val target = if (resume != null && resume.sid == src.sid)
                src.episodes.firstOrNull { it.nid == resume.nid } ?: src.episodes.firstOrNull()
            else src.episodes.firstOrNull()
            target?.let { openPlayer(src.sid, it) }
        }
        b.btnFav.setOnClickListener {
            val d = detail
            val item = if (d != null) VodItem(d.id, d.title, d.cover, d.status, d.actors)
            else VodItem(vodId, prefillTitle, prefillCover, "", "")
            val fav = Store.toggleFav(this, item)
            renderFav(fav)
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        // 从播放器/其他页面返回时刷新分集列表，选中态（上次观看集数）跟随最新历史
        if (detail != null) renderEpisodes()
    }

    private fun renderFav(fav: Boolean) {
        b.btnFav.setColorFilter(getColor(if (fav) R.color.accent else R.color.text_secondary))
    }

    private fun load() {
        b.progress.isVisible = true
        b.tvError.isVisible = false
        lifecycleScope.launch {
            runSuspendCatching { SiteApi.detail(vodId) }
                .onSuccess { d ->
                    detail = d
                    render(d)
                    b.progress.isVisible = false
                }
                .onFailure { e ->
                    b.progress.isVisible = false
                    b.tvError.isVisible = true
                    b.tvError.text = "加载失败：${e.message}\n点击返回"
                    b.tvError.setOnClickListener { finish() }
                }
        }
    }

    private fun render(d: VodDetail) {
        b.tvTitle.text = d.title
        b.imgCover.load(d.cover) { crossfade(true) }
        b.imgBg.load(d.cover) { crossfade(true) }
        b.tvStatus.text = d.status.ifBlank { d.remark }
        b.tvActors.text = "主演：${d.actors.ifBlank { "未知" }}"
        b.tvDirector.text = "导演：${d.director.ifBlank { "未知" }}"
        b.tvIntro.text = d.intro.ifBlank { "暂无简介" }
        renderFav(Store.isFav(this, d.id))

        // 线路 chips
        val group: ChipGroup = b.chipsSource
        group.removeAllViews()
        d.sources.forEach { src ->
            val chip = Chip(this)
            chip.text = "${src.name} (${src.episodes.size}集)"
            chip.isCheckable = true
            chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_bg)
            chip.setTextColor(getColorStateList(R.color.chip_text))
            chip.isChecked = src.sid == currentSid
            chip.setOnClickListener { selectSource(src.sid) }
            group.addView(chip)
        }

        // 默认选中：历史线路 > 第一个非空线路
        val last = Store.history(this).firstOrNull { it.vodId == d.id }
        currentSid = when {
            last != null && d.source(last.sid)?.episodes?.isNotEmpty() == true -> last.sid
            else -> d.firstAvailable()?.sid ?: -1
        }
        group.children.forEachIndexed { idx, chip ->
            if ((d.sources.getOrNull(idx)?.sid ?: -1) == currentSid) (chip as Chip).isChecked = true
        }
        renderEpisodes()
    }

    private fun selectSource(sid: Int) {
        currentSid = sid
        renderEpisodes()
    }

    private fun renderEpisodes() {
        val d = detail ?: return
        val src = d.source(currentSid) ?: d.firstAvailable() ?: return
        // 上次观看的集数（当前线路）→ 列表中选中高亮
        val last = Store.history(this).firstOrNull { it.vodId == d.id && it.sid == src.sid }
        val adapter = EpisodeAdapter(src.episodes, last?.nid ?: -1) { ep -> openPlayer(src.sid, ep) }
        b.rvEpisodes.layoutManager = GridLayoutManager(this, 4)
        b.rvEpisodes.adapter = adapter
        // 滚到上次观看位置
        val idx = last?.let { e -> src.episodes.indexOfFirst { it.nid == e.nid } } ?: -1
        if (idx >= 0) (b.rvEpisodes.layoutManager as GridLayoutManager)
            .scrollToPositionWithOffset(idx, 0)
    }

    private fun openPlayer(sid: Int, ep: Episode) {
        val d = detail ?: return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("id", d.id)
            putExtra("sid", sid)
            putExtra("nid", ep.nid)
            putExtra("title", d.title)
            putExtra("cover", d.cover)
            putExtra("episodeName", ep.name)
        })
    }
}

class EpisodeAdapter(
    private val episodes: List<Episode>,
    private val selectedNid: Int = -1,
    private val onClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    inner class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = episodes.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ep = episodes[pos]
        val selected = ep.nid == selectedNid
        h.b.tvEpisode.text =
            if (selected) "▶ ${ep.name}" else ep.name
        h.b.root.setBackgroundResource(
            if (selected) R.drawable.bg_episode_selected else R.drawable.bg_episode
        )
        h.b.tvEpisode.setTextColor(
            h.b.root.context.getColor(if (selected) R.color.accent else R.color.text_primary)
        )
        h.b.root.setOnClickListener { onClick(ep) }
    }
}
