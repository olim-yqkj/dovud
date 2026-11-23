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

/** Zaxira miya — Gemini (bepul limit bilan). */
class GeminiClient : LlmClient {
    override val name = "Gemini"

    override suspend fun chat(system: String, messages: List<Msg>, tools: List<ToolSpec>, agent: Boolean, fast: Boolean): LlmReply =
        withContext(Dispatchers.IO) {
            val contents = JSONArray()
            for (m in messages) {
                when (m.role) {
                    "assistant" -> {
                        val parts = JSONArray()
                        if (!m.text.isNullOrBlank()) parts.put(JSONObject().put("text", m.text))
                        m.toolCalls.forEach { c -> parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args))) }
                        if (parts.length() == 0) parts.put(JSONObject().put("text", "..."))
                        contents.put(JSONObject().put("role", "model").put("parts", parts))
                    }
                    "tool" -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                        JSONObject().put("functionResponse", JSONObject().put("name", m.toolName ?: "tool")
                            .put("response", JSONObject().put("result", m.text ?: ""))))))
                    else -> {
                        val parts = JSONArray()
                        if (!m.text.isNullOrBlank()) parts.put(JSONObject().put("text", m.text))
                        if (m.imageJpegB64 != null) parts.put(JSONObject().put("inline_data",
                            JSONObject().put("mime_type", "image/jpeg").put("data", m.imageJpegB64)))
                        if (parts.length() == 0) parts.put(JSONObject().put("text", "..."))
                        contents.put(JSONObject().put("role", "user").put("parts", parts))
                    }
                }
            }
            // Gemini ketma-ket bir xil rolni yoqtirmaydi — birlashtiramiz
            val merged = JSONArray()
            for (i in 0 until contents.length()) {
                val c = contents.getJSONObject(i)
                if (merged.length() > 0 && merged.getJSONObject(merged.length() - 1).getString("role") == c.getString("role")) {
                    val prev = merged.getJSONObject(merged.length() - 1).getJSONArray("parts")
                    val ps = c.getJSONArray("parts"); for (k in 0 until ps.length()) prev.put(ps.get(k))
                } else merged.put(c)
            }
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", merged)
                .put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", if (fast) 400 else 1500))
            if (tools.isNotEmpty()) {
                val decl = JSONArray()
                tools.forEach { t -> decl.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", upper(t.params))) }
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decl)))
            }
            val j = rawGenerate(body)
            val calls = ArrayList<ToolCall>()
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            var text = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                p.optJSONObject("functionCall")?.let { fc ->
                    calls.add(ToolCall("g${System.nanoTime()}_$i", fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
                }
                if (p.has("text") && !p.optBoolean("thought")) text.append(p.getString("text"))
            }
            LlmReply(text.toString().ifBlank { null }, calls)
        }

    /** OpenAPI sxemasini Gemini formatiga (turlar katta harf bilan). */
    private fun upper(o: JSONObject): JSONObject {
        val r = JSONObject()
        for (k in o.keys()) {
            val v = o.get(k)
            r.put(k, when {
                k == "type" && v is String -> v.uppercase()
                v is JSONObject -> if (k == "properties") JSONObject().also { pp -> for (pk in v.keys()) pp.put(pk, upper(v.getJSONObject(pk))) } else upper(v)
                else -> v
            })
        }
        return r
    }

    companion object {
        @Volatile private var resolvedModel: String? = null

        private fun model(key: String): String {
            val m = SecureStore.get(SecureStore.GEMINI_MODEL)
            if (m.isNotBlank() && m != "auto") return m
            resolvedModel?.let { return it }
            // Eng arzon "flash-lite" modelini o'zi topadi
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", key).build()
            val chosen = runCatching {
                Net.http.newCall(req).execute().use { r ->
                    val arr = JSONObject(r.body!!.string()).getJSONArray("models")
                    val names = (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                        .map { it.getString("name").removePrefix("models/") }
                        .filter { !it.contains("preview") && !it.contains("tts") && !it.contains("image") && !it.contains("live") }
                    names.filter { it.contains("flash-lite") }.maxOrNull()
                        ?: names.filter { it.contains("flash") }.maxOrNull()
                }
            }.getOrNull() ?: "gemini-flash-lite-latest"
            resolvedModel = chosen
            return chosen
        }

        fun rawGenerate(body: JSONObject): JSONObject {
            val key = SecureStore.get(SecureStore.GEMINI_KEY)
            if (key.isBlank()) throw ApiException(401, "Gemini kaliti kiritilmagan")
            val m = model(key)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            Net.http.newCall(req).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) { if (r.code == 404) resolvedModel = null; throw ApiException(r.code, s.take(400)) }
                val j = JSONObject(s)
                j.optJSONObject("usageMetadata")?.let { u -> Usage.addLlm("gemini:$m", u.optInt("promptTokenCount"), u.optInt("candidatesTokenCount")) }
                return j
            }
        }

        fun textOf(j: JSONObject): String {
            val parts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) { val p = parts.getJSONObject(i); if (p.has("text") && !p.optBoolean("thought")) sb.append(p.getString("text")) }
            return sb.toString()
        }
    }
}
