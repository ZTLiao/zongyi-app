package com.zongyi.box.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.zongyi.box.R
import com.zongyi.box.databinding.ActivityMainBinding
import com.zongyi.box.data.VodItem

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 首页顶栏搜索按钮 → 搜索页（与底部导航的搜索入口行为一致）
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTo(R.id.nav_home)
                R.id.nav_category -> switchTo(R.id.nav_category)
                R.id.nav_mine -> switchTo(R.id.nav_mine)
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java))
                    return@setOnItemSelectedListener false
                }
            }
            true
        }

        if (savedInstanceState == null) switchTo(R.id.nav_home)
        else {
            val tab = savedInstanceState.getInt("tab", R.id.nav_home)
            binding.bottomNav.selectedItemId = tab
            // selectedItemId 已等于该值时监听器不会触发，显式补一次切换
            switchTo(tab)
        }

        // 开屏广告：本次运行首次进入主界面时展示（未配置 Direct Link 时静默跳过）
        com.zongyi.box.ads.AdManager.showAppOpen(this)

        // 静默检查更新：延迟到首屏与开屏广告之后，有新版本才弹窗
        UpdatePrompt.autoCheck(this)
    }

    /**
     * Tab 切换：Fragment 按 tag 复用（findFragmentByTag），add/show/hide 保活，
     * 切回不再重建视图、不再重新请求网络（旋转屏幕后也能从 FragmentManager 恢复）。
     */
    private fun switchTo(navId: Int) {
        val targetTag = when (navId) {
            R.id.nav_category -> "category"
            R.id.nav_mine -> "mine"
            else -> "home"
        }
        val fm = supportFragmentManager
        val tabs = mapOf(
            "home" to { HomeFragment() },
            "category" to { CategoryFragment() },
            "mine" to { MineFragment() },
        )
        fm.beginTransaction().apply {
            tabs.forEach { (tag, create) ->
                val f = fm.findFragmentByTag(tag) ?: create()
                if (tag == targetTag) {
                    if (f.isAdded) show(f) else add(R.id.container, f, tag)
                } else if (f.isAdded) {
                    hide(f)
                }
            }
        }.commitAllowingStateLoss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", binding.bottomNav.selectedItemId)
    }

    companion object {
        fun startDetail(ctx: android.content.Context, item: VodItem) {
            ctx.startActivity(Intent(ctx, DetailActivity::class.java).apply {
                putExtra("id", item.id)
                putExtra("title", item.title)
                putExtra("cover", item.cover)
                putExtra("remark", item.remark)
            })
        }
    }
}
