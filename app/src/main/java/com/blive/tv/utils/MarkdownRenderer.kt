package com.blive.tv.utils

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 轻量级 Markdown 渲染器，用于把 Release 更新日志渲染成 [CharSequence]。
 *
 * 支持的语法子集：
 * - 标题: `#` / `##` / `###` / `####`（渲染为不同字号 + 粗体）
 * - 无序列表: `- item` / `* item` / `+ item`（加 • 前缀）
 * - 粗体: `**bold**` / `__bold__`
 * - 斜体: `*italic*` / `_italic_`
 * - 行内代码: `` `code` ``（monospace + 背景色）
 * - 代码块: ```` ``` ````（整块 monospace + 背景色）
 * - 引用: `> text`（缩进 + 斜体）
 * - 链接: `[text](url)`（只显示 text，染色区分；TV 上不可点击）
 * - 分隔线: `---` / `***`（转换为空行）
 * - 转义: `\*` 显示为字面 `*`
 *
 * 不支持：图片、表格、嵌套列表、HTML 标签。未识别语法按原样输出。
 */
object MarkdownRenderer {

    // 颜色（暗色背景）
    private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xE6FFFFFF.toInt()
    private const val COLOR_CODE_BG = 0x33FFFFFF
    private const val COLOR_LINK = 0xFF6BA5FF.toInt()
    private const val COLOR_QUOTE = 0xB3FFFFFF.toInt()

    // 字号（单位：px，由 density 决定；这里用 sp 级别比例）
    private const val SIZE_H1 = 22
    private const val SIZE_H2 = 20
    private const val SIZE_H3 = 18
    private const val SIZE_H4 = 16
    private const val SIZE_BODY = 15

    fun render(markdown: String): CharSequence {
        if (markdown.isBlank()) return ""
        val out = SpannableStringBuilder()
        val lines = markdown.lines()
        var i = 0
        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        while (i < lines.size) {
            val line = lines[i]

            // 代码块边界
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块：渲染并清空
                    appendCodeBlock(out, codeBlockContent.toString())
                    codeBlockContent.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }
            if (inCodeBlock) {
                if (codeBlockContent.isNotEmpty()) codeBlockContent.append('\n')
                codeBlockContent.append(line)
                i++
                continue
            }

            // 空行
            if (line.isBlank()) {
                // 避免连续多个空行
                if (out.isNotEmpty() && !out.endsWith("\n\n")) {
                    out.append('\n')
                }
                i++
                continue
            }

            // 标题
            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2].trim()
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                appendHeading(out, text, level)
                out.append('\n')
                i++
                continue
            }

            // 分隔线
            if (Regex("^\\s*[-*_]{3,}\\s*$").matches(line)) {
                // 渲染为空行
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                i++
                continue
            }

            // 无序列表
            val listMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
            if (listMatch != null) {
                val content = listMatch.groupValues[1].trim()
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                appendListItem(out, content)
                out.append('\n')
                i++
                continue
            }

            // 引用
            val quoteMatch = Regex("^>\\s*(.+)$").find(line)
            if (quoteMatch != null) {
                val content = quoteMatch.groupValues[1].trim()
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                appendQuote(out, content)
                out.append('\n')
                i++
                continue
            }

            // 普通段落
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            appendInline(out, line.trim(), COLOR_TEXT_SECONDARY, SIZE_BODY)
            out.append('\n')
            i++
        }

        // 未闭合的代码块：按已收集内容渲染
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            appendCodeBlock(out, codeBlockContent.toString())
        }

        return out.trimEnd()
    }

    // ---------------- Block 级 ----------------

    private fun appendHeading(out: SpannableStringBuilder, text: String, level: Int) {
        val size = when (level) {
            1 -> SIZE_H1
            2 -> SIZE_H2
            3 -> SIZE_H3
            else -> SIZE_H4
        }
        val start = out.length
        appendInline(out, text, COLOR_TEXT_PRIMARY, size)
        out.setSpan(
            StyleSpan(Typeface.BOLD),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendListItem(out: SpannableStringBuilder, content: String) {
        val start = out.length
        out.append("• ")
        appendInline(out, content, COLOR_TEXT_SECONDARY, SIZE_BODY)
        // 整行缩进
        out.setSpan(
            LeadingMarginSpan.Standard(20, 0),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendQuote(out: SpannableStringBuilder, content: String) {
        val start = out.length
        appendInline(out, content, COLOR_QUOTE, SIZE_BODY)
        out.setSpan(
            StyleSpan(Typeface.ITALIC),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.setSpan(
            LeadingMarginSpan.Standard(30, 0),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendCodeBlock(out: SpannableStringBuilder, code: String) {
        if (code.isBlank()) return
        val start = out.length
        out.append(code.trimEnd())
        out.append('\n')
        out.setSpan(
            TypefaceSpan("monospace"),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.setSpan(
            BackgroundColorSpan(COLOR_CODE_BG),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.setSpan(
            AbsoluteSizeSpan(SIZE_BODY, true),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.setSpan(
            LeadingMarginSpan.Standard(20, 0),
            start, out.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    // ---------------- Inline 级 ----------------

    /**
     * 解析一段文本中的 inline 元素：粗体/斜体/代码/链接。
     * 逐字符扫描，遇到标记就递归处理标记内内容。
     */
    private fun appendInline(
        out: SpannableStringBuilder,
        text: String,
        color: Int,
        sizeSp: Int
    ) {
        var i = 0
        val n = text.length
        val plainStart = out.length

        fun appendPlain(s: String) {
            if (s.isEmpty()) return
            val start = out.length
            out.append(s)
            applyTextStyle(out, start, out.length, color, sizeSp)
        }

        while (i < n) {
            val c = text[i]
            when {
                // 转义字符
                c == '\\' && i + 1 < n && text[i + 1] in "*_`[]()#" -> {
                    appendPlain(text[i + 1].toString())
                    i += 2
                }
                // 行内代码 `code`
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        val code = text.substring(i + 1, end)
                        val start = out.length
                        out.append(code)
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(COLOR_CODE_BG), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(AbsoluteSizeSpan(sizeSp, true), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(color), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        appendPlain(c.toString())
                        i++
                    }
                }
                // 粗体 **text** 或 __text__
                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val inner = text.substring(i + 2, end)
                        val start = out.length
                        appendInline(out, inner, color, sizeSp)
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        appendPlain(c.toString())
                        i++
                    }
                }
                c == '_' && i + 1 < n && text[i + 1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i + 2) {
                        val inner = text.substring(i + 2, end)
                        val start = out.length
                        appendInline(out, inner, color, sizeSp)
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        appendPlain(c.toString())
                        i++
                    }
                }
                // 斜体 *text*（需要避免把 ** 误判）
                c == '*' && (i + 1 >= n || text[i + 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        val inner = text.substring(i + 1, end)
                        val start = out.length
                        appendInline(out, inner, color, sizeSp)
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        appendPlain(c.toString())
                        i++
                    }
                }
                // 链接 [text](url)
                c == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i + 1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket + 2) {
                            val label = text.substring(i + 1, closeBracket)
                            val start = out.length
                            appendInline(out, label, COLOR_LINK, sizeSp)
                            i = closeParen + 1
                        } else {
                            appendPlain(c.toString())
                            i++
                        }
                    } else {
                        appendPlain(c.toString())
                        i++
                    }
                }
                // 普通字符：一直读到下一个特殊字符
                else -> {
                    var j = i + 1
                    while (j < n && text[j] !in "\\`*_[") {
                        j++
                    }
                    appendPlain(text.substring(i, j))
                    i = j
                }
            }
        }

        // 如果整段都是普通文本，也应用样式
        if (plainStart == 0 && out.isEmpty() && text.isNotEmpty()) {
            // 不会走到，因为 appendPlain 总会被调用
        }
    }

    private fun applyTextStyle(
        out: SpannableStringBuilder,
        start: Int,
        end: Int,
        color: Int,
        sizeSp: Int
    ) {
        if (end <= start) return
        out.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(AbsoluteSizeSpan(sizeSp, true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
