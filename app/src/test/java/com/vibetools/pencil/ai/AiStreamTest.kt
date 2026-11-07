package com.vibetools.pencil.ai

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiStreamTest {
    @Test fun sseSupportsMultilineCommentsAndFinalEvent() {
        val events=mutableListOf<String>()
        val reader=AiSseReader{events+=it}
        listOf(": heartbeat","event: message","data: first","data: second","","data: [DONE]").forEach(reader::line)
        reader.flush()
        assertEquals(listOf("first\nsecond","[DONE]"),events)
    }
    @Test fun inlineThinkingHandlesEveryTokenBoundary() {
        val source="<think>分析一下</think>正式回答"
        for(i in 1..source.length){
            val (thought,answer)=splitAiThinking(source.take(i))
            assertFalse(answer.contains("<think>"))
            assertFalse(answer.contains("</think>"))
            if(i<source.indexOf("正式"))assertEquals("",answer)
        }
        assertEquals("分析一下" to "正式回答",splitAiThinking(source))
    }
    @Test fun ordinaryAnswersAndMathArePreserved() {
        val answer="答案：\n\\(x^2 + y^2\\)"
        assertEquals("" to answer,splitAiThinking(answer))
        assertEquals("思考" to "",splitAiThinking("<think>思考</thi"))
    }
    @Test fun cancellationWorksBeforeCallIsAttached() {
        val cancellation=AiCancellation();cancellation.cancel()
        assertTrue(cancellation.cancelled)
        try { cancellation.check(); fail("Expected cancellation") } catch(expected:IOException) { assertEquals("已停止输出",expected.message) }
    }
}
