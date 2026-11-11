package com.zongyi.box.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.zongyi.box.R
import com.zongyi.box.data.HomeData
import com.zongyi.box.data.Parser
import com.zongyi.box.data.Section
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.VodItem
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.FragmentHomeBinding
import com.zongyi.box.databinding.ItemBannerPageBinding
import com.zongyi.box.databinding.ItemHomeBannerBinding
import com.zongyi.box.databinding.ItemHomeSectionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val handler = Handler(Looper.getMainLooper())
    private var bannerTask: Runnable? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        b.refresh.setColorSchemeResources(R.color.accent)
        b.refresh.setOnRefreshListener { load() }
        b.rvHome.layoutManager = LinearLayoutManager(requireContext())
        b.tvError.setOnClickListener { load() }
        load()
    }

    private fun load() {
        b.tvError.visibility = View.GONE
        b.refresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            runSuspendCatching { SiteApi.home() }
                .onSuccess { data ->
                    b.refresh.isRefreshing = false
                    b.rvHome.adapter = HomeAdapter(data) { Nav.detail(requireContext(), it) }
                }
                .onFailure { e ->
                    // 协程取消/视图已销毁时直接退出，不碰已置空的 binding
                    if (_b == null || e is CancellationException) return@onFailure
                    b.refresh.isRefreshing = false
                    if (b.rvHome.adapter == null || b.rvHome.adapter!!.itemCount == 0) {
                        b.tvError.visibility = View.VISIBLE
                        b.tvError.text = "加载失败：${e.message}\n点击重试"
                    }
                }
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
    private val data: HomeData,
    private val onClick: (VodItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sections = data.sections
    private val hasBanner = data.banner.isNotEmpty()

    inner class BannerVH(val b: ItemHomeBannerBinding) : RecyclerView.ViewHolder(b.root)
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
                hh.b.root.setOnClickListener { onClick(item) }
            }
        }
        vp.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(p: Int) {
                dots.getTabAt(p)?.select()
            }
        })
        // 轮播
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
        taskHolder.add(task)
    }
}

class BannerVH2(val b: ItemBannerPageBinding) : RecyclerView.ViewHolder(b.root)

/** Banner 轮播任务统一持有，Fragment 销毁时停止 */
object taskHolder {
    private val tasks = mutableListOf<Runnable>()
    fun add(t: Runnable) { tasks.add(t) }
    fun stopAll() {
        tasks.forEach { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        tasks.clear()
    }
}
