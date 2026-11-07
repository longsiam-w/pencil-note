package com.vibetools.pencil.ai

import okhttp3.Call
import org.json.JSONObject
import java.io.IOException

/** One request owns one cancellation handle, including cancellation before the call exists. */
class AiCancellation {
    @Volatile var cancelled = false
        private set
    @Volatile private var call: Call? = null
    fun attach(value: Call) { call = value; if (cancelled) value.cancel() }
    fun cancel() { cancelled = true; call?.cancel() }
    fun check() { if (cancelled) throw IOException("已停止输出") }
}

/** Incremental SSE framing; events may span several data lines. */
internal class AiSseReader(private val accept: (String) -> Unit) {
    private val data = mutableListOf<String>()
    fun line(line: String) {
        if (line.isEmpty()) flush()
        else if (line.startsWith("data:")) data += line.substring(5).removePrefix(" ")
    }
    fun flush() { if (data.isNotEmpty()) { val event = data.joinToString("\n"); data.clear(); accept(event) } }
}

/** Separates providers' inline <think> output, including tags split across tokens. */
internal fun splitAiThinking(raw: String): Pair<String, String> {
    val reasoning = StringBuilder(); val answer = StringBuilder()
    var rest = raw; var thinking = false
    while (rest.isNotEmpty()) {
        val tag = if (thinking) "</think>" else "<think>"
        val index = rest.indexOf(tag)
        if (index >= 0) {
            (if (thinking) reasoning else answer).append(rest.substring(0, index))
            thinking = !thinking; rest = rest.substring(index + tag.length)
        } else {
            var count = 0
            for (n in 1 until tag.length) if (rest.endsWith(tag.take(n))) count = n
            (if (thinking) reasoning else answer).append(rest.dropLast(count)); break
        }
    }
    return reasoning.toString() to answer.toString()
}
