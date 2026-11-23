package uz.olim.dovud.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uz.olim.dovud.ApiException
import uz.olim.dovud.Net
import uz.olim.dovud.memory.Usage
import uz.olim.dovud.settings.SecureStore

class OpenAiClient : LlmClient {
    override val name = "OpenAI"
    private val noReasoningParam = HashSet<String>()

    override suspend fun chat(system: String, messages: List<Msg>, tools: List<ToolSpec>, agent: Boolean, fast: Boolean): LlmReply =
        withContext(Dispatchers.IO) {
            val key = SecureStore.get(SecureStore.OPENAI_KEY)
            if (key.isBlank()) throw ApiException(401, "OpenAI kaliti kiritilmagan")
            val first = SecureStore.get(if (agent) SecureStore.OPENAI_AGENT_MODEL else SecureStore.OPENAI_CHAT_MODEL)
            val candidates = listOf(first, "gpt-5.4-mini", "gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini").distinct()
            var last: Exception? = null
            for (model in candidates) {
                try { return@withContext call(key, model, system, messages, tools, fast) }
                catch (e: ApiException) {
                    last = e
                    val modelProblem = e.code == 404 || (e.code == 400 && e.message?.contains("model", true) == true)
                    if (!modelProblem) throw e
                }
            }
            throw last ?: ApiException(0, "OpenAI xatosi")
        }

    private fun call(key: String, model: String, system: String, messages: List<Msg>, tools: List<ToolSpec>, fast: Boolean): LlmReply {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in messages) msgs.put(toJson(m))
        val body = JSONObject().put("model", model).put("messages", msgs)
        val reasoning = model.startsWith("gpt-5") || model.startsWith("o")
        if (reasoning) {
            body.put("max_completion_tokens", if (fast) 600 else 2000)
            if (model !in noReasoningParam) body.put("reasoning_effort", if (fast) "none" else "low")
        } else {
            body.put("max_tokens", if (fast) 300 else 800).put("temperature", 0.3)
        }
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) arr.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.params)))
            body.put("tools", arr)
        }
        val req = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Net.http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                // Ba'zi modellar reasoning_effort qiymatini qabul qilmaydi — usiz qayta urinamiz
                if (r.code == 400 && reasoning && s.contains("reasoning_effort") && model !in noReasoningParam) {
                    noReasoningParam.add(model)
                    return call(key, model, system, messages, tools, fast)
                }
                throw ApiException(r.code, s.take(400))
            }
            val j = JSONObject(s)
            j.optJSONObject("usage")?.let { u ->
                Usage.addLlm(model, u.optInt("prompt_tokens"), u.optInt("completion_tokens"))
            }
            val msg = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = ArrayList<ToolCall>()
            msg.optJSONArray("tool_calls")?.let { tc ->
                for (i in 0 until tc.length()) {
                    val c = tc.getJSONObject(i); val f = c.getJSONObject("function")
                    val args = runCatching { JSONObject(f.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    calls.add(ToolCall(c.getString("id"), f.getString("name"), args))
                }
            }
            val text = if (msg.isNull("content")) null else msg.optString("content")
            return LlmReply(text, calls)
        }
    }

    private fun toJson(m: Msg): JSONObject = when (m.role) {
        "tool" -> JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId).put("content", m.text ?: "")
        "assistant" -> {
            val o = JSONObject().put("role", "assistant").put("content", m.text ?: JSONObject.NULL)
            if (m.toolCalls.isNotEmpty()) {
                val arr = JSONArray()
                m.toolCalls.forEach { c -> arr.put(JSONObject().put("id", c.id).put("type", "function")
                    .put("function", JSONObject().put("name", c.name).put("arguments", c.args.toString()))) }
                o.put("tool_calls", arr)
            }
            o
        }
        else -> {
            if (m.imageJpegB64 != null) {
                val parts = JSONArray()
                if (!m.text.isNullOrBlank()) parts.put(JSONObject().put("type", "text").put("text", m.text))
                parts.put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,${m.imageJpegB64}").put("detail", "auto")))
                JSONObject().put("role", "user").put("content", parts)
            } else JSONObject().put("role", "user").put("content", m.text ?: "")
        }
    }
}
