package com.zongyi.box.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.zongyi.box.R
import com.zongyi.box.data.HomeData
import com.zongyi.box.data.HomeMerger.mergeHome
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.SourceHealth
import com.zongyi.box.data.VodItem
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.FragmentHomeBinding
import com.zongyi.box.databinding.ItemBannerPageBinding
import com.zongyi.box.databinding.ItemHomeBannerBinding
import com.zongyi.box.databinding.ItemHomeSectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.abs

class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "ZongYiBox"
    }

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())
    private var bannerTask: Runnable? = null
    private var adapter: HomeAdapter? = null

    /** 首页数据新鲜缓存窗口：进入页面先显缓存秒开，后台再刷新 */
    private val homeTtlMs = 10 * 60_000L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.refresh.setColorSchemeResources(R.color.accent)
        b.refresh.setOnRefreshListener { load(forceNetwork = true) }
        b.rvHome.layoutManager = LinearLayoutManager(requireContext())
        b.tvError.setOnClickListener { load(forceNetwork = false) }
        b.tvSrcStatus.setOnClickListener { load(forceNetwork = false) }
        // 源隐藏/恢复时（如失效源自动隐藏）重载首页
        viewLifecycleOwner.lifecycleScope.launch {
            SourceHealth.availableFlow.collect { load(forceNetwork = false) }
        }
        load(forceNetwork = false)
    }

    private fun load(forceNetwork: Boolean) {
        b.tvError.visibility = View.GONE
        b.tvSrcStatus.visibility = View.GONE
        b.refresh.isRefreshing = true
        val ttl = if (forceNetwork) 0 else homeTtlMs
        viewLifecycleOwner.lifecycleScope.launch {
            // 全部可用源并发加载；单站失败不影响其他站展示
            val sources = SourceHealth.available()
            val jobs = sources.map { s ->
                async {
                    runSuspendCatching { SiteApi.home(s, ttl) }
                        .onFailure { Log.w(TAG, "${s.label}源加载失败", it) }
                        .getOrNull()
                }
            }
            val results = jobs.map { it.await() }
            val data = mergeHome(results)
            b.refresh.isRefreshing = false
            if (data == null) {
                if (b.rvHome.adapter == null || b.rvHome.adapter!!.itemCount == 0) {
                    b.tvError.visibility = View.VISIBLE
                    b.tvError.text = "加载失败：网络异常\n点击重试"
                }
                return@launch
            }
            render(data)
            // 单源失败时给出可见提示（内容仍可看，但用户应知道哪个源挂了）
            val lost = sources.filterIndexed { i, _ -> results[i] == null }.map { it.label }
            if (lost.isNotEmpty()) {
                b.tvSrcStatus.text = "「${lost.joinToString("」「")}」源暂时连不上，其余内容正常 · 点击重试"
                b.tvSrcStatus.visibility = View.VISIBLE
            }
            // 缓存秒开后，后台静默拉最新数据，内容有变化才无感更新列表
            if (!forceNetwork) launch {
                val freshJobs = SourceHealth.available().map { s ->
                    async { runSuspendCatching { SiteApi.home(s) }.getOrNull() }
                }
                val fresh = mergeHome(freshJobs.map { it.await() })
                if (_b != null && fresh != null && fresh != data) render(fresh)
            }
        }
    }

    /** 复用 adapter 增量刷新，避免整表重建导致的滚动位置丢失与图片闪烁 */
    private fun render(data: HomeData) {
        val a = adapter
        if (a == null) {
            adapter = HomeAdapter(data) { Nav.detail(requireContext(), it) }.also { b.rvHome.adapter = it }
        } else {
            a.submit(data)
        }
    }

    override fun onDestroyView() {
        taskHolder.stopAll()
        _b = null
        super.onDestroyView()
    }
}

/** 首页多类型列表：Banner + 版块 */
class HomeAdapter(
    private var data: HomeData,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections get() = data.sections
    private val hasBanner get() = data.banner.isNotEmpty()

    /** 数据更新：整体内容变化时调用（Coil 内存缓存使封面无闪烁） */
    fun submit(d: HomeData) {
        data = d
        notifyDataSetChanged()
    }

    inner class BannerVH(val b: ItemHomeBannerBinding) : RecyclerView.ViewHolder(b.root) {
        // 当前 VH 绑定的轮播任务，重绑时先移除旧的，避免任务堆积
        var task: Runnable? = null
    }
    inner class SectionVH(val b: ItemHomeSectionBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(pos: Int) = if (pos == 0 && hasBanner) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        return if (type == 0)
            BannerVH(ItemHomeBannerBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        else
            SectionVH(ItemHomeSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun getItemCount() = sections.size + if (hasBanner) 1 else 0

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        if (h is BannerVH) bindBanner(h, data.banner)
        else if (h is SectionVH) {
            val sec = sections[pos - if (hasBanner) 1 else 0]
            h.b.tvSection.text = sec.title
            h.b.rvSection.setupHorizontal()
            h.b.rvSection.adapter = VodRowAdapter(sec.items) { onClick(it) }
        }
    }

    private fun bindBanner(h: BannerVH, banner: List<VodItem>) {
        val dots = h.b.bannerDots
        val vp = h.b.vpBanner
        dots.removeAllViews()
        repeat(banner.size) { dots.addTab(dots.newTab()) }
        vp.adapter = object : RecyclerView.Adapter<BannerVH2>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerVH2 =
                BannerVH2(ItemBannerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            override fun getItemCount() = banner.size

            override fun onBindViewHolder(hh: BannerVH2, pos: Int) {
                val item = banner[pos]
                hh.b.imgBanner.load(item.cover) { crossfade(true) }
                hh.b.tvBannerTitle.text = item.title
                hh.b.tvBannerRemark.text = item.remark
                hh.b.tvBannerRemark.isVisible = item.remark.isNotBlank()
                hh.b.root.setOnClickListener { onClick(item) }
            }
        }
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(p: Int) {
                dots.getTabAt(p)?.select()
            }
        })
        // 轮播：重绑时先清掉该 VH 的旧任务，防止任务堆积（复用/刷新场景）
        h.task?.let { taskHolder.remove(it) }
        val task = object : Runnable {
            private val hnd = Handler(Looper.getMainLooper())
            override fun run() {
                if (banner.size > 1) {
                    val next = (vp.currentItem + 1) % banner.size
                    vp.setCurrentItem(next, true)
                }
                hnd.postDelayed(this, 5000)
            }
        }
        h.task = task
        taskHolder.add(task)
    }
}

class BannerVH2(val b: ItemBannerPageBinding) : RecyclerView.ViewHolder(b.root)

/** Banner 轮播任务统一持有，Fragment 销毁时停止 */
object taskHolder {
    private val tasks = mutableListOf<Runnable>()
    fun add(t: Runnable) { tasks.add(t) }
    fun remove(t: Runnable?) {
        if (t == null) return
        Handler(Looper.getMainLooper()).removeCallbacks(t)
        tasks.remove(t)
    }
    fun stopAll() {
        tasks.forEach { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        tasks.clear()
    }
}
