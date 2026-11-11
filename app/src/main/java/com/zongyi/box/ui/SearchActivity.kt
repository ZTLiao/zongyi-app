package com.zongyi.box.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zongyi.box.databinding.ActivitySearchBinding
import com.zongyi.box.data.SiteApi
import com.zongyi.box.data.runSuspendCatching
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
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
    }

    private fun submit() {
        val kw = b.etKeyword.text.toString().trim()
        if (kw.isEmpty() || kw == keyword && hasSearched) return
        keyword = kw
        hasSearched = true
        doSearch(1, append = false)
    }

    private fun doSearch(p: Int, append: Boolean) {
        if (loading) return
        loading = true
        page = p
        b.progress.isVisible = !append
        b.tvEmpty.isVisible = false
        lifecycleScope.launch {
            runSuspendCatching { SiteApi.search(keyword, p) }
                .onSuccess { data ->
                    totalPages = data.totalPages
                    if (append) adapter.append(data.items) else adapter.submit(data.items)
                    b.tvEmpty.isVisible = !append && data.items.isEmpty()
                }
                .onFailure { e ->
                    if (!append) b.tvEmpty.text = "搜索失败：${e.message}"
                    b.tvEmpty.isVisible = !append
                }
            b.progress.isVisible = false
            loading = false
        }
    }
}
