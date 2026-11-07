package com.vibetools.pencil.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class AiClient {
    private fun client(timeoutSeconds:Int):OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(minOf(30,timeoutSeconds.coerceAtLeast(1)).toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.coerceAtLeast(1).toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.coerceAtLeast(1).toLong(), TimeUnit.SECONDS)
        .callTimeout((timeoutSeconds.toLong()+30L).coerceAtLeast(1L), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Throws(IOException::class)
    fun query(url: String, apiKey: String, model: String, prompt: String, imageBase64: String? = null, imageBase64List: List<String> = emptyList(), systemPrompt: String = "", memory: JSONArray = JSONArray(), timeoutSeconds:Int = 120, cancellation:AiCancellation = AiCancellation(), onUpdate:((String,String)->Unit)? = null): String {
        require(url.isNotBlank()) { "请先在设置中配置 API URL" }
        require(model.isNotBlank()) { "请先在设置中配置 AI 模型名称" }

        val endpoint = normalizeEndpoint(url)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        (listOfNotNull(imageBase64) + imageBase64List).filter { it.isNotBlank() }.forEach { encoded ->
            val dataUri = if (encoded.startsWith("data:")) encoded else "data:image/png;base64,$encoded"
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUri)))
        }
        val message = JSONObject().put("role", "user").put("content", content)
        val messages = JSONArray()
        if(systemPrompt.isNotBlank()) messages.put(JSONObject().put("role","system").put("content",systemPrompt))
        for(i in 0 until memory.length()){
            val previous=memory.optJSONObject(i)?:continue
            if(previous.optString("role") in listOf("user","assistant"))messages.put(previous)
        }
        messages.put(message)
        val payload = JSONObject().put("model", model).put("messages", messages)
        if(onUpdate != null) payload.put("stream", true)
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", if(onUpdate != null) "text/event-stream, application/json" else "application/json")
            .post(body)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        val call = client(timeoutSeconds).newCall(request)
        cancellation.attach(call)
        cancellation.check()
        call.execute().use { response ->
            if(response.isSuccessful && onUpdate != null && response.header("Content-Type").orEmpty().contains("text/event-stream",true)) {
                val answer=StringBuilder(); val reasoning=StringBuilder()
                var done=false
                val reader=AiSseReader { event ->
                    if(event.trim()=="[DONE]") done=true
                    else {
                        val json=JSONObject(event)
                        json.optJSONObject("error")?.let { throw IOException(it.optString("message","流式请求失败")) }
                        val choice=json.optJSONArray("choices")?.optJSONObject(0)
                        val delta=choice?.optJSONObject("delta")?:choice?.optJSONObject("message")
                        fun text(key:String):String = delta?.opt(key)?.takeIf { it is String } as? String ?: ""
                        reasoning.append(text("reasoning_content").ifEmpty { text("reasoning") })
                        answer.append(text("content").ifEmpty { choice?.optString("text").orEmpty() })
                        val split=splitAiThinking(answer.toString())
                        onUpdate(split.second,reasoning.toString()+split.first)
                    }
                }
                val source=response.body?.source()?:throw IOException("响应为空")
                while(!done) { cancellation.check(); val line=source.readUtf8Line()?:break; reader.line(line) }
                reader.flush(); cancellation.check()
                return splitAiThinking(answer.toString()).second
            }
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val error = JSONObject(responseText).optJSONObject("error")
                    error?.optString("message")?.takeIf { it.isNotBlank() } ?: responseText
                }.getOrDefault(responseText).take(800)
                throw IOException("HTTP ${response.code}: $detail")
            }
            cancellation.check()
            val text=parseText(responseText)
            val split=splitAiThinking(text)
            val message=runCatching { JSONObject(responseText).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") }.getOrNull()
            val reasoning=message?.optString("reasoning_content").orEmpty().ifEmpty { message?.optString("reasoning").orEmpty() }
            onUpdate?.invoke(split.second,reasoning+split.first)
            return split.second
        }
    }

    fun errorMessage(error:Throwable,timeoutSeconds:Int=120):String = when(error){is SocketTimeoutException->"请求超时：模型响应超过 $timeoutSeconds 秒，请检查网络或降低图片/选区大小";is IOException->error.message?:"网络请求失败";else->error.message?:"请求失败"}

    private fun normalizeEndpoint(raw: String): String {
        val clean=raw.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            else -> clean
        }
    }

    private fun parseText(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        json.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        val choices = json.optJSONArray("choices")
        val first = choices?.optJSONObject(0) ?: return raw
        first.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }?.let { return it }
        first.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        return raw
    }
}
