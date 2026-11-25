package com.zongyi.box.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.zongyi.box.R
import com.zongyi.box.data.LiveApi
import com.zongyi.box.data.LiveGroup
import com.zongyi.box.data.LiveHome
import com.zongyi.box.data.LiveMatch
import com.zongyi.box.data.ReplayItem
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.FragmentLiveBinding
import com.zongyi.box.databinding.ItemLiveDayBinding
import com.zongyi.box.databinding.ItemLiveHeroBinding
import com.zongyi.box.databinding.ItemLiveMatchBinding
import com.zongyi.box.databinding.ItemLiveNowBinding
import com.zongyi.box.databinding.ItemReplayBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TYPE_HERO = 0
private const val TYPE_DAY = 1
private const val TYPE_MATCH = 2
private const val TYPE_REPLAY = 3

/**
 * 赛事直播页：赛程 / 录像 双模式。
 *
 * 交互设计：
 * - 顶部「直播 / 录像」主切换，下方按 NBA、篮球、足球、其他 过滤
 * - 直播模式顶部横滑「正在直播」，下方按日期分组的赛程卡片
 * - 录像模式为可无限下滑的集锦列表
 * - 下拉刷新 + 60 秒静默自动刷新（直播比分/状态可能变化）
 * - 全部列表项可遥控器聚焦，适配 TV
 */
class LiveFragment : Fragment() {

    private companion object {
        const val MODE_LIVE = 0
        const val MODE_REPLAY = 1
        const val AUTO_REFRESH_MS = 60_000L
    }

    private var _b: FragmentLiveBinding? = null
    private val b get() = _b!!

    private val uiHandler = Handler(Looper.getMainLooper())

    private var mode = MODE_LIVE
    private var chip = LiveGroup.ALL

    private var home: LiveHome? = null
    private var liveLoading = false

    private val replays = mutableListOf<ReplayItem>()
    private var replayPage = 0
    private var replayTotalPages = 1
    private var replayLoading = false

    private val chipViews = LinkedHashMap<String, TextView>()

    /** 本次进入页面后是否已自动定位到今天（只在首次渲染执行一次） */
    private var locatedToday = false

