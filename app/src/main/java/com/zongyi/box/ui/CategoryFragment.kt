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
import com.zongyi.box.data.ParserMonkey
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.SourceHealth
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
    // 蓝光影院（myui 模板）分类：顶层 + 子分类（tid 即站点 /list/?{tid}.html 的分类号）
    private val catsTbl = listOf(
        "电影" to 1, "电视剧" to 2, "综艺" to 3, "动漫" to 4, "短剧" to 37
    )
    private val subCatsTbl: Map<Int, List<Pair<String, Int>>> = mapOf(
        1 to listOf(
            "动作片" to 5, "爱情片" to 6, "科幻片" to 7, "恐怖片" to 8,
            "战争片" to 9, "喜剧片" to 10, "纪录片" to 11, "剧情片" to 12,
        ),
        2 to listOf(
            "大陆剧" to 13, "港台剧" to 14, "欧美剧" to 15, "日韩剧" to 16,
            "海外剧" to 25, "泰国剧" to 26,
        ),
        3 to listOf("大陆综艺" to 27, "港台综艺" to 28, "日韩综艺" to 29, "欧美综艺" to 30),
        4 to listOf(
            "国产动漫" to 31, "日韩动漫" to 32, "欧美动漫" to 33,
            "港台动漫" to 34, "海外动漫" to 35, "动画片" to 36,
        ),
    )
    // 猴影工坊（Astro SSR）分类
    private val catsMf = ParserMonkey.categories
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
    /** 猴影工坊筛选：genre / year / order */
    private val selMf = mutableMapOf<String, String>()
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private lateinit var adapter: VodGridAdapter

    /** 列表新鲜缓存窗口：切分类/切筛选/翻页命中缓存时免网络秒出 */
    private val listTtlMs = 5 * 60_000L

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
        // 源隐藏/恢复时重建来源 chips；当前源被隐藏则切回第一个可用源
        // （必须放在 adapter 初始化之后：collect 会立即回调一次，可能触发 refresh）
        viewLifecycleOwner.lifecycleScope.launch {
            SourceHealth.availableFlow.collect {
                if (_b == null || !::adapter.isInitialized) return@collect
                if (SourceHealth.isHidden(src)) {
                    src = SourceHealth.available().firstOrNull() ?: Src.JUZI
                    sel555.clear(); selMf.clear(); cat = currentCats.firstOrNull()?.second ?: 1
                    buildChips(); refresh()
                } else {
                    buildChips()
                }
            }
        }
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
        // 来源切换（失效源已被 SourceHealth 隐藏，不再出现）
        b.chipsSrc.removeAllViews()
        SourceHealth.available().forEach { s ->
            b.chipsSrc.addView(chip(s.label, s == src) {
                if (src != s) {
                    src = s; sel555.clear(); selMf.clear()
                    cat = currentCats.firstOrNull()?.second ?: 1
                    buildChips(); refresh()
                }
            })
        }
        // 分类 chips：蓝光影院顶层分类需按当前生效 tid（可能为子分类）反查高亮
        when (src) {
            Src.TBL -> {
                val top = tblTopOf(cat) ?: catsTbl.first().second
                b.chipsCategory.removeAllViews()
                catsTbl.forEach { (label, tid) ->
                    b.chipsCategory.addView(chip(label, tid == top) {
                        if (cat != tid) {
                            cat = tid; sel555.clear(); selMf.clear()
                            buildChips(); refresh()
                        }
                    })
                }
            }
            else -> {
                val idx = currentCats.indexOfFirst { it.second == cat }.let { if (it >= 0) it else 0 }
                fill(b.chipsCategory, currentCats, idx) { v ->
                    cat = v as Int; sel555.clear(); selMf.clear(); buildChips(); refresh()
                }
            }
        }
        buildFilterRows()
    }

    /** 当前生效 tid 所属的蓝光影院顶层分类 tid */
    private fun tblTopOf(catId: Int): Int? = when {
        catsTbl.any { it.second == catId } -> catId
        else -> subCatsTbl.entries.firstOrNull { e -> e.value.any { it.second == catId } }?.key
    }

    private val currentCats: List<Pair<String, Int>>
        get() = when (src) {
            Src.JUZI -> catsJuzi
            Src.DY555 -> cats555
            Src.TBL -> catsTbl
            Src.MF -> catsMf
        }

    /** 筛选行：盒子源=排序/年份；555 源=动态精细筛选；蓝光源=子分类；猴影源=类型/年代/排序 */
    private fun buildFilterRows() {
        b.filtersBox.removeAllViews()
        if (src == Src.JUZI) {
            addRow(sorts.map { it.first to it.second }, sort) { v -> sort = v; refresh() }
            addRow(years.map { it.first to it.second }, year) { v -> year = v; refresh() }
        } else if (src == Src.DY555) {
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
        } else if (src == Src.TBL) {
            // 子分类行（"全部"=顶层分类本身）
            val top = tblTopOf(cat) ?: return
            val subs = subCatsTbl[top] ?: return
            addRow(subs.map { it.first to it.second.toString() }, if (cat == top) "" else cat.toString()) { v ->
                val newCat = if (v.isEmpty()) top else v.toIntOrNull() ?: top
                if (newCat != cat) {
                    cat = newCat
                    buildFilterRows()
                    refresh()
                }
            }
        } else if (src == Src.MF) {
            // 类型子分类（按顶层分类不同而不同）
            ParserMonkey.genresByType[cat]?.let { genres ->
                addRow(genres.map { it.first to it.second.toString() }, selMf["genre"] ?: "") { v ->
                    if (v.isEmpty()) selMf.remove("genre") else selMf["genre"] = v
                    buildFilterRows()
                    refresh()
                }
            }
            addRow(ParserMonkey.yearsMf.map { it.first to it.second }, selMf["year"] ?: "") { v ->
                if (v.isEmpty()) selMf.remove("year") else selMf["year"] = v
                refresh()
            }
            addRow(ParserMonkey.ordersMf.map { it.first to it.second }, selMf["order"] ?: "") { v ->
                if (v.isEmpty()) selMf.remove("order") else selMf["order"] = v
                refresh()
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
        if (loading || !::adapter.isInitialized) return
        loading = true
        page = p
        b.tvError.visibility = View.GONE
        if (!append) {
            b.progress.visibility = View.VISIBLE
            if (adapter.items.isNotEmpty()) adapter.submit(emptyList())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // 全部源被隐藏时先重新探测（离线恢复后无需重启 App）
            if (SourceHealth.available().isEmpty()) {
                runSuspendCatching { SourceHealth.recheckHidden() }
                if (_b != null && SourceHealth.available().isNotEmpty()) buildChips()
            }
            runSuspendCatching {
                if (src == Src.JUZI) SiteApi.category(src, cat, sort, year, p, ttlMs = listTtlMs)
                else if (src == Src.DY555) SiteApi.category555(
                    cat,
                    area = sel555[1] ?: "", by = sel555[2] ?: "", cls = sel555[3] ?: "",
                    lang = sel555[4] ?: "", year = sel555[11] ?: "", page = p,
                    ttlMs = listTtlMs
                )
                else if (src == Src.MF) SiteApi.categoryMf(
                    cat,
                    genre = selMf["genre"] ?: "", year = selMf["year"] ?: "",
                    orderBy = selMf["order"] ?: "", page = p, ttlMs = listTtlMs
                )
                else SiteApi.category(src, cat, "", "", p, ttlMs = listTtlMs)
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
