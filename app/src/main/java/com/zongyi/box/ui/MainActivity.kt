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
                R.id.nav_home -> switchTo(HomeFragment())
                R.id.nav_category -> switchTo(CategoryFragment())
                R.id.nav_mine -> switchTo(MineFragment())
                R.id.nav_search -> {
                    startActivity(Intent(this, SearchActivity::class.java))
                    return@setOnItemSelectedListener false
                }
            }
            true
        }

        if (savedInstanceState == null) switchTo(HomeFragment())
        else binding.bottomNav.selectedItemId =
            savedInstanceState.getInt("tab", R.id.nav_home)
    }

    private fun switchTo(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .commitAllowingStateLoss()
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