    /** 站点自动切换提示（LiveApi 可能在 IO 线程回调，这里切回主线程） */
    private val siteSwitchListener: (String) -> Unit = { base ->
        uiHandler.post {
            val ctx = context ?: return@post
            if (!isAdded || isHidden) return@post
            Toast.makeText(
                ctx,
                "已自动切换直播站点：${base.substringAfter("://")}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val diffCallback = object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
            oldItem is Row.Hero && newItem is Row.Hero -> true
            oldItem is Row.Day && newItem is Row.Day -> oldItem.date == newItem.date
            oldItem is Row.Match && newItem is Row.Match -> oldItem.match.id == newItem.match.id
            oldItem is Row.Replay && newItem is Row.Replay -> oldItem.item.id == newItem.item.id
            else -> false
        }

        override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
    }

    private val adapter = LiveFeedAdapter(
        diffCallback,
        onClickMatch = { LivePlayerActivity.startLive(requireContext(), it) },
        onClickReplay = { LivePlayerActivity.startReplay(requireContext(), it) },
    )

    private val autoRefreshTask = object : Runnable {
        override fun run() {
            if (!isAdded || isHidden || _b == null) return
            if (mode == MODE_LIVE) loadLive(force = true, silent = true)
            uiHandler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _b = FragmentLiveBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        locatedToday = false
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        b.rv.itemAnimator = null

        b.tabLive.isSelected = true
        b.tabLive.setOnClickListener { setMode(MODE_LIVE) }
        b.tabReplay.setOnClickListener { setMode(MODE_REPLAY) }

        buildChips()

        b.refresh.setColorSchemeResources(R.color.accent)
        b.refresh.setOnRefreshListener { load(force = true, silent = true) }
        b.btnRefresh.setOnClickListener { load(force = true, silent = false) }
        b.btnStateRetry.setOnClickListener { load(force = true, silent = false) }

        b.rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || mode != MODE_REPLAY || replayLoading) return
                if (replayPage >= replayTotalPages) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    loadReplays(replayPage + 1, force = false, silent = true)
                }
            }
        })

        load(force = false, silent = false)

        if (TvCompat.isTv(requireContext())) b.tabLive.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // 播放页会临时接管回调，回到列表页重新注册站点切换提示
        LiveApi.onSiteSwitch = siteSwitchListener
        if (!isHidden) uiHandler.postDelayed(autoRefreshTask, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        if (LiveApi.onSiteSwitch === siteSwitchListener) LiveApi.onSiteSwitch = null
        uiHandler.removeCallbacks(autoRefreshTask)
    }

    /** 底部导航的 Fragment 保活，隐藏时停止轮询，避免在别的页面后台刷新 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        uiHandler.removeCallbacks(autoRefreshTask)
        if (!hidden) uiHandler.postDelayed(autoRefreshTask, AUTO_REFRESH_MS)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (LiveApi.onSiteSwitch === siteSwitchListener) LiveApi.onSiteSwitch = null
        uiHandler.removeCallbacks(autoRefreshTask)
        b.rv.adapter = null
        _b = null
    }

    // ---------------- 筛选 / 模式 ----------------

    private fun buildChips() {
        b.chipRow.removeAllViews()
        chipViews.clear()
        LiveGroup.chips.forEach { name ->
            val tv = TextView(requireContext()).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setTextColor(ContextCompat.getColorStateList(context, R.color.live_chip_text))
                background = ContextCompat.getDrawable(context, R.drawable.bg_live_chip)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener { selectChip(name) }
            }
            chipViews[name] = tv
            b.chipRow.addView(tv)
        }
        updateChipStates()
    }

    private fun updateChipStates() {
        chipViews.forEach { (name, view) -> view.isSelected = name == chip }
    }

    private fun selectChip(name: String) {
        if (chip == name) return
        chip = name
        updateChipStates()
        b.rv.scrollToPosition(0)
        if (mode == MODE_LIVE) {
            renderLive()
        } else {
            replays.clear()
            replayPage = 0
            replayTotalPages = 1
            loadReplays(1, force = false, silent = false)
        }
    }

    private fun setMode(newMode: Int) {
        if (mode == newMode) return
        mode = newMode
        b.tabLive.isSelected = newMode == MODE_LIVE
        b.tabReplay.isSelected = newMode == MODE_REPLAY
        if (newMode == MODE_LIVE) {
            if (home != null) renderLive() else loadLive(force = false, silent = false)
        } else {
            if (replays.isEmpty()) loadReplays(1, force = false, silent = false) else renderReplays()
        }
    }

    private fun load(force: Boolean, silent: Boolean) {
        if (mode == MODE_LIVE) loadLive(force, silent) else loadReplays(1, force, silent)
    }

    // ---------------- 直播数据 ----------------

    private fun loadLive(force: Boolean, silent: Boolean) {
        if (liveLoading) return
        liveLoading = true
        if (!silent) showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runSuspendCatching {
                LiveApi.home(ttlMs = if (force) 0 else 2 * 60_000L)
            }
            liveLoading = false
            if (!isAdded) return@launch
            b.progress.isVisible = false
            b.refresh.isRefreshing = false
            result.onSuccess {
                home = it
                updateUpdatedAt()
                renderLive()
            }.onFailure {
                if (home == null) {
                    showState("直播源加载失败", "站点可能正在切换，点击重试", retry = true)
                } else if (!silent) {
                    Toast.makeText(requireContext(), "刷新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderLive() {
        val data = home ?: return
        val rows = mutableListOf<Row>()

        val liveNow = data.all.filter { it.isLive && LiveGroup.accept(it.group, chip) }
        if (liveNow.isNotEmpty()) rows.add(Row.Hero(liveNow))

        data.days.forEach { day ->
            val matches = day.matches.filter { LiveGroup.accept(it.group, chip) }
            if (matches.isNotEmpty()) {
                val (title, sub) = dayLabels(day.date)
                rows.add(Row.Day(day.date, title, sub, matches.size))
                matches.forEach { rows.add(Row.Match(it)) }
            }
        }

        adapter.submitList(rows)
        if (rows.isEmpty()) {
            showState("当前分类暂无赛事", "切换其他分类看看")
        } else {
            showState(null)
            locateTodayIfNeeded(rows)
        }
    }

    /**
     * 首次进入自动滚动到今天：站点一次返回约 5 天，
     * 若今天不在第一组，避免用户手动往下找。
     */
    private fun locateTodayIfNeeded(rows: List<Row>) {
        if (locatedToday) return
        locatedToday = true
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val index = rows.indexOfFirst { it is Row.Day && it.date == today }
        if (index <= 0) return
        b.rv.post {
            if (!isAdded) return@post
            (b.rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        }
    }

    // ---------------- 录像数据 ----------------

    private fun loadReplays(page: Int, force: Boolean, silent: Boolean) {
        if (replayLoading) return
        replayLoading = true
        if (!silent) showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runSuspendCatching {
                LiveApi.replays(page, ttlMs = if (force) 0 else 10 * 60_000L)
            }
            replayLoading = false
            if (!isAdded) return@launch
            b.progress.isVisible = false
            b.refresh.isRefreshing = false
            result.onSuccess { pageData ->
                if (pageData.page <= 1) replays.clear()
                val existing = replays.mapTo(HashSet()) { it.id }
                pageData.items.forEach { if (existing.add(it.id)) replays.add(it) }
                replayPage = pageData.page
                replayTotalPages = pageData.totalPages
                updateUpdatedAt()
                renderReplays()
            }.onFailure {
                if (replays.isEmpty()) {
                    showState("录像加载失败", "站点可能正在切换，点击重试", retry = true)
                } else if (!silent) {
                    Toast.makeText(requireContext(), "加载失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderReplays() {
        val filtered = replays.filter { LiveGroup.accept(it.group, chip) }
        adapter.submitList(filtered.map { Row.Replay(it) })
        if (filtered.isEmpty()) {
            showState("当前分类暂无录像", "切换其他分类看看")
        } else {
            showState(null)
        }
    }

    // ---------------- 通用 UI ----------------

    private fun showLoading(show: Boolean) {
        b.progress.isVisible = show
        if (show) b.statePanel.isVisible = false
    }

    /**
     * 空态 / 错误态面板。
     * @param title null 表示隐藏
     * @param retry 是否显示重试按钮（错误态）
     */
    private fun showState(title: String?, desc: String = "", retry: Boolean = false) {
        if (title == null) {
            b.statePanel.isVisible = false
            return
        }
        b.tvStateTitle.text = title
        b.tvStateDesc.text = desc
        b.tvStateDesc.isVisible = desc.isNotBlank()
        b.btnStateRetry.isVisible = retry
        b.statePanel.isVisible = true
    }

    private fun updateUpdatedAt() {
        val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        b.tvUpdatedAt.text = "更新于 $hm"
    }

    private fun dayLabels(date: String): Pair<String, String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val d = runCatching { fmt.parse(date) }.getOrNull() ?: return date to ""
        val target = Calendar.getInstance().apply {
            time = d
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = ((target.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        val md = SimpleDateFormat("MM-dd", Locale.CHINA).format(d)
        val week = SimpleDateFormat("EEE", Locale.CHINA).format(d)
        return when (diff) {
            0 -> "今天" to "$md · $week"
            1 -> "明天" to "$md · $week"
            -1 -> "昨天" to "$md · $week"
            else -> md to week
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun ImageView.loadLogo(url: String) {
        if (url.isBlank()) setImageDrawable(null) else load(url) { crossfade(true) }
    }

    // ---------------- RecyclerView ----------------

    private sealed class Row {
        data class Hero(val matches: List<LiveMatch>) : Row()

        data class Day(
            val date: String,
            val title: String,
            val sub: String,
            val count: Int,
        ) : Row()

        data class Match(val match: LiveMatch) : Row()

        data class Replay(val item: ReplayItem) : Row()
    }

    private inner class LiveFeedAdapter(
        diff: DiffUtil.ItemCallback<Row>,
        private val onClickMatch: (LiveMatch) -> Unit,
        private val onClickReplay: (ReplayItem) -> Unit,
    ) : ListAdapter<Row, RecyclerView.ViewHolder>(diff) {

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Row.Hero -> TYPE_HERO
            is Row.Day -> TYPE_DAY
            is Row.Match -> TYPE_MATCH
            is Row.Replay -> TYPE_REPLAY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HERO -> HeroVH(ItemLiveHeroBinding.inflate(inflater, parent, false))
                TYPE_DAY -> DayVH(ItemLiveDayBinding.inflate(inflater, parent, false))
                TYPE_MATCH -> MatchVH(ItemLiveMatchBinding.inflate(inflater, parent, false))
                else -> ReplayVH(ItemReplayBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Hero -> (holder as HeroVH).bind(row.matches)
                is Row.Day -> (holder as DayVH).bind(row)
                is Row.Match -> (holder as MatchVH).bind(row.match, onClickMatch)
                is Row.Replay -> (holder as ReplayVH).bind(row.item, onClickReplay)
            }
        }

        inner class HeroVH(private val bind: ItemLiveHeroBinding) :
            RecyclerView.ViewHolder(bind.root) {
            fun bind(matches: List<LiveMatch>) {
                bind.tvHeroTitle.text = "正在直播"
                bind.tvHeroCount.text = "${matches.size} 场进行中"
                bind.rvNow.layoutManager =
                    LinearLayoutManager(bind.root.context, LinearLayoutManager.HORIZONTAL, false)
                bind.rvNow.itemAnimator = null
                bind.rvNow.adapter = NowAdapter(matches, onClickMatch)
            }
        }

        inner class DayVH(private val bind: ItemLiveDayBinding) :
            RecyclerView.ViewHolder(bind.root) {
            fun bind(row: Row.Day) {
                bind.tvDate.text = row.title
                bind.tvWeekday.text = row.sub
                bind.tvCount.text = "${row.count} 场"
            }
        }

        inner class MatchVH(private val bind: ItemLiveMatchBinding) :
            RecyclerView.ViewHolder(bind.root) {
            fun bind(match: LiveMatch, onClick: (LiveMatch) -> Unit) {
                bind.tvLeague.text = match.league.ifBlank { "体育赛事" }
                bind.tvTime.text = match.time
                bind.tvHome.text = match.home
                bind.tvAway.text = match.away
                bind.ivHome.loadLogo(match.homeLogo)
                bind.ivAway.loadLogo(match.awayLogo)

                bind.tvStatus.text = match.status.ifBlank {
                    if (match.isFinished) "已结束" else "未开始"
                }
                bind.tvStatus.setTextColor(
                    ContextCompat.getColor(
                        bind.root.context,
                        if (match.isLive) R.color.badge else R.color.text_secondary,
                    )
                )
                bind.dotLive.isVisible = match.isLive
                bind.tvAction.text = when {
                    match.isLive -> "观看直播 ›"
                    match.isFinished -> "观看回放 ›"
                    else -> "查看线路 ›"
                }
                bind.root.setOnClickListener { onClick(match) }
            }
        }

        inner class ReplayVH(private val bind: ItemReplayBinding) :
            RecyclerView.ViewHolder(bind.root) {
            fun bind(item: ReplayItem, onClick: (ReplayItem) -> Unit) {
                bind.tvTitle.text = item.title
                bind.tvDesc.text = item.desc.ifBlank { "赛事录像回放" }
                bind.tvTime.text = item.publishedAt
                if (item.cover.isBlank()) {
                    bind.ivCover.setImageDrawable(null)
                } else {
                    bind.ivCover.load(item.cover) { crossfade(true) }
                }
                bind.root.setOnClickListener { onClick(item) }
            }
        }
    }

    /** 「正在直播」横滑卡片 */
    private inner class NowAdapter(
        private val items: List<LiveMatch>,
        private val onClick: (LiveMatch) -> Unit,
    ) : RecyclerView.Adapter<NowAdapter.VH>() {

        inner class VH(val bind: ItemLiveNowBinding) : RecyclerView.ViewHolder(bind.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemLiveNowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val match = items[position]
            with(holder.bind) {
                tvLeague.text = match.league.ifBlank { "体育赛事" }
                tvTime.text = match.time
                tvHome.text = match.home
                tvAway.text = match.away
                ivHome.loadLogo(match.homeLogo)
                ivAway.loadLogo(match.awayLogo)
                root.setOnClickListener { onClick(match) }
            }
        }
    }
}
