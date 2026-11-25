package com.zongyi.box.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.zongyi.box.CrashGuard
import com.zongyi.box.R
import com.zongyi.box.databinding.ActivityMainBinding
import com.zongyi.box.data.VodItem

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV/盒子横屏（手机保持布局声明的竖屏）
        TvCompat.applyOrientation(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 上次崩溃报告（TV 真机无法连 adb 时的自诊断通道）
        CrashGuard.lastCrash(this)?.let { showCrashReport(it) }
        setContentView(binding.root)

        // 首页顶栏搜索按钮 → 搜索页（与底部导航的搜索入口行为一致）
        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchTo(R.id.nav_home)
                R.id.nav_category -> switchTo(R.id.nav_category)
                R.id.nav_live -> switchTo(R.id.nav_live)
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
    }

    /**
     * Tab 切换：Fragment 按 tag 复用（findFragmentByTag），add/show/hide 保活，
     * 切回不再重建视图、不再重新请求网络（旋转屏幕后也能从 FragmentManager 恢复）。
     */
    private fun switchTo(navId: Int) {
        binding.tvAppTitle.setText(
            if (navId == R.id.nav_live) R.string.live_title else R.string.app_name
        )
        val targetTag = when (navId) {
            R.id.nav_category -> "category"
            R.id.nav_live -> "live"
            R.id.nav_mine -> "mine"
            else -> "home"
        }
        val fm = supportFragmentManager
        val tabs = mapOf(
            "home" to { HomeFragment() },
            "category" to { CategoryFragment() },
            "live" to { LiveFragment() },
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

    /**
     * 上次崩溃报告（TV 真机无法连 adb 时的自诊断通道）：
     * 每次启动若存在上次崩溃记录则展示完整堆栈，可一键复制反馈/清除。
     */
    private fun showCrashReport(report: String) {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFF2F3F5.toInt())
            text = report
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (380 * dp).toInt()
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        AlertDialog.Builder(this, R.style.Theme_ZongYiBox)
            .setTitle("检测到上次异常崩溃")
            .setView(box)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                android.widget.Toast.makeText(this, "已复制崩溃报告", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("清除") { _, _ -> CrashGuard.clearLastCrash(this) }
            .show()
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
