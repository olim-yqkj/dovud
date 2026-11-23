package uz.olim.dovud.brain

import org.json.JSONObject

/** AI "miya" uchun umumiy turlar. Modelni almashtirish uchun faqat LlmClient'ni almashtirish yetarli. */
data class ToolSpec(val name: String, val description: String, val params: JSONObject)
data class ToolCall(val id: String, val name: String, val args: JSONObject)

data class Msg(
    val role: String,                         // system | user | assistant | tool
    val text: String? = null,
    val imageJpegB64: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class LlmReply(val text: String?, val toolCalls: List<ToolCall>)

interface LlmClient {
    val name: String
    /** fast=true — tezkor rejim (kamroq o'ylaydi, qisqa javob). agent=true — telefonni boshqarish uchun kuchliroq model. */
    suspend fun chat(system: String, messages: List<Msg>, tools: List<ToolSpec>, agent: Boolean, fast: Boolean): LlmReply
}

/** Asosiy model ishlamasa (internet, limit, kalit), zaxiraga o'tadi. */
class FailoverLlm(private val primary: LlmClient, private val backup: LlmClient) : LlmClient {
    override val name = "failover"
    var lastUsed: String = primary.name
        private set

    override suspend fun chat(system: String, messages: List<Msg>, tools: List<ToolSpec>, agent: Boolean, fast: Boolean): LlmReply {
        return try {
            primary.chat(system, messages, tools, agent, fast).also { lastUsed = primary.name }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            uz.olim.dovud.Bus.log("Tizim", "${primary.name} ishlamadi (${e.message?.take(80)}), ${backup.name}ga o'tdim")
            backup.chat(system, messages, tools, agent, fast).also { lastUsed = backup.name }
        }
    }
}

object Params {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject(); props.forEach { p.put(it.first, it.second) }
        return JSONObject().put("type", "object").put("properties", p)
            .put("required", org.json.JSONArray(required))
    }
    fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    fun int(d: String) = JSONObject().put("type", "integer").put("description", d)
    fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)
    fun enumOf(d: String, vararg v: String) = JSONObject().put("type", "string").put("description", d)
        .put("enum", org.json.JSONArray(v.toList()))
}
