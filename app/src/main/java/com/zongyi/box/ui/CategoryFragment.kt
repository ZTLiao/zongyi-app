package com.zongyi.box.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.zongyi.box.R
import com.zongyi.box.data.Parser55
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Src
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.FragmentCategoryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CategoryFragment : Fragment() {

    private var _b: FragmentCategoryBinding? = null
    private val b get() = _b!!

    // 综艺盒子（定制 vr 模板）分类
    private val catsJuzi = listOf(
        "大陆综艺" to 1, "港台综艺" to 2, "日韩综艺" to 3, "欧美综艺" to 4
    )
    // 555影视（mxone 模板）分类
    private val cats555 = Parser55.categories
    private val sorts = listOf("按更新" to "time", "按人气" to "hits", "按评分" to "score")
    private val years = listOf(
        "全部" to "", "2026" to "2026", "2025" to "2025", "2024" to "2024",
        "2023" to "2023", "2022" to "2022", "2021" to "2021", "2020" to "2020",
        "2019" to "2019", "2018" to "2018", "2017" to "2017"
    )

    private var src: Src = Src.JUZI
    private var cat = 1
    private var sort = "time"
    private var year = ""
    /** 555 精细筛选：URL 槽位 → 已选值（1地区 2排序 3类型 4语言 11年份） */
    private val sel555 = mutableMapOf<Int, String>()
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private lateinit var adapter: VodGridAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentCategoryBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        buildChips()
        adapter = VodGridAdapter { Nav.detail(requireContext(), it) }
        val lm = GridLayoutManager(requireContext(), 3)
        b.rvGrid.layoutManager = lm
        b.rvGrid.adapter = adapter
        b.rvGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (loading || !adapter.items.isNotEmpty()) return
                val last = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 6 && page < totalPages) load(page + 1, append = true)
            }
        })
        b.tvError.setOnClickListener { refresh() }
        refresh()
    }

    private fun chip(label: String, checked: Boolean, onPick: () -> Unit): Chip {
        val chip = Chip(requireContext())
        chip.text = label
        chip.isCheckable = true
        chip.isChecked = checked
        chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.chip_bg)
        chip.setTextColor(requireContext().getColorStateList(R.color.chip_text))
        chip.setOnClickListener { if (!chip.isChecked) chip.isChecked = true; onPick() }
        return chip
    }

    private fun buildChips() {
        // 来源切换
        b.chipsSrc.removeAllViews()
        Src.entries.forEach { s ->
            b.chipsSrc.addView(chip(s.label, s == src) {
                if (src != s) { src = s; sel555.clear(); buildChips(); refresh() }
            })
        }
        fill(b.chipsCategory, currentCats, 0) { v -> cat = v as Int; sel555.clear(); buildChips(); refresh() }
        buildFilterRows()
    }

    private val currentCats: List<Pair<String, Int>>
        get() = if (src == Src.JUZI) catsJuzi else cats555

    /** 筛选行：盒子源=排序/年份；555 源=从站点动态解析的 5 组精细筛选 */
    private fun buildFilterRows() {
        b.filtersBox.removeAllViews()
        if (src == Src.JUZI) {
            addRow(sorts.map { it.first to it.second }, sort) { v -> sort = v; refresh() }
            addRow(years.map { it.first to it.second }, year) { v -> year = v; refresh() }
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                runSuspendCatching { SiteApi.filters555(cat) }
                    .onSuccess { groups ->
                        // 视图已销毁或用户已切走来源时放弃渲染
                        if (_b == null || src != Src.DY555) return@onSuccess
                        groups.forEach { g ->
                            addRow(
                                g.options.map { it.label to it.value },
                                sel555[g.slot] ?: ""
                            ) { v ->
                                if (v.isEmpty()) sel555.remove(g.slot) else sel555[g.slot] = v
                                refresh()
                            }
                        }
                    }
            }
        }
    }

    /** 动态加一行筛选 chips（首个为"全部"，value=""） */
    private fun addRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
        val ctx = requireContext()
        val hsv = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val cg = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = (8 * resources.displayMetrics.density).toInt()
        }
        val all: List<Pair<String, String>> = listOf("全部" to "") + options
        all.forEach { (label, value) ->
            cg.addView(chip(label, value == selected) { onPick(value) })
        }
        hsv.addView(cg)
        b.filtersBox.addView(
            hsv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun fill(
        group: ChipGroup,
        list: List<Pair<String, Any>>,
        checkedIndex: Int,
        onPick: (Any) -> Unit
    ) {
        group.removeAllViews()
        list.forEachIndexed { idx, (label, value) ->
            group.addView(chip(label, idx == checkedIndex) { onPick(value) })
        }
    }

    private fun refresh() = load(1, append = false)

    private fun load(p: Int, append: Boolean) {
        if (loading) return
        loading = true
        page = p
        b.tvError.visibility = View.GONE
        if (!append) {
            b.progress.visibility = View.VISIBLE
            if (adapter.items.isNotEmpty()) adapter.submit(emptyList())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            runSuspendCatching {
                if (src == Src.JUZI) SiteApi.category(src, cat, sort, year, p)
                else SiteApi.category555(
                    cat,
                    area = sel555[1] ?: "", by = sel555[2] ?: "", cls = sel555[3] ?: "",
                    lang = sel555[4] ?: "", year = sel555[11] ?: "", page = p
                )
            }
                .onSuccess { data ->
                    totalPages = data.totalPages
                    if (append) adapter.append(data.items) else adapter.submit(data.items)
                    b.progress.visibility = View.GONE
                    if (data.items.isEmpty() && !append) {
                        b.tvError.visibility = View.VISIBLE
                        b.tvError.text = getString(R.string.empty)
                    }
                }
                .onFailure { e ->
                    // 协程取消/视图已销毁时直接退出，不碰已置空的 binding
                    if (_b == null || e is CancellationException) return@onFailure
                    b.progress.visibility = View.GONE
                    if (!append) {
                        b.tvError.visibility = View.VISIBLE
                        b.tvError.text = "加载失败：${e.message}\n点击重试"
                    }
                }
            loading = false
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
