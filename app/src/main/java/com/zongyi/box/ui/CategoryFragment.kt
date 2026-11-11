package com.zongyi.box.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.zongyi.box.R
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.runSuspendCatching
import com.zongyi.box.databinding.FragmentCategoryBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CategoryFragment : Fragment() {

    private var _b: FragmentCategoryBinding? = null
    private val b get() = _b!!

    private val cats = listOf(
        "大陆综艺" to 1, "港台综艺" to 2, "日韩综艺" to 3, "欧美综艺" to 4
    )
    private val sorts = listOf("按更新" to "time", "按人气" to "hits", "按评分" to "score")
    private val years = listOf(
        "全部" to "", "2026" to "2026", "2025" to "2025", "2024" to "2024",
        "2023" to "2023", "2022" to "2022", "2021" to "2021", "2020" to "2020",
        "2019" to "2019", "2018" to "2018", "2017" to "2017"
    )

    private var cat = 1
    private var sort = "time"
    private var year = ""
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

    private fun buildChips() {
        fun fill(group: com.google.android.material.chip.ChipGroup, list: List<Pair<String, Any>>, checkedIndex: Int, onPick: (Any) -> Unit) {
            group.removeAllViews()
            list.forEachIndexed { idx, (label, value) ->
                val chip = Chip(requireContext())
                chip.text = label
                chip.isCheckable = true
                chip.isChecked = idx == checkedIndex
                chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.chip_bg)
                chip.setTextColor(requireContext().getColorStateList(R.color.chip_text))
                chip.setOnClickListener { if (!chip.isChecked) chip.isChecked = true; onPick(value) }
                group.addView(chip)
            }
        }
        fill(b.chipsCategory, cats, 0) { v -> cat = v as Int; refresh() }
        fill(b.chipsSort, sorts, 0) { v -> sort = v as String; refresh() }
        fill(b.chipsYear, years, 0) { v -> year = v as String; refresh() }
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
            runSuspendCatching { SiteApi.category(cat, sort, year, p) }
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
