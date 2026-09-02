package android.util

/**
 * 单元测试专用的 android.util.SparseIntArray 影子实现。
 *
 * 单元测试的 android.jar 是纯 stub（调用即抛异常），而 WrapStrategy 的
 * 换行算法核心依赖 SparseIntArray 的**有序 key + 二分查找**语义。
 * 此实现放在 test 源集中，运行时类路径优先于 mockable android.jar，
 * 从而以真实语义替代 stub。仅覆盖项目实际用到的 API。
 *
 * 语义对齐 AOSP ContainerHelpers.binarySearch：
 * indexOfKey 命中返回下标；未命中返回 ~(插入点)。
 */
class SparseIntArray {

    private val keys = ArrayList<Int>()
    private val vals = ArrayList<Int>()

    /** 二分查找：命中返回下标，未命中返回 ~(插入点)。 */
    private fun search(key: Int): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midKey = keys[mid]
            when {
                midKey < key -> lo = mid + 1
                midKey > key -> hi = mid - 1
                else -> return mid
            }
        }
        return lo.inv()
    }

    fun get(key: Int): Int = get(key, 0)

    fun get(key: Int, valueIfKeyNotFound: Int): Int {
        val i = search(key)
        return if (i >= 0) vals[i] else valueIfKeyNotFound
    }

    fun put(key: Int, value: Int) {
        val i = search(key)
        if (i >= 0) {
            vals[i] = value
        } else {
            val at = i.inv()
            keys.add(at, key)
            vals.add(at, value)
        }
    }

    /** 项目用法保证 key 递增，但实现保持与 put 相同的有序插入，更稳妥。 */
    fun append(key: Int, value: Int) = put(key, value)

    fun indexOfKey(key: Int): Int = search(key)

    fun keyAt(index: Int): Int = keys[index]

    fun valueAt(index: Int): Int = vals[index]

    fun size(): Int = keys.size

    fun clear() {
        keys.clear()
        vals.clear()
    }

    fun delete(key: Int) {
        val i = search(key)
        if (i >= 0) {
            keys.removeAt(i)
            vals.removeAt(i)
        }
    }
}
