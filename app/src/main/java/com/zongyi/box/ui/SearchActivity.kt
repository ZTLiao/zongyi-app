package com.zongyi.box.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.zongyi.box.R
import com.zongyi.box.databinding.ActivitySearchBinding
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.Store
import com.zongyi.box.data.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var b: ActivitySearchBinding
    private lateinit var adapter: VodGridAdapter
    private var page = 1
    private var totalPages = 1
    private var loading = false
    private var keyword = ""
    private var hasSearched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = VodGridAdapter { Nav.detail(this, it) }
        b.rvGrid.layoutManager = GridLayoutManager(this, 3)
        b.rvGrid.adapter = adapter
        b.rvGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (loading || !adapter.items.isNotEmpty()) return
                val last = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 6 && page < totalPages) doSearch(page + 1, append = true)
            }
        })

        b.btnBack.setOnClickListener { finish() }
        b.btnGo.setOnClickListener { submit() }
        b.btnClearHistory.setOnClickListener {
            Store.clearSearchHistory(this)
            renderHistory()
        }
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }

        // 支持从外部直接带入关键词（如首页直达）
        val preset = intent.getStringExtra("keyword").orEmpty()
        if (preset.isNotBlank()) {
            b.etKeyword.setText(preset)
            submit()
        } else {
            renderHistory()
        }
    }

    private fun submit() {
        val kw = b.etKeyword.text.toString().trim()
        if (kw.isEmpty() || kw == keyword && hasSearched) return
        keyword = kw
        hasSearched = true
        // 本地保存搜索记录（去重置顶，最新在前）
        Store.addSearchHistory(this, kw)
        b.historyPanel.isVisible = false
        doSearch(1, append = false)
    }

    /** 搜索历史面板：Chip 展示，点击回填并搜索 */
    private fun renderHistory() {
        val items = Store.searchHistory(this)
        b.historyPanel.isVisible = items.isNotEmpty() && !hasSearched
        if (!b.historyPanel.isVisible) return
        b.historyChips.removeAllViews()
        items.forEach { kw ->
            val chip = Chip(this)
            chip.text = kw
            chip.isCheckable = false
            chip.isCloseIconVisible = true
            chip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_bg)
            chip.setTextColor(getColorStateList(R.color.chip_text))
            chip.closeIconTint = androidx.core.content.ContextCompat.getColorStateList(this, R.color.nav_inactive)
            chip.setOnClickListener {
                b.etKeyword.setText(kw)
                b.etKeyword.setSelection(kw.length)
                submit()
            }
            // 单条删除
            chip.setOnCloseIconClickListener {
                Store.removeSearchHistory(this, kw)
                renderHistory()
            }
            b.historyChips.addView(chip)
        }
    }

    private fun doSearch(p: Int, append: Boolean) {
        if (loading) return
        loading = true
        page = p
        b.progress.isVisible = !append
        b.tvEmpty.isVisible = false
        b.rvGrid.isVisible = !(!append && keyword.isEmpty())
        lifecycleScope.launch {
            // 双站搜索：综艺盒子分页 + 555影视单页（第一页时并入）
            val jz = async {
                runSuspendCatching { SiteApi.search(com.zongyi.box.data.Src.JUZI, keyword, p) }.getOrNull()
            }
            val dy = async {
                if (p == 1) runSuspendCatching { SiteApi.search(com.zongyi.box.data.Src.DY555, keyword, 1) }.getOrNull()
                else null
            }
            val jzData = jz.await()
            val dyData = dy.await()
            b.progress.isVisible = false
            loading = false

            if (jzData == null && dyData == null) {
                b.tvEmpty.text = "搜索失败：网络异常"
                b.tvEmpty.isVisible = !append
                return@launch
            }
            val items = mutableListOf<com.zongyi.box.data.VodItem>()
            jzData?.let { items.addAll(it.items) }
            dyData?.let { items.addAll(it.items) }
            totalPages = jzData?.totalPages ?: 1
            if (append) adapter.append(items) else adapter.submit(items)
            b.rvGrid.isVisible = items.isNotEmpty()
            b.tvEmpty.isVisible = !append && items.isEmpty()
        }
    }

    override fun onBackPressed() {
        // 返回时回到历史面板（若有历史），再次返回才退出页面
        val items = Store.searchHistory(this)
        if (hasSearched && items.isNotEmpty()) {
            hasSearched = false
            keyword = ""
            adapter.submit(emptyList())
            b.tvEmpty.isVisible = false
            b.rvGrid.isVisible = false
            b.historyPanel.isVisible = true
            renderHistory()
        } else {
            super.onBackPressed()
        }
    }
}
