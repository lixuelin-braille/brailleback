package android.util

/**
 * 单元测试专用的 android.util.Log 影子实现。
 *
 * 主代码（Java/Kotlin）调用的是 Log 的**静态**方法；android.jar stub
 * 会抛 "Stub!" 异常，这里以空实现替代。注意必须用 companion @JvmStatic
 * 生成真正的静态方法（Kotlin object 生成的是实例方法，会报
 * IncompatibleClassChangeError）。
 */
class Log {
    companion object {
        @JvmStatic fun v(tag: String, msg: String): Int = 0
        @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int = 0
        @JvmStatic fun d(tag: String, msg: String): Int = 0
        @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int = 0
        @JvmStatic fun i(tag: String, msg: String): Int = 0
        @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int = 0
        @JvmStatic fun w(tag: String, msg: String): Int = 0
        @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int = 0
        @JvmStatic fun e(tag: String, msg: String): Int = 0
        @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
        @JvmStatic fun wtf(tag: String, msg: String): Int = 0
    }
}
