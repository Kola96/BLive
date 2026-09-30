package com.blive.tv.utils

/**
 * 语义化版本号比较工具。
 *
 * 支持 "1.0.2"、"v1.0.2"、"1.0"、"2" 等纯数字点分格式。
 * 长度不一致时短的一侧补零：1.0 与 1.0.0 视为相等。
 * 遇到非数字段（如 "1.0.2-beta"）按字面值降级比较，保证不抛异常。
 */
object VersionComparator {

    /**
     * @return 负数: a < b；0: 相等；正数: a > b
     */
    fun compare(a: String, b: String): Int {
        val pa = normalize(a)
        val pb = normalize(b)
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val sa = pa.getOrNull(i)
            val sb = pb.getOrNull(i)
            if (sa == null && sb == null) continue
            // 短的一侧补 "0"
            val ta = sa ?: "0"
            val tb = sb ?: "0"
            val na = ta.toIntOrNull()
            val nb = tb.toIntOrNull()
            val cmp = if (na != null && nb != null) {
                na.compareTo(nb)
            } else {
                ta.compareTo(tb)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** a 是否比 b 新 */
    fun isNewer(a: String, b: String): Boolean = compare(a, b) > 0

    private fun normalize(version: String): List<String> {
        return version.trim()
            .removePrefix("v").removePrefix("V")
            .split(".")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
