package com.zongyi.box.data

import kotlinx.coroutines.CancellationException

/**
 * 协程安全的 try-catch：网络失败转 Result，但【取消异常原样抛出】。
 *
 * 用它替代 runCatching 包裹 suspend 调用——runCatching 会吞掉
 * CancellationException，导致协程取消后失败回调继续访问已销毁的
 * ViewBinding（_b!! → NPE）而崩溃：快速切页 + 网络超时即可复现。
 *
 * 捕获 Throwable 而非 Exception：部分 TV/盒子把 jsoup 等库内置进系统
 * framework，被遮蔽后抛的是 NoSuchMethodError/NoClassDefFoundError 这类
 * Error——只捕 Exception 会让 Error 直接杀死进程（TCL ak30a5 实测）。
 */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (e: Throwable) {
    Result.failure(e)
}
