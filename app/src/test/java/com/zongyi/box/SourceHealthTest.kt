package com.zongyi.box

import com.zongyi.box.data.SourceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 源健康检测：连续失败自动隐藏 + 恢复解除隐藏 */
class SourceHealthTest {

    @Test
    fun failTwice_hidesSource() {
        SourceHealth.initForTest()
        assertFalse(SourceHealth.isHidden(com.zongyi.box.data.Src.TBL))
        SourceHealth.markFailure(com.zongyi.box.data.Src.TBL)
        assertFalse("失败 1 次不隐藏", SourceHealth.isHidden(com.zongyi.box.data.Src.TBL))
        SourceHealth.markFailure(com.zongyi.box.data.Src.TBL)
        assertTrue("连续失败 2 次应隐藏", SourceHealth.isHidden(com.zongyi.box.data.Src.TBL))
    }

    @Test
    fun hiddenSource_excludedEverywhere() {
        SourceHealth.initForTest(mapOf("mf" to Pair(2, true)))
        val available = SourceHealth.available()
        assertFalse("可用源不应包含 mf", available.any { it.key == "mf" })
        assertEquals(com.zongyi.box.data.Src.entries.size - 1, available.size)
        // availableFlow 与 available() 一致
        assertEquals(available, SourceHealth.availableFlow.value)
    }

    @Test
    fun success_resetsAndRevives() {
        SourceHealth.initForTest(mapOf("tbl" to Pair(2, true)))
        assertTrue(SourceHealth.isHidden(com.zongyi.box.data.Src.TBL))
        SourceHealth.markSuccess(com.zongyi.box.data.Src.TBL)
        assertFalse("成功一次即解除隐藏", SourceHealth.isHidden(com.zongyi.box.data.Src.TBL))
        assertEquals(0, SourceHealth.failures(com.zongyi.box.data.Src.TBL))
        assertTrue(SourceHealth.available().any { it.key == "tbl" })
    }

    @Test
    fun failThenSuccess_resetsCounter() {
        SourceHealth.initForTest()
        SourceHealth.markFailure(com.zongyi.box.data.Src.MF)
        SourceHealth.markSuccess(com.zongyi.box.data.Src.MF)
        SourceHealth.markFailure(com.zongyi.box.data.Src.MF)
        assertFalse("计数应被成功清零，不隐藏", SourceHealth.isHidden(com.zongyi.box.data.Src.MF))
    }
}
