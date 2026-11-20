package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSentenceTest {

    @Test
    fun singleSentenceIsItsOwnLine() {
        val line = LyricsSentence.at("他合上书，抬头看了一眼旧钟。", 4)
        assertEquals("他合上书，抬头看了一眼旧钟。", line.current)
        assertEquals(4, line.progress)
        assertNull(line.prev)
        assertNull(line.next)
    }

    @Test
    fun cursorSelectsMatchingSentence() {
        val text = "第一句。第二句！第三句？"
        val first = LyricsSentence.at(text, 1)
        assertEquals("第一句。", first.current)
        assertEquals("第二句！", first.next)
        assertNull(first.prev)

        val second = LyricsSentence.at(text, 5)
        assertEquals("第二句！", second.current)
        assertEquals("第一句。", second.prev)
        assertEquals("第三句？", second.next)
        // 进度是句内偏移, 不是整段偏移
        assertEquals(1, second.progress)

        val third = LyricsSentence.at(text, 9)
        assertEquals("第三句？", third.current)
        // 第三句从下标 8 开始, 游标 9 落在句内第 1 个字符
        assertEquals(1, third.progress)
    }

    @Test
    fun trailingClosersStayWithSentence() {
        val line = LyricsSentence.at("他说：“走吧。”然后又坐下了。", 4)
        assertEquals("他说：“走吧。”", line.current)
    }

    @Test
    fun newlineBreaksLine() {
        val line = LyricsSentence.at("上半行\n下半行", 5)
        assertEquals("上半行", line.prev)
        assertEquals("下半行", line.current)
    }

    @Test
    fun longRunWithoutPunctuationIsWindowedAroundCursor() {
        val text = "甲".repeat(500)
        val line = LyricsSentence.at(text, 250)
        assertTrue("超长句应被裁剪, 实际 ${line.current.length}", line.current.length <= 162)
        assertTrue("应带省略号", line.current.startsWith("…") && line.current.endsWith("…"))
        // 进度落在展示文本内
        assertTrue(line.progress in 1 until line.current.length)
    }

    @Test
    fun cursorBeyondEndIsClamped() {
        val line = LyricsSentence.at("只有一句。", 999)
        assertEquals("只有一句。", line.current)
        assertEquals("只有一句。".length, line.progress)
    }

    @Test
    fun blankTextYieldsEmptyLine() {
        val line = LyricsSentence.at("", 0)
        assertEquals("", line.current)
        assertEquals(0, line.progress)
    }
}
