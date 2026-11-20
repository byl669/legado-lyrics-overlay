package io.legado.app.service

/**
 * 段落朗读模式下的歌词行切分。
 *
 * 传统模式 contentList 的每一项就是一句话，可直接丢给歌词窗；
 * 段落模式下 contentList 的每一项是「朗读块」—— TTS 引擎可达数千字符，HTTP 引擎直接是整段。
 * 直接显示会把悬浮窗撑爆，因此这里按句末标点再切一层，并用块内进度定位当前句。
 */
internal object LyricsSentence {

    /** 单行上限：超过此长度（通常是通篇无标点的长段）改为围绕进度开窗 */
    private const val MAX_LINE = 160

    /** 开窗时进度点左右保留的字符数 */
    private const val WINDOW_RADIUS = 64

    /** 上下句展示上限 */
    private const val CONTEXT_LIMIT = 60

    private const val END_MARKS = "。！？!?；;…"
    private const val CLOSERS = "”’」』）)】》"

    data class Line(
        /** 上一句（已裁剪，可能为 null） */
        val prev: String?,
        /** 当前句 */
        val current: String,
        /** 下一句（已裁剪，可能为 null） */
        val next: String?,
        /** 当前句内已读字符数 */
        val progress: Int,
    )

    /**
     * @param text   朗读块原文
     * @param cursor 块内已读字符数（readAloudChapterStart - readAloudNumber）
     */
    fun at(text: String, cursor: Int): Line {
        if (text.isEmpty()) return Line(null, "", null, 0)
        val pos = cursor.coerceIn(0, text.length)
        val spans = split(text)
        if (spans.isEmpty()) {
            val (body, inner) = window(text, 0, text.length, pos)
            return Line(null, body, null, inner)
        }
        var index = spans.indexOfLast { it.start <= pos }
        if (index < 0) index = 0
        val span = spans[index]
        val (body, inner) = window(text, span.start, span.end, pos)
        val prev = spans.getOrNull(index - 1)
            ?.let { clipTail(text.substring(it.start, it.end)) }
        val next = spans.getOrNull(index + 1)
            ?.let { clipHead(text.substring(it.start, it.end)) }
        return Line(prev, body, next, inner)
    }

    // ------------------------------------------------------------------ 内部

    private data class Span(val start: Int, val end: Int)

    /** 按句末标点切分；标点后的引号、右括号归入前一句 */
    private fun split(text: String): List<Span> {
        val spans = ArrayList<Span>()
        var start = firstVisible(text, 0)
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (ch in END_MARKS) {
                var end = i + 1
                while (end < text.length && (text[end] in END_MARKS || text[end] in CLOSERS)) end++
                if (text.substring(start, end).isNotBlank()) spans += Span(start, end)
                start = firstVisible(text, end)
                i = start
                continue
            }
            if (ch == '\n') {
                if (text.substring(start, i).isNotBlank()) spans += Span(start, i)
                start = firstVisible(text, i)
                i = start
                continue
            }
            i++
        }
        if (start < text.length && text.substring(start).isNotBlank()) {
            spans += Span(start, text.length)
        }
        return spans
    }

    private fun firstVisible(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /**
     * 取出 [spanStart, spanEnd) 的可见文本；超长时围绕 pos 开窗。
     * 返回 (展示文本, 该文本内的已读字符数)
     */
    private fun window(text: String, spanStart: Int, spanEnd: Int, pos: Int): Pair<String, Int> {
        val raw = text.substring(spanStart, spanEnd)
        val leading = raw.length - raw.trimStart().length
        val body = raw.trim()
        var inner = (pos - spanStart - leading).coerceIn(0, body.length)
        if (body.length <= MAX_LINE) return body to inner

        var from = (inner - WINDOW_RADIUS).coerceAtLeast(0)
        var to = (from + MAX_LINE).coerceAtMost(body.length)
        from = (to - MAX_LINE).coerceAtLeast(0)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < body.length) "…" else ""
        val shown = prefix + body.substring(from, to) + suffix
        inner = inner - from + prefix.length
        return shown to inner.coerceIn(0, shown.length)
    }

    private fun clipHead(text: String): String {
        val body = text.trim()
        return if (body.length <= CONTEXT_LIMIT) body
        else body.take(CONTEXT_LIMIT) + "…"
    }

    private fun clipTail(text: String): String {
        val body = text.trim()
        return if (body.length <= CONTEXT_LIMIT) body
        else "…" + body.takeLast(CONTEXT_LIMIT)
    }
}
