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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailBinding
    private var detail: VodDetail? = null
    private var currentSid = -1
    private var vodId = -1
    private var srcKey = com.zongyi.box.data.Src.JUZI.key
    private var prefillTitle = ""
    private var prefillCover = ""
    /** 用户是否手动换过线路：后台静默刷新时不打扰已交互的界面 */
    private var userInteracted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV/盒子横屏（手机保持布局声明的竖屏），失败不影响启动
        TvCompat.applyOrientation(this)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        // NestedScrollView 构造器会强制 setFocusable(true)，抢走遥控器初始焦点，
        // 这里显式关掉：焦点直接落到返回/立即播放按钮
        b.root.isFocusable = false

        vodId = intent.getIntExtra("id", -1)
        srcKey = intent.getStringExtra("src") ?: com.zongyi.box.data.Src.JUZI.key
        prefillTitle = intent.getStringExtra("title").orEmpty()
        prefillCover = intent.getStringExtra("cover").orEmpty()

        b.btnBack.setOnClickListener { finish() }
        b.tvTitle.text = prefillTitle
        if (prefillCover.isNotBlank()) b.imgCover.load(prefillCover)
        b.tvIntro.setOnClickListener { b.tvIntro.maxLines = if (b.tvIntro.maxLines == 3) Int.MAX_VALUE else 3 }

        b.btnPlay.setOnClickListener {
            val (src, ep) = playTarget() ?: return@setOnClickListener
            openPlayer(src.sid, ep)
        }
        b.btnFav.setOnClickListener {
            val d = detail
            val item = if (d != null) VodItem(d.id, d.title, d.cover, d.status, d.actors, srcKey)
            else VodItem(vodId, prefillTitle, prefillCover, "", "", srcKey)
            val fav = Store.toggleFav(this, item)
            renderFav(fav)
        }

        load(fromCache = true)
    }

    override fun onResume() {
        super.onResume()
        // 从播放器/其他页面返回时刷新分集列表，选中态（上次观看集数）跟随最新历史
        if (detail != null) {
            renderEpisodes()
            updateResumeButton()
        }
    }

    /** 播放按钮文案：有观看记录时显示"续播 第X集"（同来源） */
    private fun updateResumeButton() {
        val d = detail ?: return
        val last = Store.history(this).firstOrNull { it.vodId == d.id && it.srcKey == srcKey }
        val src = last?.let { d.source(it.sid) } ?: d.firstAvailable()
        val ep = src?.episodes?.firstOrNull { it.nid == last?.nid }
        b.btnPlay.text = if (ep != null) "续播 ${ep.name}" else "立即播放"
    }

    private fun renderFav(fav: Boolean) {
        b.btnFav.setColorFilter(getColor(if (fav) R.color.accent else R.color.text_secondary))
    }

    private fun load(fromCache: Boolean = false) {
        b.progress.isVisible = true
        b.tvError.isVisible = false
        val ttl = if (fromCache) SiteApi.DETAIL_TTL_MS else 0
        lifecycleScope.launch {
            runSuspendCatching { SiteApi.detail(com.zongyi.box.data.Src.of(srcKey), vodId, ttl) }
                .onSuccess { d ->
                    val firstLoad = detail == null
                    val changed = detail != d
                    detail = d
                    render(d)
                    b.progress.isVisible = false
                    // 缓存秒开后，后台静默拉最新；内容有变化且用户未操作时才重绘
                    if (fromCache && firstLoad && changed) refreshInBackground()
                }
                .onFailure { e ->
                    if (e is CancellationException) return@onFailure
                    b.progress.isVisible = false
                    b.tvError.isVisible = true
                    b.tvError.text = "加载失败：${e.message}\n点击重试"
                    b.tvError.setOnClickListener { load() }
                }
        }
    }

    private fun refreshInBackground() {
        lifecycleScope.launch {
            runSuspendCatching { SiteApi.detail(com.zongyi.box.data.Src.of(srcKey), vodId, 0) }
                .onSuccess { d ->
                    if (d == detail) return@onSuccess
                    detail = d
                    if (!userInteracted && !isDestroyed && !isFinishing) render(d)
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

        // 线路 chips（555 站线路很多时展示前 12 条）
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

        // 默认选中：历史线路 > 第一个非空线路（同来源）
        val last = Store.history(this).firstOrNull { it.vodId == d.id && it.srcKey == srcKey }
        currentSid = when {
            last != null && d.source(last.sid)?.episodes?.isNotEmpty() == true -> last.sid
            else -> d.firstAvailable()?.sid ?: -1
        }
        group.children.forEachIndexed { idx, chip ->
            if ((d.sources.getOrNull(idx)?.sid ?: -1) == currentSid) (chip as Chip).isChecked = true
        }
        updateResumeButton()
        renderEpisodes()
        // 预取播放地址：用户点"立即播放"时秒开
        prefetchPlay()
    }

    private fun selectSource(sid: Int) {
        userInteracted = true
        currentSid = sid
        renderEpisodes()
    }

    /** 默认播放目标：当前线路 +（续播集或第一集）；null=无可播内容 */
    private fun playTarget(): Pair<Source, Episode>? {
        val d = detail ?: return null
        val src = d.source(currentSid) ?: d.firstAvailable() ?: return null
        // 优先跳最近看的那一集（同来源）
        val resume = Store.history(this).firstOrNull { it.vodId == vodId && it.srcKey == srcKey }
        val target = if (resume != null && resume.sid == src.sid)
            src.episodes.firstOrNull { it.nid == resume.nid } ?: src.episodes.firstOrNull()
        else src.episodes.firstOrNull()
        return target?.let { src to it }
    }

    /** 后台预取目标集的播放地址与清洗后播放列表：点击"立即播放"零网络等待 */
    private fun prefetchPlay() {
        val (src, ep) = playTarget() ?: return
        lifecycleScope.launch {
            SiteApi.prefetchStream(com.zongyi.box.data.Src.of(srcKey), vodId, src.sid, ep.nid, cacheDir)
        }
    }

    private fun renderEpisodes() {
        val d = detail ?: return
        val src = d.source(currentSid) ?: d.firstAvailable() ?: return
        // 上次观看的集数（当前线路）→ 列表中选中高亮（同来源）
        val last = Store.history(this).firstOrNull { it.vodId == d.id && it.srcKey == srcKey && it.sid == src.sid }
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
            putExtra("src", srcKey)
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
